package com.boringman.splashdash

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * Resolution-independent symbols on a 24-unit grid, standing in for the SF Symbols the iOS app
 * uses. Without intrinsic size they fill whatever bounds the view gives them.
 */
class ControlIcon(private val symbol: String, color: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val path = Path()
    private val box = RectF()

    private fun shape(vararg p: Float, close: Boolean = true): Path {
        path.reset(); path.moveTo(p[0], p[1])
        for (i in 2 until p.size step 2) path.lineTo(p[i], p[i + 1])
        if (close) path.close()
        return path
    }
    private fun heart(c: Canvas, paint: Paint) {
        path.reset()
        path.moveTo(12f, 21f)
        path.cubicTo(0.5f, 13.5f, 1f, 3f, 7.5f, 3.5f)
        path.cubicTo(9.5f, 3.6f, 11f, 5f, 12f, 6.8f)
        path.cubicTo(13f, 5f, 14.5f, 3.6f, 16.5f, 3.5f)
        path.cubicTo(23f, 3f, 23.5f, 13.5f, 12f, 21f)
        path.close()
        c.drawPath(path, paint)
    }

    override fun draw(c: Canvas) {
        c.save()
        c.translate(bounds.left.toFloat(), bounds.top.toFloat())
        c.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = c.drawLine(x, y, x2, y2, stroke)
        when (symbol) {
            "chest" -> {
                // Stands in for SF Symbol shippingbox.fill: a treasure chest with a lid band.
                box.set(3f, 9f, 21f, 21f); c.drawRoundRect(box, 2f, 2f, fill)
                box.set(3.5f, 4f, 20.5f, 10f); c.drawRoundRect(box, 3f, 3f, fill)
                stroke.color = Clay.ink; stroke.strokeWidth = 1.8f
                line(3.5f, 10.5f, 20.5f, 10.5f); line(12f, 8.5f, 12f, 14f)
                stroke.color = fill.color; stroke.strokeWidth = 2.6f
            }
            "forward" -> {
                // Stands in for SF Symbol forward.end.fill.
                c.drawPath(shape(3f, 5f, 11f, 12f, 3f, 19f), fill)
                c.drawPath(shape(11f, 5f, 19f, 12f, 11f, 19f), fill)
                box.set(19f, 5f, 22f, 19f); c.drawRoundRect(box, 1f, 1f, fill)
            }
            "envelope" -> {
                // Stands in for SF Symbol envelope.open.fill.
                // Outlined so the flap reads on any button colour.
                box.set(3f, 6f, 21f, 19f); c.drawRoundRect(box, 2.5f, 2.5f, stroke)
                c.drawPath(shape(3.5f, 7f, 12f, 13.5f, 20.5f, 7f, close = false), stroke)
            }
            "xmark" -> { stroke.strokeWidth = 3.2f; line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f); stroke.strokeWidth = 2.6f }
            "check" -> { stroke.strokeWidth = 3.2f; c.drawPath(shape(4f, 12.5f, 9.5f, 18f, 20f, 6f, close = false), stroke); stroke.strokeWidth = 2.6f }
            "ad" -> {
                // Stands in for SF Symbol play.rectangle.fill.
                box.set(2f, 4.5f, 22f, 19.5f); c.drawRoundRect(box, 3.5f, 3.5f, stroke)
                c.drawPath(shape(9.5f, 8.5f, 16f, 12f, 9.5f, 15.5f), fill)
            }
            "pause" -> {
                box.set(5.5f, 4f, 10f, 20f); c.drawRoundRect(box, 1.5f, 1.5f, fill)
                box.set(14f, 4f, 18.5f, 20f); c.drawRoundRect(box, 1.5f, 1.5f, fill)
            }
            "heart" -> heart(c, fill)
            "heart.slash" -> {
                fill.alpha = 150; heart(c, fill); fill.alpha = 255
                line(3f, 3f, 21f, 21f)
            }
            "scope" -> {
                c.drawCircle(12f, 12f, 7f, stroke)
                line(12f, 1.5f, 12f, 6.5f); line(12f, 17.5f, 12f, 22.5f)
                line(1.5f, 12f, 6.5f, 12f); line(17.5f, 12f, 22.5f, 12f)
                c.drawCircle(12f, 12f, 2.2f, fill)
            }
            "play" -> {
                stroke.strokeWidth = 2f
                val p = shape(7f, 4f, 20f, 12f, 7f, 20f)
                c.drawPath(p, fill); c.drawPath(p, stroke)
                stroke.strokeWidth = 2.6f
            }
            "sailboat" -> {
                c.drawPath(shape(11f, 2f, 11f, 14.5f, 3.5f, 14.5f), fill)
                c.drawPath(shape(13f, 4.5f, 13f, 14.5f, 19.5f, 14.5f), fill)
                c.drawPath(shape(2.5f, 16.5f, 21.5f, 16.5f, 18f, 21f, 6f, 21f), fill)
            }
            "restart" -> {
                box.set(4.5f, 4.5f, 19.5f, 19.5f)
                c.drawArc(box, 40f, 290f, false, stroke)
                // Arrowhead at the arc's end (330°), pointing clockwise along the arc.
                val a = Math.toRadians(330.0)
                val ex = (12 + 7.5 * cos(a)).toFloat(); val ey = (12 + 7.5 * sin(a)).toFloat()
                val tx = (-sin(a)).toFloat(); val ty = cos(a).toFloat()
                c.drawPath(shape(ex + tx * 4f, ey + ty * 4f, ex - ty * 3.5f - tx, ey + tx * 3.5f - ty, ex + ty * 3.5f - tx, ey - tx * 3.5f - ty), fill)
            }
            "trophy" -> {
                path.reset(); path.moveTo(6.5f, 3f); path.lineTo(17.5f, 3f); path.lineTo(17.5f, 9f)
                path.quadTo(17.5f, 14.5f, 12f, 14.5f); path.quadTo(6.5f, 14.5f, 6.5f, 9f); path.close()
                c.drawPath(path, fill)
                box.set(2.5f, 4.5f, 8.5f, 11f); c.drawArc(box, 90f, 180f, false, stroke)
                box.set(15.5f, 4.5f, 21.5f, 11f); c.drawArc(box, 270f, 180f, false, stroke)
                c.drawRect(10.8f, 14f, 13.2f, 18f, fill)
                box.set(7.5f, 18f, 16.5f, 21.5f); c.drawRoundRect(box, 1.2f, 1.2f, fill)
            }
            "crown" -> c.drawPath(shape(2.5f, 7.5f, 8f, 12f, 12f, 4f, 16f, 12f, 21.5f, 7.5f, 19f, 19f, 5f, 19f), fill)
            "speaker.on", "speaker.off" -> {
                c.drawPath(shape(2.5f, 9f, 6.5f, 9f, 12f, 4.5f, 12f, 19.5f, 6.5f, 15f, 2.5f, 15f), fill)
                if (symbol == "speaker.on") {
                    box.set(10f, 8f, 18f, 16f); c.drawArc(box, -50f, 100f, false, stroke)
                    box.set(9f, 4.5f, 22f, 19.5f); c.drawArc(box, -50f, 100f, false, stroke)
                } else {
                    line(15.5f, 9f, 21f, 15f); line(21f, 9f, 15.5f, 15f)
                }
            }
            "book" -> {
                box.set(4.5f, 3f, 19.5f, 21f); c.drawRoundRect(box, 2.5f, 2.5f, fill)
                stroke.color = Color.argb(150, 11, 37, 48)
                line(8.5f, 3.5f, 8.5f, 20.5f); line(11.5f, 8f, 16.5f, 8f)
                stroke.color = fill.color
            }
            "swipe" -> {
                line(3f, 12f, 21f, 12f)
                c.drawPath(shape(7f, 8f, 3f, 12f, 7f, 16f, close = false), stroke)
                c.drawPath(shape(17f, 8f, 21f, 12f, 17f, 16f, close = false), stroke)
            }
            "arrow.up" -> {
                c.drawCircle(12f, 12f, 10f, fill)
                stroke.color = Clay.ink; stroke.strokeWidth = 2.8f
                line(12f, 17f, 12f, 7.5f)
                c.drawPath(shape(7.5f, 11.5f, 12f, 7f, 16.5f, 11.5f, close = false), stroke)
                stroke.color = fill.color; stroke.strokeWidth = 2.6f
            }
            "tap" -> {
                c.drawCircle(12f, 12f, 3.5f, fill)
                stroke.alpha = 150; c.drawCircle(12f, 12f, 8f, stroke); stroke.alpha = 255
            }
            "waves" -> for (y in floatArrayOf(8f, 15f)) {
                path.reset(); path.moveTo(2.5f, y)
                path.quadTo(5f, y - 3f, 7.5f, y); path.quadTo(10f, y + 3f, 12.5f, y)
                path.quadTo(15f, y - 3f, 17.5f, y); path.quadTo(20f, y + 3f, 21.5f, y)
                c.drawPath(path, stroke)
            }
            "flag" -> {
                line(5f, 3f, 5f, 21.5f)
                c.drawPath(shape(6f, 3.5f, 19.5f, 3.5f, 16f, 8.5f, 19.5f, 13.5f, 6f, 13.5f), fill)
            }
            "warning" -> {
                stroke.strokeWidth = 2.2f
                val p = shape(12f, 2.5f, 22f, 20.5f, 2f, 20.5f)
                c.drawPath(p, fill); c.drawPath(p, stroke)
                stroke.color = Clay.ink; stroke.strokeWidth = 2.6f
                line(12f, 9f, 12f, 14f); c.drawPoint(12f, 17.5f, stroke)
                stroke.color = fill.color
            }
            "wind" -> {
                path.reset(); path.moveTo(2.5f, 8f); path.lineTo(15f, 8f); path.quadTo(19f, 8f, 19f, 5f); path.quadTo(19f, 2.5f, 16f, 3f)
                c.drawPath(path, stroke)
                path.reset(); path.moveTo(2.5f, 12.5f); path.lineTo(18.5f, 12.5f); path.quadTo(22f, 12.5f, 22f, 15.5f); path.quadTo(22f, 18.5f, 18.5f, 18f)
                c.drawPath(path, stroke)
                line(2.5f, 17f, 10f, 17f)
            }
        }
        c.restore()
    }
    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { stroke.colorFilter = filter; fill.colorFilter = filter; invalidateSelf() }
    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
