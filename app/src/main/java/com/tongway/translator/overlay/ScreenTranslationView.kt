package com.tongway.translator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.tongway.translator.R

/**
 * Перевод всего экрана: поверх каждого куска текста — тёмная плашка с переводом.
 * Пока кусок ещё переводится, вместо плашки видна оранжевая рамка.
 */
class ScreenTranslationView(context: Context, private val blocks: List<Rect>) : View(context) {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val texts = arrayOfNulls<String>(blocks.size)
    private val layouts = arrayOfNulls<StaticLayout>(blocks.size)
    private val location = IntArray(2)
    private val rect = RectF()

    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF00B0B0B.toInt() }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val pending = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.accent)
        alpha = 140
        strokeWidth = 1.5f * density
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = ResourcesCompat.getFont(context, R.font.inter)
    }

    fun setTranslation(index: Int, text: String) {
        texts[index] = text
        layouts[index] = null
        invalidate()
    }

    fun allTranslations(): String = texts.filterNotNull().joinToString("\n\n")

    override fun onDraw(canvas: Canvas) {
        // Координаты блоков — экранные; окно может быть сдвинуто (вырез камеры), учитываем это.
        getLocationOnScreen(location)
        val corner = 6f * density
        for (i in blocks.indices) {
            val b = blocks[i]
            rect.set(
                (b.left - location[0]).toFloat(),
                (b.top - location[1]).toFloat(),
                (b.right - location[0]).toFloat(),
                (b.bottom - location[1]).toFloat(),
            )
            val text = texts[i]
            if (text == null) {
                canvas.drawRoundRect(rect, corner, corner, pending)
                continue
            }
            val layout = layouts[i] ?: fit(text, rect.width(), rect.height()).also { layouts[i] = it }
            // Если перевод длиннее оригинала, плашка вытягивается вниз.
            rect.bottom = maxOf(rect.bottom, rect.top + layout.height + PAD * density * 2)
            canvas.drawRoundRect(rect, corner, corner, box)
            canvas.drawRect(rect.left, rect.top + corner / 2, rect.left + 2.5f * density, rect.bottom - corner / 2, bar)
            canvas.save()
            canvas.translate(rect.left + PAD * density + 2 * density, rect.top + PAD * density)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /** Подбирает размер шрифта, чтобы перевод поместился в место оригинала. */
    private fun fit(text: String, width: Float, height: Float): StaticLayout {
        val innerWidth = maxOf(1, (width - PAD * density * 2 - 2 * density).toInt())
        val innerHeight = height - PAD * density * 2
        var size = MAX_SP
        while (true) {
            textPaint.textSize = size * scaled
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, innerWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            if (layout.height <= innerHeight || size <= MIN_SP) return layout
            size -= 1f
        }
    }

    private companion object {
        const val PAD = 3f
        const val MAX_SP = 16f
        const val MIN_SP = 9f
    }
}
