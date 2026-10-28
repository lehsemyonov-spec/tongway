package com.tongway.translator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.tongway.translator.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Механическая ДНК: две стальные нити с сегментами, перемычки-поршни с болтами
 * и светящиеся оранжевые узлы. Медленно вращается, пока видна на экране.
 */
class MechanicalDnaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var horizontal = false
    /** Скорость вращения, радиан в секунду. */
    var speed = 0.9f
    var animating = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val accentLight = ContextCompat.getColor(context, R.color.accent_light)
    private val accentDark = ContextCompat.getColor(context, R.color.accent_dark)
    private val steelLight = ContextCompat.getColor(context, R.color.steel_light)
    private val steelDark = ContextCompat.getColor(context, R.color.steel_dark)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strandPath1 = Path()
    private val strandPath2 = Path()
    private val startTime = SystemClock.uptimeMillis()
    private var dash: DashPathEffect? = null
    private var dashUnit = 0f

    init {
        context.obtainStyledAttributes(attrs, R.styleable.MechanicalDnaView).apply {
            horizontal = getBoolean(R.styleable.MechanicalDnaView_dnaHorizontal, false)
            recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val phase = (SystemClock.uptimeMillis() - startTime) / 1000f * speed
        if (horizontal) {
            canvas.save()
            canvas.translate(0f, height.toFloat())
            canvas.rotate(-90f)
            drawHelix(canvas, height.toFloat(), width.toFloat(), phase)
            canvas.restore()
        } else {
            drawHelix(canvas, width.toFloat(), height.toFloat(), phase)
        }
        if (animating && isShown) postInvalidateOnAnimation()
    }

    /** Рисует вертикальную спираль в прямоугольнике w × h. */
    private fun drawHelix(canvas: Canvas, w: Float, h: Float, phase: Float) {
        val unit = (w / 30f).coerceIn(0.8f * density, 4.5f * density)
        val nodeRadius = unit * 1.3f
        val amp = w * 0.5f - nodeRadius * 2.6f
        val cx = w / 2f
        val spacing = max(w * 0.15f, 8f * density)
        val pad = nodeRadius * 2.5f
        val count = max(3, ((h - pad * 2) / spacing).toInt())
        val step = (h - pad * 2) / count

        val ys = FloatArray(count + 1)
        val x1 = FloatArray(count + 1)
        val x2 = FloatArray(count + 1)
        val depth = FloatArray(count + 1)
        for (i in 0..count) {
            val angle = phase + i * 0.55f
            ys[i] = pad + i * step
            x1[i] = cx + sin(angle) * amp
            x2[i] = cx - sin(angle) * amp
            depth[i] = cos(angle)
        }

        // Задние узлы — тусклые, чтобы спираль выглядела объёмной.
        for (i in 0..count) {
            if (depth[i] <= 0) drawNode(canvas, x1[i], ys[i], nodeRadius, front = false)
            if (-depth[i] <= 0) drawNode(canvas, x2[i], ys[i], nodeRadius, front = false)
        }

        // Перемычки: стальной поршень, оранжевая муфта в центре и два болта.
        for (i in 0..count) {
            val left = min(x1[i], x2[i])
            val right = max(x1[i], x2[i])
            if (right - left < nodeRadius) continue
            stroke.pathEffect = null
            stroke.color = steelDark
            stroke.strokeWidth = unit * 1.4f
            canvas.drawLine(left, ys[i], right, ys[i], stroke)
            stroke.color = steelLight
            stroke.alpha = 200
            stroke.strokeWidth = unit * 0.45f
            canvas.drawLine(left, ys[i] - unit * 0.25f, right, ys[i] - unit * 0.25f, stroke)
            stroke.alpha = 255
            stroke.color = accent
            stroke.strokeWidth = unit * 0.9f
            canvas.drawLine(cx - unit * 1.2f, ys[i], cx + unit * 1.2f, ys[i], stroke)
            fill.color = steelLight
            val bolt = unit * 0.32f
            canvas.drawCircle(left + (right - left) * 0.25f, ys[i], bolt, fill)
            canvas.drawCircle(left + (right - left) * 0.75f, ys[i], bolt, fill)
        }

        // Нити: тёмный кабель и стальная сегментированная оплётка поверх.
        buildStrand(strandPath1, x1, ys)
        buildStrand(strandPath2, x2, ys)
        for (path in listOf(strandPath1, strandPath2)) {
            stroke.pathEffect = null
            stroke.color = 0xFF1E1E1E.toInt()
            stroke.strokeWidth = unit * 2f
            canvas.drawPath(path, stroke)
            stroke.color = steelLight
            stroke.strokeWidth = unit * 0.6f
            stroke.pathEffect = dashFor(unit)
            canvas.drawPath(path, stroke)
        }
        stroke.pathEffect = null

        // Передние узлы — яркие, со свечением.
        for (i in 0..count) {
            if (depth[i] > 0) drawNode(canvas, x1[i], ys[i], nodeRadius, front = true)
            if (-depth[i] > 0) drawNode(canvas, x2[i], ys[i], nodeRadius, front = true)
        }
    }

    private fun dashFor(unit: Float): DashPathEffect {
        val current = dash
        if (current != null && dashUnit == unit) return current
        return DashPathEffect(floatArrayOf(unit * 1.2f, unit * 0.8f), 0f).also {
            dash = it
            dashUnit = unit
        }
    }

    private fun buildStrand(path: Path, xs: FloatArray, ys: FloatArray) {
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) {
            val midY = (ys[i - 1] + ys[i]) / 2f
            path.cubicTo(xs[i - 1], midY, xs[i], midY, xs[i], ys[i])
        }
    }

    private fun drawNode(canvas: Canvas, x: Float, y: Float, radius: Float, front: Boolean) {
        if (front) {
            fill.color = accent
            fill.alpha = 60
            canvas.drawCircle(x, y, radius * 2.4f, fill)
            fill.alpha = 110
            canvas.drawCircle(x, y, radius * 1.6f, fill)
            fill.alpha = 255
            fill.color = accentLight
            canvas.drawCircle(x, y, radius, fill)
            stroke.color = steelLight
        } else {
            fill.color = accentDark
            canvas.drawCircle(x, y, radius * 0.85f, fill)
            stroke.color = steelDark
        }
        stroke.strokeWidth = radius * 0.35f
        canvas.drawCircle(x, y, radius * 1.35f, stroke)
    }
}
