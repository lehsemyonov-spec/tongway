package com.tongway.translator.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.tongway.translator.PageLayout
import com.tongway.translator.R

/** Светящаяся оранжевая линия «пальцевого перевода»; общий рисунок для экрана и для примера в настройках. */
class FingerStrokePainter(context: Context) {
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val accentLight = ContextCompat.getColor(context, R.color.accent_light)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = accent
        alpha = 60
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = accent
        alpha = 130
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = accentLight
        alpha = 230
    }

    fun draw(canvas: Canvas, path: Path, width: Float) {
        glow.strokeWidth = width * 1.5f
        body.strokeWidth = width
        core.strokeWidth = (width * 0.18f).coerceIn(2f, 8f)
        canvas.drawPath(path, glow)
        canvas.drawPath(path, body)
        canvas.drawPath(path, core)
    }

    companion object {
        /** Плавная линия через точки. */
        fun pathOf(points: List<PointF>): Path {
            val path = Path()
            if (points.isEmpty()) return path
            path.moveTo(points[0].x, points[0].y)
            if (points.size == 1) {
                path.lineTo(points[0].x + 0.1f, points[0].y)
                return path
            }
            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val cur = points[i]
                path.quadTo(prev.x, prev.y, (prev.x + cur.x) / 2, (prev.y + cur.y) / 2)
            }
            path.lineTo(points.last().x, points.last().y)
            return path
        }
    }
}

/**
 * Прозрачный слой на весь экран в режиме «пальцевого перевода»: ловит палец, рисует за ним линию,
 * после отрыва подсвечивает выбранные слова оранжевым маркером.
 */
@SuppressLint("ViewConstructor")
class FingerCanvasView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onStrokeStart()
        /** Точки линии в координатах экрана. */
        fun onStrokeEnd(points: List<PageLayout.Pt>)
    }

    var strokeWidthPx = 40f

    /** Пока делается снимок экрана, линию не рисуем — чтобы она не попала на снимок. */
    var hideStroke = false
        set(value) {
            field = value
            invalidate()
        }

    private val painter = FingerStrokePainter(context)
    private val points = mutableListOf<PointF>()
    private var marks: List<RectF> = emptyList()
    private val location = IntArray(2)
    private val density = resources.displayMetrics.density
    private val markFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        alpha = 70
    }
    private val markLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        strokeWidth = 2.5f * density
    }

    /** Подсветить выбранные слова (координаты экрана); линия при этом гаснет. */
    fun showMarks(words: List<Rect>) {
        getLocationOnScreen(location)
        val pad = 2 * density
        marks = words.map {
            RectF(
                it.left - location[0] - pad,
                it.top - location[1] - pad,
                it.right - location[0] + pad,
                it.bottom - location[1] + pad,
            )
        }
        points.clear()
        invalidate()
    }

    fun clear() {
        points.clear()
        marks = emptyList()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                marks = emptyList()
                points.add(PointF(event.x, event.y))
                listener.onStrokeStart()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) points.add(PointF(event.getHistoricalX(i), event.getHistoricalY(i)))
                points.add(PointF(event.x, event.y))
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                points.add(PointF(event.x, event.y))
                getLocationOnScreen(location)
                listener.onStrokeEnd(points.map { PageLayout.Pt(it.x + location[0], it.y + location[1]) })
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> clear()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val corner = 4 * density
        for (m in marks) {
            canvas.drawRoundRect(m, corner, corner, markFill)
            canvas.drawLine(m.left, m.bottom, m.right, m.bottom, markLine)
        }
        if (!hideStroke && points.isNotEmpty()) {
            painter.draw(canvas, FingerStrokePainter.pathOf(points), strokeWidthPx)
        }
    }
}

/** Пример линии в настройках: меняется вместе с ползунком толщины. */
class FingerStrokePreview @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {
    var strokeWidthPx = 40f
        set(value) {
            field = value
            invalidate()
        }
    private val painter = FingerStrokePainter(context)
    private val sample = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textSize = 15 * resources.displayMetrics.scaledDensity
    }

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f
        val lineGap = sample.textSize * 1.5f
        canvas.drawText("The quick brown fox jumps over", 12f, y - lineGap + sample.textSize / 3, sample)
        canvas.drawText("the lazy dog near the river bank", 12f, y + sample.textSize / 3, sample)
        canvas.drawText("and runs away into the forest.", 12f, y + lineGap + sample.textSize / 3, sample)
        val w = width.toFloat()
        val points = listOf(PointF(w * 0.12f, y), PointF(w * 0.35f, y - 3), PointF(w * 0.6f, y + 2), PointF(w * 0.82f, y))
        painter.draw(canvas, FingerStrokePainter.pathOf(points), strokeWidthPx)
    }
}
