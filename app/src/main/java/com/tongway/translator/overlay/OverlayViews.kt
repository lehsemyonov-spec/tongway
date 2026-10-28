package com.tongway.translator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.tongway.translator.R

private fun View.dp(value: Float) = value * resources.displayMetrics.density

/** Прицел над пальцем: куда он указывает, там и ищется текст. */
class CrosshairView(context: Context) : View(context) {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(5f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.aim)
        strokeWidth = dp(2.5f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.aim)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - dp(4f)
        val gap = dp(4f)
        for (paint in listOf(outline, line)) {
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawLine(cx - radius, cy, cx - gap, cy, paint)
            canvas.drawLine(cx + gap, cy, cx + radius, cy, paint)
            canvas.drawLine(cx, cy - radius, cx, cy - gap, paint)
            canvas.drawLine(cx, cy + gap, cx, cy + radius, paint)
        }
        canvas.drawCircle(cx, cy, dp(2f), dot)
    }
}

/** Крестик внизу экрана: если отпустить на нём кружок, кружок исчезнет. */
class CloseZoneView(context: Context) : View(context) {
    var active = false
        set(value) {
            if (field == value) return
            field = value
            animate().scaleX(if (value) 1.25f else 1f).scaleY(if (value) 1.25f else 1f).setDuration(120).start()
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - dp(8f)
        fill.color = if (active) Color.parseColor("#E53935") else Color.parseColor("#99000000")
        canvas.drawCircle(cx, cy, radius, fill)
        val arm = radius * 0.4f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, cross)
    }
}

/** Рамка вокруг текста, который сейчас переводится. */
class HighlightView(context: Context) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.brand)
        strokeWidth = dp(2f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand)
        alpha = 30
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val inset = stroke.strokeWidth / 2
        rect.set(inset, inset, width - inset, height - inset)
        val corner = dp(6f)
        canvas.drawRoundRect(rect, corner, corner, fill)
        canvas.drawRoundRect(rect, corner, corner, stroke)
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
