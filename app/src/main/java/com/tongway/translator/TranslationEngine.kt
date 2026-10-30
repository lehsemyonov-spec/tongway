package com.tongway.translator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Перевод: нейросетью по цепочке сервисов (Groq → OpenRouter, у которых есть ключ) или прямо
 * на телефоне через Google ML Kit. Если ни один сервис не ответил, переводим на телефоне и пишем почему.
 * Вызывать с главного потока: переводчики и память переводов хранятся без блокировок.
 */
class TranslationEngine(private val context: Context) {

    data class Result(
        val original: String,
        val translated: String,
        val sourceLanguage: String,
        val alreadyTarget: Boolean,
        /** Кто перевёл: «Groq», «OpenRouter» или «на телефоне». */
        val provider: String = PROVIDER_PHONE,
        /** Почему не получилось через облако, если пришлось переводить на телефоне. */
        val note: String? = null,
    )

    /** Итог перевода нескольких кусков. */
    data class Outcome(val provider: String, val note: String?)

    /** Память переводов нейросети: повторный перевод того же текста не тратит запрос. */
    private val cloudCache = object : LinkedHashMap<String, Pair<String, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > 400
    }

    private val languageId = LanguageIdentification.getClient(
        // Короткие фразы часто определяются неуверенно; тогда считаем текст английским,
        // чтобы не скачивать словарь случайного языка.
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.7f).build()
    )
    private val translators = HashMap<String, Translator>()
    private val modelManager = RemoteModelManager.getInstance()
    private val downloadConditions = DownloadConditions.Builder().build()

    private fun translator(source: String): Translator = translators.getOrPut(source) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TARGET)
                .build()
        )
    }

    suspend fun isModelDownloaded(language: String): Boolean =
        modelManager.isModelDownloaded(TranslateRemoteModel.Builder(language).build()).await()

    suspend fun baseModelsReady(): Boolean =
        isModelDownloaded(TranslateLanguage.ENGLISH) && isModelDownloaded(TARGET)

    suspend fun downloadBaseModels(onProgress: (ModelDownloadWatcher.Progress) -> Unit) {
        AppLog.i("Скачиваю словарь английский → русский")
        val started = System.currentTimeMillis()
        withDownloadProgress(onProgress) {
            translator(TranslateLanguage.ENGLISH).downloadModelIfNeeded(downloadConditions).await()
        }
        AppLog.i("Словарь английский → русский скачан за ${(System.currentTimeMillis() - started) / 1000} с")
    }

    /** Выполняет загрузку и, пока она идёт, сообщает прогресс. */
    private suspend fun <T> withDownloadProgress(
        onProgress: (ModelDownloadWatcher.Progress) -> Unit,
        block: suspend () -> T,
    ): T = coroutineScope {
        val watcher = launch { ModelDownloadWatcher(context).watch(onProgress) }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    /**
     * onStatus — короткий текст состояния; onDownload — прогресс, если по пути
     * пришлось скачивать словарь.
     */
    suspend fun translate(
        text: String,
        onStatus: (String) -> Unit,
        onDownload: (ModelDownloadWatcher.Progress) -> Unit = {},
        fromImage: Boolean = false,
    ): Result {
        val tag = runCatching { languageId.identifyLanguage(text).await() }
            .onFailure { AppLog.e("Не удалось определить язык", it) }
            .getOrDefault(UNDETERMINED)

        if (tag == TARGET || (tag == UNDETERMINED && looksCyrillic(text))) {
            return Result(text, text, TARGET, alreadyTarget = true)
        }
        // Пока переводим только с английского: словари других языков сами не докачиваем.
        val source = TranslateLanguage.ENGLISH
        if (tag != UNDETERMINED && tag != source) AppLog.i("Язык определён как «$tag», перевожу как английский")

        var note: String? = null
        if (cloudEnabled()) {
            cloudCache[text]?.let { (translated, by) ->
                return Result(text, translated, source, alreadyTarget = false, provider = by)
            }
            val problems = mutableListOf<String>()
            val cloud = runCloud(onStatus, problems) { provider, key, model ->
                CloudClient.translateOne(provider, key, model, text, fromImage)
            }
            if (cloud != null) {
                cloudCache[text] = cloud.value to cloud.label
                return Result(text, cloud.value, source, alreadyTarget = false, provider = cloud.label)
            }
            note = problems.distinct().joinToString("; ")
        }

        if (!isModelDownloaded(source) || !isModelDownloaded(TARGET)) {
            onStatus("Скачиваю словарь: ${languageName(source)} → русский (около 30 МБ, один раз)…")
            AppLog.i("Скачиваю словарь для «$source»")
        }
        val translator = translator(source)
        withDownloadProgress(onDownload) {
            translator.downloadModelIfNeeded(downloadConditions).await()
        }
        onStatus("Перевожу…")

        // По строкам: так сохраняются переносы и абзацы исходного текста.
        val translated = text.split('\n').map { line ->
            if (line.isBlank()) line else translator.translate(line).await()
        }.joinToString("\n")
        return Result(text, translated, source, alreadyTarget = false, note = note)
    }

    /**
     * Перевод многих кусков (весь экран, вся страница). Нейросети они уходят одним запросом
     * (длинное — несколькими по ~6000 символов). onEach сообщает готовый кусок сразу.
     */
    suspend fun translateMany(
        texts: List<String>,
        onStatus: (String) -> Unit,
        onEach: (Int, String) -> Unit,
        fromImage: Boolean = false,
    ): Outcome {
        val done = BooleanArray(texts.size)
        val cloud = cloudEnabled()
        val usedBy = linkedSetOf<String>()
        texts.forEachIndexed { i, text ->
            val cached = if (cloud) cloudCache[text] else null
            val ready = if (looksCyrillic(text)) text else cached?.first
            if (ready != null) {
                done[i] = true
                cached?.second?.let { usedBy.add(it) }
                onEach(i, ready)
            }
        }
        val problems = mutableListOf<String>()
        val pending = texts.indices.filter { !done[it] }
        // Куски пронумерованы: если нейросеть какие-то пропустила, досылаем только их (один раз).
        if (cloud) for (chunk in chunks(pending.map { it to texts[it] })) {
            var left: List<Pair<Int, String>> = chunk
            var attempt = 0
            while (left.isNotEmpty() && attempt < 2) {
                attempt++
                val sending = left
                val result = runCloud(onStatus, problems, "${sending.size} кусков одним запросом") { provider, key, model ->
                    CloudClient.translateMany(provider, key, model, sending.map { it.second }, fromImage)
                } ?: break
                val missing = mutableListOf<Pair<Int, String>>()
                sending.forEachIndexed { j, (index, original) ->
                    val translated = result.value[j]
                    if (translated == null) {
                        missing.add(index to original)
                    } else {
                        cloudCache[original] = translated to result.label
                        done[index] = true
                        onEach(index, translated)
                    }
                }
                if (sending.size > missing.size) usedBy.add(result.label)
                if (missing.isNotEmpty()) AppLog.i("${result.label} пропустил ${missing.size} из ${sending.size} кусков, дошлю")
                left = missing
            }
        }
        val rest = texts.indices.filter { !done[it] }
        rest.forEachIndexed { n, index ->
            onStatus("Перевожу на телефоне… ${n + 1} из ${rest.size}")
            onEach(index, translateEnglish(texts[index]))
        }
        if (rest.isNotEmpty()) usedBy.add(PROVIDER_PHONE)
        val note = problems.distinct().joinToString("; ").ifEmpty { null }.takeIf { rest.isNotEmpty() }
        return Outcome(usedBy.joinToString(" + ").ifEmpty { PROVIDER_PHONE }, note)
    }

    /**
     * «Весь текст»: абзацы страницы одним связным документом (через пустую строку) —
     * перевод получается связным, и кусков потерять нельзя. Длинное — порциями по абзацам.
     * Если нейросеть не ответила, абзацы переводятся на телефоне.
     */
    suspend fun translateDocument(
        paragraphs: List<String>,
        onStatus: (String) -> Unit,
        fromImage: Boolean,
    ): Pair<String, Outcome> {
        val cloud = cloudEnabled()
        val problems = mutableListOf<String>()
        val parts = mutableListOf<String>()
        val usedBy = linkedSetOf<String>()
        val groups = chunks(paragraphs.mapIndexed { i, p -> i to p }).map { group -> group.map { it.second } }
        for ((n, group) in groups.withIndex()) {
            val text = group.joinToString("\n\n")
            val cached = cloudCache[text]
            if (cached != null) {
                parts.add(cached.first)
                usedBy.add(cached.second)
                continue
            }
            var translated: String? = null
            if (cloud) {
                val what = "весь текст" + if (groups.size > 1) ", часть ${n + 1} из ${groups.size}" else ""
                runCloud(onStatus, problems, what) { provider, key, model ->
                    CloudClient.translateDocument(provider, key, model, text, fromImage)
                }?.let { result ->
                    translated = result.value
                    cloudCache[text] = result.value to result.label
                    usedBy.add(result.label)
                }
            }
            if (translated == null) {
                // Документом не вышло — переводим по абзацам (пронумерованными кусками нейросети,
                // а что не получится — на телефоне).
                val pieces = arrayOfNulls<String>(group.size)
                val outcome = translateMany(group, onStatus, { i, t -> pieces[i] = t }, fromImage)
                outcome.provider.split(" + ").forEach { usedBy.add(it) }
                outcome.note?.let { problems.add(it) }
                translated = pieces.joinToString("\n\n") { it.orEmpty() }
            }
            parts.add(translated.orEmpty())
        }
        val note = problems.distinct().joinToString("; ").ifEmpty { null }.takeIf { PROVIDER_PHONE in usedBy }
        return parts.joinToString("\n\n") to Outcome(usedBy.joinToString(" + ").ifEmpty { PROVIDER_PHONE }, note)
    }

    /** Проверка ключа: переводит короткую фразу, перебирая модели сервиса. Возвращает перевод и модель. */
    suspend fun checkCloud(provider: CloudProvider, key: String): Pair<String, String> {
        val problems = mutableListOf<String>()
        val result = tryModels(provider, key, onStatus = {}, problems = problems, what = null, limit = Int.MAX_VALUE) { p, k, m ->
            CloudClient.translateOne(p, k, m, "The weather is lovely today, let's go for a walk.")
        } ?: throw CloudClient.CloudException(problems.lastOrNull()?.substringAfter(": ") ?: "не удалось перевести")
        return result.value to result.model
    }

    private fun cloudEnabled(): Boolean =
        Prefs.provider(context) == Prefs.PROVIDER_CLOUD && CloudProvider.entries.any { keyOf(it) != null }

    private fun keyOf(provider: CloudProvider): String? = SecretStore.apiKey(context, provider)?.takeIf { it.isNotBlank() }

    /** Успешный ответ нейросети: что перевела, какой сервис и какая модель. */
    private class Cloud<T>(val value: T, val provider: CloudProvider, val model: String) {
        val label: String get() = "${provider.title} · ${shortModel(model)}"
    }

    /**
     * Порядок, о котором попросил пользователь:
     * 1) Groq — перебираем все его модели по очереди (в карточке видно, какая модель сейчас пробуется);
     * 2) если все модели Groq не ответили — OpenRouter и ещё один круг Groq параллельно,
     *    берём того, кто ответит первым, второго останавливаем;
     * 3) не вышло ни там, ни там — вернём null, и переведёт телефон.
     */
    private suspend fun <T> runCloud(
        onStatus: (String) -> Unit,
        problems: MutableList<String>,
        what: String? = null,
        call: suspend (CloudProvider, String, String) -> T,
    ): Cloud<T>? {
        val groqKey = keyOf(CloudProvider.GROQ)
        val routerKey = keyOf(CloudProvider.OPENROUTER)
        if (groqKey != null) {
            tryModels(CloudProvider.GROQ, groqKey, onStatus, problems, what, Int.MAX_VALUE, call)?.let { return it }
        }
        return coroutineScope {
            val racers = buildList {
                if (routerKey != null) {
                    add(async { tryModels(CloudProvider.OPENROUTER, routerKey, onStatus, problems, what, ROUTER_TRIES, call) })
                }
                if (groqKey != null) {
                    add(async {
                        delay(GROQ_RETRY_PAUSE_MS)
                        tryModels(CloudProvider.GROQ, groqKey, onStatus, problems, what, Int.MAX_VALUE, call)
                    })
                }
            }
            if (racers.isEmpty()) return@coroutineScope null
            if (routerKey != null && groqKey != null) AppLog.i("Все модели Groq не ответили — Groq и OpenRouter наперегонки")
            val waiting = racers.toMutableList()
            var winner: Cloud<T>? = null
            while (waiting.isNotEmpty() && winner == null) {
                val (done, value) = select<Pair<kotlinx.coroutines.Deferred<Cloud<T>?>, Cloud<T>?>> {
                    waiting.forEach { racer -> racer.onAwait { racer to it } }
                }
                waiting.remove(done)
                winner = value
            }
            waiting.forEach { it.cancel() }
            winner?.let { AppLog.i("Первым ответил ${it.label}") }
            winner
        }
    }

    /** Пробует модели сервиса по очереди, пока одна не ответит. Ошибки — в problems. */
    private suspend fun <T> tryModels(
        provider: CloudProvider,
        key: String,
        onStatus: (String) -> Unit,
        problems: MutableList<String>,
        what: String?,
        limit: Int,
        call: suspend (CloudProvider, String, String) -> T,
    ): Cloud<T>? {
        val models = try {
            orderedModels(provider, key)
        } catch (e: CloudClient.CloudException) {
            problems.add("${provider.title}: ${e.reason}")
            AppLog.i("${provider.title}: ${e.reason}")
            return null
        }
        for (model in models.take(limit)) {
            onStatus("Перевожу нейросетью: ${provider.title} · ${shortModel(model)}" + (what?.let { " ($it)" } ?: "") + "…")
            Prefs.addUsage(context, provider)
            try {
                val value = call(provider, key, model)
                Prefs.setAutoModel(context, provider, model)
                return Cloud(value, provider, model)
            } catch (e: CloudClient.CloudException) {
                AppLog.i("${provider.title} · $model: ${e.reason}")
                problems.add("${provider.title}: ${e.reason}")
                // Неверный ключ, нет сети, доступ запрещён — другие модели этого сервиса не помогут.
                if (!e.tryOtherModel) return null
            }
        }
        return null
    }

    /**
     * Модели сервиса в порядке попыток: выбранная вручную, потом та, что ответила в прошлый раз,
     * потом остальные — сильные семейства впереди.
     */
    private suspend fun orderedModels(provider: CloudProvider, key: String): List<String> {
        val list = modelsCache[provider] ?: try {
            CloudClient.models(provider, key)
        } catch (e: CloudClient.CloudException) {
            throw CloudClient.CloudException("не удалось получить список моделей: ${e.reason}")
        } catch (e: Exception) {
            throw CloudClient.CloudException("не удалось получить список моделей")
        }.also { if (it.isNotEmpty()) modelsCache[provider] = it }
        if (list.isEmpty()) throw CloudClient.CloudException("нет доступных моделей")
        val ranked = CloudClient.rank(provider, list).map { it.id }
        val manual = Prefs.model(context, provider)
        val last = Prefs.autoModel(context, provider).first
        return (listOf(manual, last).filter { it.isNotEmpty() && it in ranked } + ranked).distinct()
    }

    /** Списки моделей на время работы приложения (меняются редко). */
    private val modelsCache = HashMap<CloudProvider, List<CloudClient.Model>>()

    private fun chunks(items: List<Pair<Int, String>>): List<List<Pair<Int, String>>> {
        val out = mutableListOf<MutableList<Pair<Int, String>>>()
        var size = 0
        for (item in items) {
            if (out.isEmpty() || size + item.second.length > CHUNK_CHARS) {
                out.add(mutableListOf())
                size = 0
            }
            out.last().add(item)
            size += item.second.length
        }
        return out
    }

    /**
     * Быстрый перевод для всего экрана: без определения языка, только с английского.
     * Русский текст возвращается как есть.
     */
    suspend fun translateEnglish(text: String): String {
        if (looksCyrillic(text)) return text
        val translator = translator(TranslateLanguage.ENGLISH)
        translator.downloadModelIfNeeded(downloadConditions).await()
        return text.split('\n').map { line ->
            if (line.isBlank()) line else translator.translate(line).await()
        }.joinToString("\n")
    }

    fun looksCyrillic(text: String): Boolean {
        var cyrillic = 0
        var latin = 0
        for (ch in text) {
            when {
                Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC -> cyrillic++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }
        return cyrillic > latin
    }

    companion object {
        const val TARGET = TranslateLanguage.RUSSIAN
        private const val UNDETERMINED = "und"
        const val PROVIDER_PHONE = "на телефоне"
        /** Русский текст занимает много «токенов», поэтому порции поменьше — чтобы влезть в лимиты Groq. */
        private const val CHUNK_CHARS = 4000
        /** OpenRouter: бесплатных запросов мало, поэтому пробуем не больше 4 моделей за раз. */
        private const val ROUTER_TRIES = 4
        /** Перед вторым кругом Groq — короткая пауза: перегрузка часто проходит за секунду-другую. */
        private const val GROQ_RETRY_PAUSE_MS = 1500L

        /** Короткое имя модели для карточки: «openai/gpt-oss-120b:free» → «gpt-oss-120b». */
        fun shortModel(model: String): String = model.substringAfterLast('/').removeSuffix(":free")

        fun languageName(code: String): String =
            Locale(code).getDisplayLanguage(Locale("ru")).ifBlank { code }
    }
}
