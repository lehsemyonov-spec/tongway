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
 * Перевод: нейросетью через OpenRouter (если выбрано и есть ключ) или прямо на телефоне
 * через Google ML Kit. Если облако не ответило, всегда переводим на телефоне и пишем почему.
 * Вызывать с главного потока: переводчики и память переводов хранятся без блокировок.
 */
class TranslationEngine(private val context: Context) {

    data class Result(
        val original: String,
        val translated: String,
        val sourceLanguage: String,
        val alreadyTarget: Boolean,
        /** Кто перевёл: «OpenRouter» или «на телефоне». */
        val provider: String = PROVIDER_PHONE,
        /** Почему не получилось через облако, если пришлось переводить на телефоне. */
        val note: String? = null,
    )

    /** Итог перевода нескольких кусков. */
    data class Outcome(val provider: String, val note: String?)

    /** Память переводов нейросети: повторный перевод того же текста не тратит запрос. */
    private val cloudCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 400
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
        if (cloudReady()) {
            cloudCache[text]?.let { return Result(text, it, source, alreadyTarget = false, provider = PROVIDER_CLOUD) }
            try {
                onStatus("Перевожу нейросетью…")
                val key = SecretStore.apiKey(context)!!
                val translated = withModel { model -> OpenRouterClient.translateOne(key, model, text) }
                cloudCache[text] = translated
                return Result(text, translated, source, alreadyTarget = false, provider = PROVIDER_CLOUD)
            } catch (e: OpenRouterClient.CloudException) {
                AppLog.i("OpenRouter не перевёл: ${e.reason}, перевожу на телефоне")
                note = "OpenRouter: ${e.reason}"
            }
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
        texts.forEachIndexed { i, text ->
            val ready = if (looksCyrillic(text)) text else if (cloudReady()) cloudCache[text] else null
            if (ready != null) {
                done[i] = true
                onEach(i, ready)
            }
        }
        var note: String? = null
        var usedCloud = false
        val pending = texts.indices.filter { !done[it] }
        if (pending.isNotEmpty() && cloudReady()) {
            try {
                val key = SecretStore.apiKey(context)!!
                for (chunk in chunks(pending.map { it to texts[it] })) {
                    onStatus("Перевожу нейросетью: ${chunk.size} кусков одним запросом…")
                    val out = withModel { model -> OpenRouterClient.translateMany(key, model, chunk.map { it.second }) }
                    chunk.forEachIndexed { j, (index, original) ->
                        cloudCache[original] = out[j]
                        done[index] = true
                        onEach(index, out[j])
                    }
                    usedCloud = true
                }
            } catch (e: OpenRouterClient.CloudException) {
                AppLog.i("OpenRouter не перевёл ${pending.size} кусков: ${e.reason}, перевожу на телефоне")
                note = "OpenRouter: ${e.reason}"
            }
        }
        val rest = texts.indices.filter { !done[it] }
        rest.forEachIndexed { n, index ->
            onStatus("Перевожу на телефоне… ${n + 1} из ${rest.size}")
            onEach(index, translateEnglish(texts[index]))
        }
        val provider = if (usedCloud && rest.isEmpty()) PROVIDER_CLOUD else PROVIDER_PHONE
        return Outcome(provider, note)
    }

    /** Проверка ключа: переводит короткую фразу. Возвращает перевод и модель. */
    suspend fun checkCloud(key: String): Pair<String, String> {
        var used = ""
        val text = withModel { model ->
            used = model
            OpenRouterClient.translateOne(key, model, "The weather is lovely today, let's go for a walk.")
        }
        return text to used
    }

    private fun cloudReady(): Boolean =
        Prefs.provider(context) == Prefs.PROVIDER_OPENROUTER && !SecretStore.apiKey(context).isNullOrBlank()

    /**
     * Выполняет запрос с выбранной моделью. Если модель выбирается автоматически и она пропала
     * из бесплатных, берём другую и пробуем ещё раз.
     */
    private suspend fun <T> withModel(block: suspend (String) -> T): T {
        val manual = Prefs.model(context)
        if (manual.isNotEmpty()) {
            Prefs.addUsage(context)
            return block(manual)
        }
        // Автовыбор: если модель пропала или перегружена, пробуем ещё до двух других.
        val tried = mutableSetOf<String>()
        var model = autoModel(refresh = false, exclude = tried)
        while (true) {
            Prefs.addUsage(context)
            try {
                return block(model)
            } catch (e: OpenRouterClient.CloudException) {
                tried.add(model)
                if (!e.tryOtherModel || tried.size >= MAX_MODEL_TRIES) throw e
                AppLog.i("Модель $model: ${e.reason}, пробую другую")
                model = autoModel(refresh = true, exclude = tried)
            }
        }
    }

    /** Бесплатная модель для перевода; список обновляется раз в сутки. */
    private suspend fun autoModel(refresh: Boolean, exclude: Set<String>): String {
        val (cached, time) = Prefs.autoModel(context)
        if (!refresh && cached.isNotEmpty() && System.currentTimeMillis() - time < DAY_MS) return cached
        val models = (freeModelsCache ?: runCatching { OpenRouterClient.freeModels() }
            .getOrElse { throw OpenRouterClient.CloudException("не удалось получить список моделей") }
            .also { freeModelsCache = it })
            .filter { it.id !in exclude }
        val pick = OpenRouterClient.pickDefault(models)
            ?: throw OpenRouterClient.CloudException("нет доступных бесплатных моделей")
        AppLog.i("Автовыбор модели OpenRouter: ${pick.id} (бесплатных моделей: ${models.size})")
        Prefs.setAutoModel(context, pick.id)
        return pick.id
    }

    /** Список бесплатных моделей на время работы приложения (меняется редко). */
    private var freeModelsCache: List<OpenRouterClient.Model>? = null

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
        const val PROVIDER_CLOUD = "OpenRouter"
        const val PROVIDER_PHONE = "на телефоне"
        private const val CHUNK_CHARS = 6000
        private const val MAX_MODEL_TRIES = 3
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun languageName(code: String): String =
            Locale(code).getDisplayLanguage(Locale("ru")).ifBlank { code }
    }
}
