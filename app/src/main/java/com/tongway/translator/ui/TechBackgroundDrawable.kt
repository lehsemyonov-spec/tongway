package com.tongway.translator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.tongway.translator.R

/** Фон как на слайдах: чёрный, тонкая сетка и оранжевое свечение по краям. */
class TechBackgroundDrawable(context: Context) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val cell = 32f * density
    private val glowTop = ContextCompat.getColor(context, R.color.accent_dark)
    private val glowSide = ContextCompat.getColor(context, R.color.glow)

    private val gridPaint = Paint().apply {
        color = 0x12FFFFFF
        strokeWidth = density
    }
    private val topGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sideGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        topGlow.shader = RadialGradient(w, 0f, w * 0.95f, glowTop, 0x00000000, Shader.TileMode.CLAMP)
        sideGlow.shader = RadialGradient(0f, h * 0.62f, w * 0.8f, glowSide, 0x00000000, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(0xFF000000.toInt())
        canvas.drawRect(b, topGlow)
        canvas.drawRect(b, sideGlow)
        var x = b.left.toFloat()
        while (x < b.right) {
            canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), gridPaint)
            x += cell
        }
        var y = b.top.toFloat()
        while (y < b.bottom) {
            canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, gridPaint)
            y += cell
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
