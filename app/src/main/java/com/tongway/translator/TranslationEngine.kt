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
 * Перевод прямо на телефоне через Google ML Kit.
 * Вызывать с главного потока: переводчики хранятся без блокировок.
 */
class TranslationEngine(private val context: Context) {

    data class Result(
        val original: String,
        val translated: String,
        val sourceLanguage: String,
        val alreadyTarget: Boolean,
    )

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
        return Result(text, translated, source, alreadyTarget = false)
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

        fun languageName(code: String): String =
            Locale(code).getDisplayLanguage(Locale("ru")).ifBlank { code }
    }
}
