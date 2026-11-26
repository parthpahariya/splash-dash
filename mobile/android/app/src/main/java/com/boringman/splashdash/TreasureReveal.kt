package com.boringman.splashdash

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The treasure's contents, shown when the voyage is won: the chest opens, a ship in a bottle
 * rises out, and a sealed letter slides in. Plays once; with reduced motion it appears open.
 * Matches TreasureChestArt in WakeRunnerApp.swift, drawn on the same 300 × 170 grid.
 */
class TreasureRevealView(
    context: Context, reducedMotion: Boolean, private val mode: Mode = Mode.OPENING, private val onProgress: (Float) -> Unit = {},
) : View(context) {
    /** CLOSED: the shut chest (step 1). OPENING: the chest opens (step 2). BOTTLE: just the bottle (step 3). */
    enum class Mode { CLOSED, OPENING, BOTTLE }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val box = RectF()
    private var progress = if (mode == Mode.CLOSED) 0f else if (reducedMotion || mode == Mode.BOTTLE) 1f else 0f
    private var animator: ValueAnimator? = null

    init {
        contentDescription = when (mode) {
            Mode.CLOSED -> "A closed treasure chest"
            Mode.OPENING -> "The open chest holds a ship in a bottle and a sealed letter"
            Mode.BOTTLE -> "A ship in a bottle"
        }
        onProgress(progress)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode == Mode.CLOSED || progress >= 1f) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600; startDelay = 200; interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; onProgress(progress); invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    private fun phase(from: Float, length: Float): Float {
        val t = ((progress - from) / length).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
    private fun c(hex: String, alpha: Float = 1f) = Clay.alpha(Color.parseColor("#$hex"), alpha)
    private fun fill(color: Int) { paint.style = Paint.Style.FILL; paint.color = color }
    private fun stroke(color: Int, width: Float) { paint.style = Paint.Style.STROKE; paint.color = color; paint.strokeWidth = width }
    private fun poly(vararg p: Float): Path {
        path.reset(); path.moveTo(p[0], p[1])
        for (i in 2 until p.size step 2) path.lineTo(p[i], p[i + 1])
        path.close(); return path
    }

    /** The corked bottle with a little ship inside, centred on (x, y) in the 300 × 170 grid. */
    private fun drawBottle(cv: Canvas, x: Float, y: Float, angle: Float) {
        val ink = Clay.ink
        cv.save(); cv.translate(x, y); cv.rotate(angle)
        box.set(-55f, -17f, 40f, 17f)
        fill(c("A8ECF2", .4f)); cv.drawRoundRect(box, 17f, 17f, paint)
        fill(c("1E8C9A", .85f)); cv.drawRoundRect(RectF(-51f, 4f, 37f, 14f), 5f, 5f, paint)
        val hull = poly(-30f, 3f, 10f, 3f, 4f, 10f, -26f, 10f)
        fill(c("4A3328")); cv.drawPath(hull, paint); stroke(ink, 1.5f); cv.drawPath(hull, paint)
        stroke(ink, 2f); cv.drawLine(-16f, 3f, -16f, -12f, paint); cv.drawLine(-2f, 3f, -2f, -9f, paint)
        for (sail in listOf(floatArrayOf(-15f, -11f, -6f, -1f, -15f, -1f), floatArrayOf(-1f, -8f, 6f, 0f, -1f, 0f))) {
            val p = poly(*sail)
            fill(c("FFF6E0")); cv.drawPath(p, paint); stroke(ink, 1.2f); cv.drawPath(p, paint)
        }
        fill(c("D9483B")); cv.drawPath(poly(-16f, -12f, -10f, -14f, -16f, -16f), paint)
        stroke(ink, 3f); cv.drawRoundRect(box, 17f, 17f, paint)
        box.set(38f, -8f, 60f, 8f)
        fill(c("A8ECF2", .4f)); cv.drawRoundRect(box, 3f, 3f, paint); stroke(ink, 3f); cv.drawRoundRect(box, 3f, 3f, paint)
        box.set(59f, -7f, 70f, 7f)
        fill(c("9A6E48")); cv.drawRoundRect(box, 3f, 3f, paint); stroke(ink, 2f); cv.drawRoundRect(box, 3f, 3f, paint)
        stroke(Clay.alpha(Color.WHITE, .65f), 3f); cv.drawLine(-40f, -10f, 20f, -10f, paint)
        cv.restore()
    }

    override fun onDraw(cv: Canvas) {
        if (mode == Mode.BOTTLE) {
            val b = minOf(width / 140f, height / 50f)
            cv.save(); cv.translate(width / 2f, height / 2f); cv.scale(b, b)
            drawBottle(cv, -8f, 0f, -6f)
            cv.restore()
            return
        }
        val s = minOf(width / 300f, height / 170f)
        cv.save()
        cv.translate((width - 300 * s) / 2, (height - 170 * s) / 2)
        cv.scale(s, s)
        val ink = Clay.ink
        val lid = phase(0f, .4f); val rise = phase(.3f, .5f); val letter = phase(.55f, .45f)

        fill(Clay.alpha(ink, .2f)); box.set(40f, 142f, 260f, 164f); cv.drawOval(box, paint)
        // Lid swinging back, then the dark inside of the chest.
        val top = 80 - 23 * lid
        val lidPath = poly(95f, 95f, 215f, 95f, 215 - 6 * lid, top, 95 + 6 * lid, top)
        fill(c(if (lid > .5f) "7A4E2E" else "A86E42")); cv.drawPath(lidPath, paint)
        stroke(ink, 3f); cv.drawPath(lidPath, paint)
        stroke(c("F3C75F"), 4f); cv.drawLine(97 + 6 * lid, top + 6, 213 - 6 * lid, top + 6, paint)
        if (lid > 0) { fill(c("3D2716")); cv.drawRect(100f, 95 - 8 * lid, 210f, 95f, paint) }
        // Warm light, and the bottle rising out of the chest.
        fill(c("FFF1B8", .55f * rise)); box.set(155 - 70 * rise, 80 - 40 * rise, 155 + 70 * rise, 80 + 40 * rise); cv.drawOval(box, paint)
        if (rise > 0) drawBottle(cv, 155f, 112 - 50 * rise, -10f) // hidden while the chest is shut
        // Chest front, drawn over the bottle so it rises from inside.
        box.set(95f, 95f, 215f, 148f)
        fill(c("8A5A34")); cv.drawRoundRect(box, 6f, 6f, paint); stroke(ink, 3f); cv.drawRoundRect(box, 6f, 6f, paint)
        stroke(c("F3C75F"), 5f); cv.drawLine(96f, 110f, 214f, 110f, paint); cv.drawLine(96f, 134f, 214f, 134f, paint)
        box.set(148f, 100f, 162f, 117f)
        fill(c("F3C75F")); cv.drawRoundRect(box, 3f, 3f, paint); stroke(ink, 2f); cv.drawRoundRect(box, 3f, 3f, paint)
        // The sealed letter slides in beside the chest.
        if (letter > 0) {
            val layer = cv.saveLayerAlpha(null, (255 * letter).toInt())
            cv.translate(64 - 40 * (1 - letter), 126f); cv.rotate(-8f)
            box.set(-26f, -20f, 26f, 20f)
            fill(c("F2E2B8")); cv.drawRect(box, paint); stroke(ink, 2.5f); cv.drawRect(box, paint)
            for (y in floatArrayOf(-21f, 21f)) {
                box.set(-29f, y - 5, 29f, y + 5)
                fill(c("E5CF98")); cv.drawOval(box, paint); stroke(ink, 2f); cv.drawOval(box, paint)
            }
            stroke(Clay.alpha(ink, .35f), 2f)
            for (y in floatArrayOf(-9f, -1f, 7f)) cv.drawLine(-18f, y, 14f, y, paint)
            box.set(7f, 8f, 21f, 22f)
            fill(c("D9483B")); cv.drawOval(box, paint); stroke(ink, 2f); cv.drawOval(box, paint)
            cv.restoreToCount(layer)
        }
        cv.restore()
    }
}
