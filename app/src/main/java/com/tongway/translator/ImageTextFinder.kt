package com.tongway.translator

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
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

    /**
     * Распознаёт текст и собирает его в абзацы по порядку чтения (`PageLayout`: выравнивание наклона,
     * отсев мусора, колонки, абзацы по отступам).
     * area — только эта часть снимка (полоса вокруг прицела), координаты экрана; null — весь экран.
     * exclude — зоны, где текст не с картинки: системные панели, надписи и кнопки приложений.
     */
    suspend fun recognize(bitmap: Bitmap, area: Rect? = null, exclude: List<Rect> = emptyList()): List<Block> {
        val region = area?.let { Rect(it).apply { intersect(0, 0, bitmap.width, bitmap.height) } }
            ?.takeIf { !it.isEmpty }
            ?: Rect(0, 0, bitmap.width, bitmap.height)
        val crop = if (region.width() == bitmap.width && region.height() == bitmap.height) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
        }
        try {
            var lines = recognizeLines(crop, 1f, region)
            // Мелкие буквы распознаются с ошибками — увеличиваем снимок вдвое и пробуем ещё раз.
            val heights = lines.map { lineHeight(it) }.sorted()
            val median = if (heights.isEmpty()) 0f else heights[heights.size / 2]
            if (heights.isNotEmpty() && median < SMALL_TEXT_PX && crop.width * 2 <= MAX_UPSCALED_SIDE &&
                crop.height * 2 <= MAX_UPSCALED_SIDE
            ) {
                val upscaled = runCatching { Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true) }
                    .getOrNull()
                if (upscaled != null) {
                    try {
                        val better = recognizeLines(upscaled, 2f, region)
                        val letters = { list: List<PageLayout.Line> -> list.sumOf { l -> l.text.count { it.isLetter() } } }
                        AppLog.i("Мелкий текст (строка ${median.toInt()} px): увеличил вдвое, букв ${letters(lines)} → ${letters(better)}")
                        if (letters(better) >= letters(lines)) lines = better
                    } finally {
                        upscaled.recycle()
                    }
                }
            }
            val boxes = exclude.map { PageLayout.Box(it.left, it.top, it.right, it.bottom) }
            val result = PageLayout.build(lines, bitmap.width, boxes)
            AppLog.i(
                "Сборка страницы: строк ${lines.size}, отброшено ${result.dropped}, " +
                    "наклон ${"%.1f".format(result.angle)}°, абзацев ${result.paragraphs.size}"
            )
            return result.paragraphs.map {
                Block(it.text, Rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom), it.lineHeight)
            }
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
    }

    /**
     * Отдельные слова со снимка для «пальцевого перевода», в координатах экрана.
     * area — часть снимка вокруг линии пальца (так быстрее). Мелкие буквы — увеличение вдвое.
     */
    suspend fun recognizeWords(bitmap: Bitmap, area: Rect): List<FingerSelect.Word> {
        val region = Rect(area).apply { intersect(0, 0, bitmap.width, bitmap.height) }
        if (region.isEmpty) return emptyList()
        val crop = Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
        try {
            var words = wordsOf(crop, 1f, region)
            val heights = words.map { w -> w.corners[3].y - w.corners[0].y }.sorted()
            val median = if (heights.isEmpty()) 0f else heights[heights.size / 2]
            if (heights.isNotEmpty() && median < SMALL_TEXT_PX &&
                crop.width * 2 <= MAX_UPSCALED_SIDE && crop.height * 2 <= MAX_UPSCALED_SIDE
            ) {
                runCatching { Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true) }.getOrNull()?.let { big ->
                    try {
                        val better = wordsOf(big, 2f, region)
                        if (better.sumOf { it.text.length } >= words.sumOf { it.text.length }) words = better
                    } finally {
                        big.recycle()
                    }
                }
            }
            return words
        } finally {
            crop.recycle()
        }
    }

    private suspend fun wordsOf(image: Bitmap, scale: Float, region: Rect): List<FingerSelect.Word> {
        val text = recognizer.process(InputImage.fromBitmap(image, 0)).await()
        val out = mutableListOf<FingerSelect.Word>()
        var lineIndex = 0
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val corners = element.cornerPoints ?: continue
                    if (corners.size < 4) continue
                    out.add(
                        FingerSelect.Word(
                            element.text,
                            corners.map { PageLayout.Pt(it.x / scale + region.left, it.y / scale + region.top) },
                            lineIndex,
                        )
                    )
                }
                lineIndex++
            }
        }
        return out
    }

    /** Строки ML Kit в координатах экрана (с учётом обрезки и увеличения). */
    private suspend fun recognizeLines(image: Bitmap, scale: Float, region: Rect): List<PageLayout.Line> {
        val text = recognizer.process(InputImage.fromBitmap(image, 0)).await()
        val out = mutableListOf<PageLayout.Line>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val corners = line.cornerPoints ?: continue
                if (corners.size < 4) continue
                out.add(
                    PageLayout.Line(
                        line.text,
                        corners.map { PageLayout.Pt(it.x / scale + region.left, it.y / scale + region.top) },
                        line.confidence,
                    )
                )
            }
        }
        return out
    }

    private fun lineHeight(line: PageLayout.Line): Float {
        val a = line.corners[0]
        val d = line.corners[3]
        return hypot((d.x - a.x).toDouble(), (d.y - a.y).toDouble()).toFloat()
    }

    /** Строки ниже этой высоты (в пикселях снимка) считаем мелкими. */
    private const val SMALL_TEXT_PX = 26f
    private const val MAX_UPSCALED_SIDE = 4096

    /** Абзац под прицелом; если прицел чуть промахнулся — ближайший в пределах slop пикселей. */
    fun blockAt(blocks: List<Block>, x: Int, y: Int, slop: Int): Block? {
        blocks.firstOrNull { it.bounds.contains(x, y) }?.let { return it }
        return blocks
            .map { it to distance(it.bounds, x, y) }
            .filter { it.second <= slop }
            .minByOrNull { it.second }
            ?.first
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
