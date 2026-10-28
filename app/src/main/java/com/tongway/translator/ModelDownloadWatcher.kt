package com.tongway.translator

import android.app.DownloadManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Следит за скачиванием словаря ML Kit. Сам ML Kit проценты не сообщает, поэтому:
 * 1) смотрим загрузки приложения в системном «Диспетчере загрузок» — ML Kit качает через него;
 * 2) если там пусто, смотрим, насколько выросли папки приложения.
 */
class ModelDownloadWatcher(private val context: Context) {

    data class Progress(
        val downloadedBytes: Long,
        /** Полный размер; если неизвестен — примерный. */
        val totalBytes: Long,
        val exact: Boolean,
        /** Почему загрузка стоит (нет сети и т. п.), или null. */
        val waitingReason: String?,
    ) {
        val fraction: Float? get() = if (downloadedBytes > 0 && totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            null
        }
    }

    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    /** Сообщает прогресс, пока корутину не отменят. */
    suspend fun watch(onProgress: (Progress) -> Unit) {
        val startSize = withContext(Dispatchers.IO) { folderSize() }
        var lastReason: String? = null
        while (true) {
            val progress = withContext(Dispatchers.IO) { fromDownloadManager() ?: fromFolders(startSize) }
            if (progress.waitingReason != lastReason) {
                lastReason = progress.waitingReason
                if (lastReason != null) AppLog.i("Скачивание словаря стоит: $lastReason")
            }
            onProgress(progress)
            delay(POLL_MS)
        }
    }

    private fun fromDownloadManager(): Progress? {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED
        )
        val cursor = runCatching { downloadManager?.query(query) }.getOrNull() ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val soFarColumn = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalColumn = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val statusColumn = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val reasonColumn = it.getColumnIndex(DownloadManager.COLUMN_REASON)
            var soFar = 0L
            var total = 0L
            var allTotalsKnown = true
            var reason: String? = null
            do {
                soFar += it.getLong(soFarColumn).coerceAtLeast(0)
                val size = it.getLong(totalColumn)
                if (size > 0) total += size else allTotalsKnown = false
                if (it.getInt(statusColumn) == DownloadManager.STATUS_PAUSED) {
                    reason = pauseReason(it.getInt(reasonColumn))
                }
            } while (it.moveToNext())
            val exact = allTotalsKnown && total > 0
            return Progress(soFar, if (exact) total else EXPECTED_BYTES, exact, reason)
        }
    }

    private fun fromFolders(startSize: Long): Progress {
        val grown = (folderSize() - startSize).coerceAtLeast(0)
        return Progress(grown, maxOf(EXPECTED_BYTES, grown), exact = false, waitingReason = null)
    }

    private fun folderSize(): Long {
        val roots = listOfNotNull(
            context.filesDir,
            context.noBackupFilesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
        )
        return roots.sumOf { sizeOf(it) }
    }

    private fun sizeOf(file: File): Long =
        if (file.isDirectory) file.listFiles()?.sumOf { sizeOf(it) } ?: 0L else file.length()

    private fun pauseReason(code: Int) = when (code) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "ждём подключения к интернету"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "ждём Wi-Fi"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "ошибка сети, сейчас повторим"
        else -> "пауза (код $code)"
    }

    companion object {
        private const val POLL_MS = 400L
        /** Примерный размер словаря, если точный неизвестен. */
        const val EXPECTED_BYTES = 30L * 1024 * 1024

        fun formatMb(bytes: Long): String =
            String.format(java.util.Locale("ru"), "%.1f МБ", bytes / 1024.0 / 1024.0)
    }
}
