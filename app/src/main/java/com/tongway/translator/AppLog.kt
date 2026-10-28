package com.tongway.translator

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал событий и ошибок: хранится в файле, чтобы пережить перезапуск и сбой. */
object AppLog {
    private const val TAG = "Tongway"
    private const val MAX_ENTRIES = 400

    private val entries = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
    private var file: File? = null

    fun init(context: Context) {
        val logFile = File(context.filesDir, "log.txt")
        file = logFile
        synchronized(entries) {
            entries.clear()
            if (logFile.exists()) {
                runCatching { logFile.readText().split(SEPARATOR) }
                    .getOrDefault(emptyList())
                    .filter { it.isNotBlank() }
                    .takeLast(MAX_ENTRIES)
                    .forEach { entries.addLast(it) }
            }
        }
    }

    fun i(message: String) = add('I', message, null)

    fun e(message: String, error: Throwable? = null) = add('E', message, error)

    fun text(): String = synchronized(entries) { entries.joinToString("\n") }

    /** Текст для отправки: сведения о телефоне и сам журнал. */
    fun report(): String = buildString {
        append("Tongway ").append(BuildConfig.VERSION_NAME).append('\n')
        append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n")
        append(text())
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            save()
        }
    }

    private fun add(level: Char, message: String, error: Throwable?) {
        if (level == 'E') Log.e(TAG, message, error) else Log.i(TAG, message)
        synchronized(entries) {
            val entry = buildString {
                append(timeFormat.format(Date())).append(' ').append(level).append(' ').append(message)
                if (error != null) append('\n').append(Log.getStackTraceString(error).trimEnd())
            }
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            save()
        }
    }

    private fun save() {
        val target = file ?: return
        runCatching { target.writeText(entries.joinToString(SEPARATOR)) }
            .onFailure { Log.e(TAG, "Не удалось записать журнал", it) }
    }

    private const val SEPARATOR = "\n\u001E\n"
}
