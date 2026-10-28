package com.tongway.translator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.tongway.translator.R

/**
 * Полоса загрузки в стиле приборной панели: сегменты, оранжевое свечение.
 * progress = null — сколько осталось неизвестно, по полосе бегает огонёк.
 */
class TechProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var progress: Float? = null
        set(value) {
            field = value?.coerceIn(0f, 1f)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val accentLight = ContextCompat.getColor(context, R.color.accent_light)

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF444444.toInt()
        strokeWidth = density
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; alpha = 70 }
    private val gap = Paint().apply { color = 0xFF000000.toInt() }
    private val rect = RectF()
    private val startTime = SystemClock.uptimeMillis()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize((14 * density).toInt(), heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, track)

        val inset = 3f * density
        val inner = w - inset * 2
        val value = progress
        val (from, to) = if (value != null) {
            0f to inner * value
        } else {
            // Огонёк ездит туда-обратно, пока размер загрузки неизвестен.
            val t = ((SystemClock.uptimeMillis() - startTime) % 1600L) / 1600f
            val pos = if (t < 0.5f) t * 2 else (1 - t) * 2
            val size = inner * 0.3f
            val start = (inner - size) * pos
            start to start + size
        }
        if (to - from > 1f) {
            rect.set(inset + from, inset, inset + to, h - inset)
            bar.shader = LinearGradient(rect.left, 0f, rect.right, 0f, accent, accentLight, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect.left - 2 * density, 0f, rect.right + 2 * density, h, radius, radius, glow)
            canvas.drawRoundRect(rect, radius, radius, bar)
            // Тёмные прорези делают полосу похожей на шкалу прибора.
            var x = inset + 6 * density
            while (x < rect.right) {
                if (x > rect.left) canvas.drawRect(x, inset, x + density, h - inset, gap)
                x += 6 * density
            }
        }
        rect.set(0f, 0f, w, h)
        rect.inset(density / 2, density / 2)
        canvas.drawRoundRect(rect, radius, radius, border)
        if (value == null && isShown) postInvalidateOnAnimation()
    }
}
