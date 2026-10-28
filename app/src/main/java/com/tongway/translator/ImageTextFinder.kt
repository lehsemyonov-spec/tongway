package com.tongway.translator

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.math.hypot

/**
 * Текст на картинках: снимок экрана через службу спец. возможностей (Android 11+)
 * и распознавание напечатанного латинского текста ML Kit прямо на телефоне.
 */
object ImageTextFinder {

    /** Кусок текста, найденный на снимке, в координатах экрана; lineHeight — средняя высота строки. */
    data class Block(val text: String, val bounds: Rect, val lineHeight: Int = 0)

    /** Результат снимка: картинка или понятная причина, почему не вышло. */
    sealed class Capture {
        class Ok(val bitmap: Bitmap) : Capture()
        class Failed(val reason: String) : Capture()
    }

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun capture(service: AccessibilityService): Capture {
        if (!isSupported) return Capture.Failed("распознавание с картинок работает на Android 11 и новее")
        var result = takeScreenshot(service)
        // Система разрешает снимок не чаще ~3 раз в секунду — один раз пробуем ещё.
        if (result is Capture.Failed && result.reason == TOO_OFTEN) {
            delay(400)
            result = takeScreenshot(service)
        }
        return result
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(service: AccessibilityService): Capture =
        suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        buffer.close()
                        cont.resume(if (bitmap != null) Capture.Ok(bitmap) else Capture.Failed("не удалось прочитать снимок"))
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(Capture.Failed(errorText(errorCode)))
                    }
                },
            )
        }

    /** Распознаёт текст и собирает его в абзацы по порядку чтения. */
    suspend fun recognize(bitmap: Bitmap): List<Block> {
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val raw = text.textBlocks.mapNotNull { block ->
            val bounds = block.boundingBox ?: return@mapNotNull null
            val joined = joinLines(block.lines)
            val heights = block.lines.mapNotNull { it.boundingBox?.height() }
            val lineHeight = if (heights.isEmpty()) bounds.height() else heights.sum() / heights.size
            if (joined.length < 2 || joined.none { it.isLetter() }) null else Block(joined, bounds, lineHeight)
        }
        return toParagraphs(dropEdgeScraps(raw, bitmap.width), bitmap.width)
    }

    /**
     * Обрывки у левого или правого края кадра (например, соседняя страница книги на фото):
     * короткие и узкие куски, прижатые к краю, отбрасываем.
     */
    private fun dropEdgeScraps(blocks: List<Block>, width: Int): List<Block> {
        val edge = (width * 0.03f).toInt()
        return blocks.filterNot { b ->
            val atEdge = b.bounds.left <= edge || b.bounds.right >= width - edge
            atEdge && (b.text.length < 25 || b.bounds.width() < width * 0.15f)
        }
    }

    /**
     * Распознавание иногда режет абзац на куски. Склеиваем соседние куски, если они стоят
     * в одной колонке, между ними почти нет зазора и предыдущий не кончается точкой.
     */
    private fun toParagraphs(blocks: List<Block>, width: Int): List<Block> {
        val sorted = blocks.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val out = mutableListOf<Block>()
        for (block in sorted) {
            val last = out.lastOrNull()
            if (last != null && belongTogether(last, block, width)) {
                val bounds = Rect(last.bounds).apply { union(block.bounds) }
                val glue = if (last.text.endsWith("-")) "" else " "
                val text = last.text.removeSuffix("-") + glue + block.text
                out[out.size - 1] = Block(text, bounds, (last.lineHeight + block.lineHeight) / 2)
            } else {
                out.add(block)
            }
        }
        return out
    }

    private fun belongTogether(upper: Block, lower: Block, width: Int): Boolean {
        val line = maxOf(upper.lineHeight, lower.lineHeight, 1)
        val gap = lower.bounds.top - upper.bounds.bottom
        if (gap > line * 0.9f || gap < -line) return false
        val overlap = minOf(upper.bounds.right, lower.bounds.right) - maxOf(upper.bounds.left, lower.bounds.left)
        val narrower = minOf(upper.bounds.width(), lower.bounds.width())
        if (narrower <= 0 || overlap < narrower * 0.6f) return false
        if (kotlin.math.abs(upper.bounds.left - lower.bounds.left) > width * 0.08f &&
            kotlin.math.abs(upper.bounds.right - lower.bounds.right) > width * 0.08f
        ) return false
        val end = upper.text.trimEnd().lastOrNull() ?: return false
        return end !in ".!?:;\"»”"
    }

    /** Абзац под прицелом; если прицел чуть промахнулся — ближайший в пределах slop пикселей. */
    fun blockAt(blocks: List<Block>, x: Int, y: Int, slop: Int): Block? {
        blocks.firstOrNull { it.bounds.contains(x, y) }?.let { return it }
        return blocks
            .map { it to distance(it.bounds, x, y) }
            .filter { it.second <= slop }
            .minByOrNull { it.second }
            ?.first
    }

    /** Строки абзаца в одну фразу; перенос со знаком «-» склеивается без пробела. */
    private fun joinLines(lines: List<Text.Line>): String {
        val out = StringBuilder()
        for (line in lines) {
            val t = line.text.trim()
            if (t.isEmpty()) continue
            if (out.isNotEmpty()) {
                if (out.endsWith("-")) out.setLength(out.length - 1) else out.append(' ')
            }
            out.append(t)
        }
        return out.toString()
    }

    private fun distance(r: Rect, x: Int, y: Int): Double {
        val dx = maxOf(r.left - x, 0, x - r.right)
        val dy = maxOf(r.top - y, 0, y - r.bottom)
        return hypot(dx.toDouble(), dy.toDouble())
    }

    private const val TOO_OFTEN = "слишком частые снимки"

    private fun errorText(code: Int) = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> TOO_OFTEN
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "нет права на снимок экрана — выключите и снова включите Tongway в спец. возможностях"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "это приложение запрещает снимки экрана"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "неизвестный экран"
        else -> "ошибка снимка экрана (код $code)"
    }
}
