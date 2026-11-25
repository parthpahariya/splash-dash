package com.parthpahariya.splashdash

import android.graphics.*
import android.graphics.drawable.Drawable

/** Resolution-independent symbols for native game buttons. */
class ControlIcon(private val symbol: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        when (symbol) {
            "pause" -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
            "fire" -> {
                canvas.drawCircle(12f, 12f, 7f, paint)
                line(12f, 2f, 12f, 7f); line(12f, 17f, 12f, 22f)
                line(2f, 12f, 7f, 12f); line(17f, 12f, 22f, 12f)
            }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
