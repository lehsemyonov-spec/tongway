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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
        val chain = chain()
        if (chain.isNotEmpty()) {
            cloudCache[text]?.let { (translated, by) ->
                return Result(text, translated, source, alreadyTarget = false, provider = by)
            }
            val problems = mutableListOf<String>()
            for ((provider, key) in chain) {
                try {
                    onStatus("Перевожу нейросетью (${provider.title})…")
                    val translated = withModel(provider) { model -> CloudClient.translateOne(provider, key, model, text) }
                    cloudCache[text] = translated to provider.title
                    return Result(text, translated, source, alreadyTarget = false, provider = provider.title)
                } catch (e: CloudClient.CloudException) {
                    AppLog.i("${provider.title} не перевёл: ${e.reason}")
                    problems.add("${provider.title}: ${e.reason}")
                }
            }
            note = problems.joinToString("; ")
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
    ): Outcome {
        val done = BooleanArray(texts.size)
        val chain = chain()
        val usedBy = linkedSetOf<String>()
        texts.forEachIndexed { i, text ->
            val cached = if (chain.isNotEmpty()) cloudCache[text] else null
            val ready = if (looksCyrillic(text)) text else cached?.first
            if (ready != null) {
                done[i] = true
                cached?.second?.let { usedBy.add(it) }
                onEach(i, ready)
            }
        }
        val problems = mutableListOf<String>()
        val pending = texts.indices.filter { !done[it] }
        // Каждая порция идёт в первый сервис цепочки, который ответит.
        for (chunk in chunks(pending.map { it to texts[it] })) {
            for ((provider, key) in chain) {
                try {
                    onStatus("Перевожу нейросетью (${provider.title}): ${chunk.size} кусков одним запросом…")
                    val out = withModel(provider) { model ->
                        CloudClient.translateMany(provider, key, model, chunk.map { it.second })
                    }
                    chunk.forEachIndexed { j, (index, original) ->
                        cloudCache[original] = out[j] to provider.title
                        done[index] = true
                        onEach(index, out[j])
                    }
                    usedBy.add(provider.title)
                    break
                } catch (e: CloudClient.CloudException) {
                    AppLog.i("${provider.title} не перевёл ${chunk.size} кусков: ${e.reason}")
                    problems.add("${provider.title}: ${e.reason}")
                }
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

    /** Проверка ключа: переводит короткую фразу. Возвращает перевод и модель. */
    suspend fun checkCloud(provider: CloudProvider, key: String): Pair<String, String> {
        var used = ""
        val text = withModel(provider, key) { model ->
            used = model
            CloudClient.translateOne(provider, key, model, "The weather is lovely today, let's go for a walk.")
        }
        return text to used
    }

    /** Сервисы с ключами в порядке цепочки; пусто — переводим на телефоне. */
    private fun chain(): List<Pair<CloudProvider, String>> {
        if (Prefs.provider(context) != Prefs.PROVIDER_CLOUD) return emptyList()
        return CloudProvider.CHAIN.mapNotNull { provider ->
            SecretStore.apiKey(context, provider)?.takeIf { it.isNotBlank() }?.let { provider to it }
        }
    }

    /**
     * Выполняет запрос с выбранной моделью. Если модель выбирается автоматически и она пропала
     * из бесплатных, берём другую и пробуем ещё раз.
     */
    private suspend fun <T> withModel(
        provider: CloudProvider,
        key: String? = SecretStore.apiKey(context, provider),
        block: suspend (String) -> T,
    ): T {
        val manual = Prefs.model(context, provider)
        if (manual.isNotEmpty()) {
            Prefs.addUsage(context, provider)
            return block(manual)
        }
        // Автовыбор: если модель пропала, перегружена или упёрлась в лимит — пробуем ещё до двух других.
        val tried = mutableSetOf<String>()
        var model = autoModel(provider, key, refresh = false, exclude = tried)
        while (true) {
            Prefs.addUsage(context, provider)
            try {
                val result = block(model)
                Prefs.setAutoModel(context, provider, model)
                return result
            } catch (e: CloudClient.CloudException) {
                tried.add(model)
                if (!e.tryOtherModel || tried.size >= MAX_MODEL_TRIES) throw e
                AppLog.i("${provider.title}, модель $model: ${e.reason}, пробую другую")
                model = autoModel(provider, key, refresh = true, exclude = tried)
            }
        }
    }

    /** Бесплатная модель для перевода; список обновляется раз в сутки. */
    private suspend fun autoModel(provider: CloudProvider, key: String?, refresh: Boolean, exclude: Set<String>): String {
        val (cached, time) = Prefs.autoModel(context, provider)
        if (!refresh && cached.isNotEmpty() && cached !in exclude && System.currentTimeMillis() - time < DAY_MS) {
            return cached
        }
        val models = (modelsCache[provider] ?: try {
            CloudClient.models(provider, key)
        } catch (e: CloudClient.CloudException) {
            throw CloudClient.CloudException("не удалось получить список моделей: ${e.reason}")
        } catch (e: Exception) {
            throw CloudClient.CloudException("не удалось получить список моделей")
        }.also { modelsCache[provider] = it })
            .filter { it.id !in exclude }
        val pick = CloudClient.pickDefault(provider, models)
            ?: throw CloudClient.CloudException("нет доступных моделей")
        AppLog.i("Автовыбор модели ${provider.title}: ${pick.id} (подходящих моделей: ${models.size})")
        return pick.id
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
        private const val MAX_MODEL_TRIES = 3
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun languageName(code: String): String =
            Locale(code).getDisplayLanguage(Locale("ru")).ifBlank { code }
    }
}
