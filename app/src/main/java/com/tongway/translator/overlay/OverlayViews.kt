package com.tongway.translator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.tongway.translator.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun View.dp(value: Float) = value * resources.displayMetrics.density

private fun View.color(id: Int) = ContextCompat.getColor(context, id)

/**
 * Кружок: чёрная «линза» с оранжевым ободком и свечением, вокруг — зубчатое кольцо,
 * которое крутится, пока идёт перевод.
 */
class BubbleView(context: Context) : View(context) {
    var busy = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val accent = color(R.color.accent)
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_translate)!!.mutate().apply { setTint(accent) }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B0B.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
        strokeWidth = dp(2f)
    }
    private val ticks = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = min(w, h) / 2f
        glow.shader = RadialGradient(w / 2f, h / 2f, r, intArrayOf(0x99EA5F0A.toInt(), 0x00EA5F0A), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(cx, cy)
        val radius = outer - dp(10f)
        canvas.drawCircle(cx, cy, outer, glow)
        canvas.drawCircle(cx, cy, radius, core)
        canvas.drawCircle(cx, cy, radius, ring)

        // Зубчатое кольцо, как у шестерёнки.
        val spin = if (busy) (SystemClock.uptimeMillis() % 2400L) / 2400f * 360f else 0f
        ticks.alpha = if (busy) 255 else 130
        val inner = radius + dp(3f)
        val tip = radius + dp(7f)
        for (i in 0 until TEETH) {
            val angle = Math.toRadians((spin + i * 360f / TEETH).toDouble())
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            canvas.drawLine(cx + c * inner, cy + s * inner, cx + c * tip, cy + s * tip, ticks)
        }

        val iconSize = (radius * 1.05f).toInt()
        icon.setBounds((cx - iconSize / 2).toInt(), (cy - iconSize / 2).toInt(), (cx + iconSize / 2).toInt(), (cy + iconSize / 2).toInt())
        icon.draw(canvas)
        if (busy) postInvalidateOnAnimation()
    }

    private companion object {
        const val TEETH = 24
    }
}

/**
 * Прицел в стиле экрана робота: круг, перекрестие и уголки.
 * Пока прицел держат на месте, вокруг заполняется дуга (progress); когда она замкнулась —
 * цель захвачена (locked): прицел становится ярким, и после отпускания будет перевод.
 */
class CrosshairView(context: Context) : View(context) {
    private val accent = color(R.color.accent)
    private val accentLight = color(R.color.accent_light)

    var progress = 0f
        set(value) {
            field = value
            invalidate()
        }

    var locked = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                scaleX = 0.8f
                scaleY = 0.8f
                animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            invalidate()
        }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcRect = RectF()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x88000000.toInt()
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(cx, cy) * 0.5f
        val edge = dp(4f)
        val arm = dp(9f)
        line.color = if (locked) accentLight else accent
        line.alpha = if (locked) 255 else 190
        line.strokeWidth = if (locked) dp(3f) else dp(2.5f)
        if (locked) {
            glow.color = accent
            glow.alpha = 70
            canvas.drawCircle(cx, cy, radius * 1.35f, glow)
        }
        for (paint in listOf(shadow, line)) {
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawLine(cx, edge + dp(4f), cx, cy - radius * 0.5f, paint)
            canvas.drawLine(cx, cy + radius * 0.5f, cx, h - edge - dp(4f), paint)
            canvas.drawLine(edge + dp(4f), cy, cx - radius * 0.5f, cy, paint)
            canvas.drawLine(cx + radius * 0.5f, cy, w - edge - dp(4f), cy, paint)
            // Уголки рамки.
            canvas.drawLine(edge, edge, edge + arm, edge, paint)
            canvas.drawLine(edge, edge, edge, edge + arm, paint)
            canvas.drawLine(w - edge, edge, w - edge - arm, edge, paint)
            canvas.drawLine(w - edge, edge, w - edge, edge + arm, paint)
            canvas.drawLine(edge, h - edge, edge + arm, h - edge, paint)
            canvas.drawLine(edge, h - edge, edge, h - edge - arm, paint)
            canvas.drawLine(w - edge, h - edge, w - edge - arm, h - edge, paint)
            canvas.drawLine(w - edge, h - edge, w - edge, h - edge - arm, paint)
        }
        // Дуга захвата цели.
        if (progress > 0f && !locked) {
            arc.color = accentLight
            arcRect.set(cx - radius - dp(4f), cy - radius - dp(4f), cx + radius + dp(4f), cy + radius + dp(4f))
            canvas.drawArc(arcRect, -90f, 360f * progress, false, arc)
        }
        dot.color = if (locked) accent else 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, if (locked) dp(3.5f) else dp(2.5f), dot)
    }
}

/** Крестик внизу экрана: если отпустить на нём кружок, кружок исчезнет. */
class CloseZoneView(context: Context) : View(context) {
    var active = false
        set(value) {
            if (field == value) return
            field = value
            animate().scaleX(if (value) 1.2f else 1f).scaleY(if (value) 1.2f else 1f).setDuration(120).start()
            invalidate()
        }

    private val accent = color(R.color.accent)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - dp(9f)
        if (active) {
            fill.color = accent
            fill.alpha = 60
            canvas.drawCircle(cx, cy, radius + dp(6f), fill)
        }
        fill.color = if (active) accent else 0xDD0B0B0B.toInt()
        fill.alpha = 255
        canvas.drawCircle(cx, cy, radius, fill)
        ring.color = if (active) 0xFFFFFFFF.toInt() else accent
        canvas.drawCircle(cx, cy, radius, ring)
        val arm = radius * 0.38f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, cross)
    }
}

/** Оранжевые уголки вокруг текста, который сейчас переводится. */
class HighlightView(context: Context) : View(context) {
    private val accent = color(R.color.accent)
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        alpha = 22
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val inset = corner.strokeWidth
        val w = width - inset
        val h = height - inset
        rect.set(inset, inset, w, h)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), fill)
        val arm = min(dp(16f), min(w, h) / 2.5f)
        canvas.drawLine(inset, inset, inset + arm, inset, corner)
        canvas.drawLine(inset, inset, inset, inset + arm, corner)
        canvas.drawLine(w, inset, w - arm, inset, corner)
        canvas.drawLine(w, inset, w, inset + arm, corner)
        canvas.drawLine(inset, h, inset + arm, h, corner)
        canvas.drawLine(inset, h, inset, h - arm, corner)
        canvas.drawLine(w, h, w - arm, h, corner)
        canvas.drawLine(w, h, w, h - arm, corner)
    }
}

/** Прокрутка с ограничением высоты, чтобы длинный перевод не закрывал весь экран. */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    var maxHeightPx = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}
