package com.tongway.translator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.tongway.translator.R

/**
 * Сканирование картинки на весь экран. Пока идёт распознавание, сверху вниз
 * проезжает оранжевая полоса со светящейся сеткой. Потом каждый найденный абзац
 * обводится тонкой рамкой, а вверху подпись «Найдено абзацев: N».
 */
class ScanOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val startTime = SystemClock.uptimeMillis()
    private val location = IntArray(2)
    private val rect = RectF()

    private var scanning = true
    private var blocks: List<Rect> = emptyList()
    private var allBright = false
    private var label: String = ""

    private val wash = Paint().apply { color = accent; alpha = 18 }
    private val band = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 2.5f * density }
    private val lineGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; alpha = 90; strokeWidth = 8f * density }
    private val grid = Paint().apply { color = accent; strokeWidth = density }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
        strokeWidth = 1.5f * density
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE60B0B0B.toInt() }
    private val labelBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
        strokeWidth = density
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = ResourcesCompat.getFont(context, R.font.unbounded)
        textAlign = Paint.Align.CENTER
    }

    fun showScanning(text: String) {
        scanning = true
        label = text
        invalidate()
    }

    /** Распознавание закончено: показываем рамки всех найденных абзацев. */
    fun showBlocks(found: List<Rect>, text: String) {
        scanning = false
        blocks = found
        label = text
        invalidate()
    }

    /** Переводится вся страница — все рамки становятся яркими. */
    fun highlightAll(text: String) {
        allBright = true
        label = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        val w = width.toFloat()
        val h = height.toFloat()
        if (scanning) {
            drawSweep(canvas, w, h)
        } else {
            val corner = 5f * density
            frame.alpha = if (allBright) 230 else 140
            for (b in blocks) {
                rect.set(
                    (b.left - location[0]).toFloat() - 3 * density,
                    (b.top - location[1]).toFloat() - 3 * density,
                    (b.right - location[0]).toFloat() + 3 * density,
                    (b.bottom - location[1]).toFloat() + 3 * density,
                )
                canvas.drawRoundRect(rect, corner, corner, frame)
            }
        }
        drawLabel(canvas, w)
        if (scanning && isShown) postInvalidateOnAnimation()
    }

    private fun drawSweep(canvas: Canvas, w: Float, h: Float) {
        canvas.drawRect(0f, 0f, w, h, wash)
        val t = ((SystemClock.uptimeMillis() - startTime) % SWEEP_MS) / SWEEP_MS.toFloat()
        val y = t * (h + BAND_DP * density)
        val top = y - BAND_DP * density
        band.shader = LinearGradient(0f, top, 0f, y, 0x00EA5F0A, 0x66EA5F0A, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, w, y, band)
        // Сетка светится только внутри полосы, как у сканера.
        val cell = 24f * density
        var x = 0f
        while (x < w) {
            grid.alpha = 60
            canvas.drawLine(x, maxOf(0f, top), x, y, grid)
            x += cell
        }
        var gy = top - (top % cell)
        while (gy < y) {
            if (gy >= 0) {
                grid.alpha = (((gy - top) / (y - top)) * 110).toInt().coerceIn(0, 110)
                canvas.drawLine(0f, gy, w, gy, grid)
            }
            gy += cell
        }
        canvas.drawLine(0f, y, w, y, lineGlow)
        canvas.drawLine(0f, y, w, y, line)
    }

    private fun drawLabel(canvas: Canvas, w: Float) {
        if (label.isEmpty()) return
        val textWidth = labelText.measureText(label)
        val padX = 14f * density
        val heightPx = 30f * density
        val top = 44f * density - location[1].coerceAtMost(0)
        rect.set(w / 2 - textWidth / 2 - padX, top, w / 2 + textWidth / 2 + padX, top + heightPx)
        canvas.drawRoundRect(rect, heightPx / 2, heightPx / 2, labelBg)
        canvas.drawRoundRect(rect, heightPx / 2, heightPx / 2, labelBorder)
        val baseline = rect.centerY() - (labelText.descent() + labelText.ascent()) / 2
        canvas.drawText(label, w / 2, baseline, labelText)
    }

    private companion object {
        const val SWEEP_MS = 1100L
        const val BAND_DP = 120f
    }
}
