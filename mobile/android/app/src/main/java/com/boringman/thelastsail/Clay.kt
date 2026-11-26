package com.boringman.thelastsail

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Claymorphism-inspired toy-boat theme shared with the iOS app (Theme in WakeRunnerApp.swift):
 * chunky ink outlines, hard drop shadows and warm gold for actions and rewards on a tropical sea.
 */
object Clay {
    val ink = Color.parseColor("#0B2530")
    val deep = Color.parseColor("#0E3440")
    val surface = Color.parseColor("#17485A")
    val surfaceTop = Color.parseColor("#21627A")
    val cream = Color.parseColor("#FFF6E0")
    val creamMuted = Color.parseColor("#C9E4E0")
    val gold = Color.parseColor("#FFC94D")
    val goldTop = Color.parseColor("#FFE08A")
    val goldDeep = Color.parseColor("#F2A922")
    val mint = Color.parseColor("#5FE3C0")
    val heart = Color.parseColor("#FF8FA3")
    val danger = Color.parseColor("#FF5D73")
    val sea = Color.parseColor("#1E8C9A")
    const val BORDER_DP = 3f
    const val DEPTH_DP = 4f

    fun alpha(color: Int, alpha: Float) = (color and 0xFFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

    private val fonts = HashMap<Int, Typeface>()
    /** Heavy sans-serif weights (900 = black) on Android 9+, bold on older versions. */
    fun font(weight: Int): Typeface = fonts.getOrPut(weight) {
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, weight, false)
        else Typeface.create(Typeface.SANS_SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Overshoots slightly past 1 and settles: used for pops (the ✗, "Set sail!"). */
    fun easeOutBack(v: Double): Double {
        val t = v.coerceIn(0.0, 1.0); val c1 = 1.70158; val c3 = c1 + 1
        return 1 + c3 * (t - 1) * (t - 1) * (t - 1) + c1 * (t - 1) * (t - 1)
    }

    /** Scores always show at least six digits (000637) so the HUD keeps a steady width. */
    fun digits(score: Int) = "%06d".format(max(0, score))
}

/**
 * Toy-like surface: gradient fill, ink outline, top highlight and a hard offset shadow.
 * The bottom [depth] pixels of the bounds hold the shadow; when pressed the face sinks onto it.
 */
class ClayDrawable(
    private val top: Int, private val fill: Int, private val radius: Float,
    private val depth: Float, private val border: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val corner = RectF()
    private val highlight = Path()
    private var gradient: LinearGradient? = null
    private var gradientTop = Float.NaN
    private var gradientBottom = Float.NaN
    private var pressed = false
    /** Forces the pressed look for views that track presses themselves. */
    var held = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_pressed)
        if (now == pressed) return false
        pressed = now; invalidateSelf(); return true
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val face = b.height() - depth
        if (face <= 0 || b.width() <= 0) return
        val r = min(radius, min(b.width() / 2f, face / 2f))
        val shift = if (pressed || held) depth - 1 else 0f
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = Clay.ink
        rect.set(b.left.toFloat(), b.top + depth, b.right.toFloat(), b.top + depth + face)
        c.drawRoundRect(rect, r, r, paint)

        rect.set(b.left.toFloat(), b.top + shift, b.right.toFloat(), b.top + shift + face)
        if (gradient == null || gradientTop != rect.top || gradientBottom != rect.bottom) {
            gradient = LinearGradient(0f, rect.top, 0f, rect.bottom, top, fill, Shader.TileMode.CLAMP)
            gradientTop = rect.top; gradientBottom = rect.bottom
        }
        paint.shader = gradient
        c.drawRoundRect(rect, r, r, paint)
        paint.shader = null

        // A soft highlight around the top-left corner sells the moulded-plastic look.
        val inset = border * 1.5f
        val hr = max(1f, r - inset)
        corner.set(rect.left + inset, rect.top + inset, rect.left + inset + hr * 2, rect.top + inset + hr * 2)
        highlight.reset()
        highlight.moveTo(rect.left + inset, rect.top + inset + hr)
        highlight.arcTo(corner, 180f, 90f, false)
        highlight.lineTo(max(rect.left + inset + hr, rect.centerX()), rect.top + inset)
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = border * 0.67f; paint.color = Clay.alpha(Color.WHITE, .22f)
        c.drawPath(highlight, paint)

        paint.strokeWidth = border; paint.color = Clay.ink
        rect.inset(border / 2, border / 2)
        c.drawRoundRect(rect, r - border / 2, r - border / 2, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Capsule hull meter with quarter ticks: each rock costs one quarter. */
class HullBar(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shown = 1f
    private var color = Clay.mint
    private var animator: ValueAnimator? = null
    var animate = true

    fun set(health: Int, tint: Int) {
        val target = health / 100f
        if (tint != color) { color = tint; invalidate() }
        if (target == shown && animator?.isRunning != true) return
        animator?.cancel()
        if (!animate || !isAttachedToWindow) { shown = target; invalidate(); return }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 350
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        paint.shader = null; paint.color = Clay.alpha(Clay.ink, .75f)
        rect.set(0f, 0f, w, h); c.drawRoundRect(rect, h / 2, h / 2, paint)
        val pad = 2 * resources.displayMetrics.density
        val inner = w - pad * 2
        val fillWidth = if (shown > 0) max(h - pad * 2, inner * shown) else 0f
        if (fillWidth > 0) {
            rect.set(pad, pad, pad + fillWidth, h - pad)
            paint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, Clay.alpha(color, .8f), color, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
            paint.shader = null
        }
        paint.color = Clay.alpha(Clay.ink, .7f)
        val tick = 2 * resources.displayMetrics.density
        for (i in 1..3) {
            val x = pad + inner * i / 4f
            c.drawRect(x - tick / 2, 0f, x + tick / 2, h, paint)
        }
    }
}

/** A rounded fill bar: the Kraken's health during the fight, and the intro's skip progress. */
class JourneyTrack(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var fraction = 0f
        set(value) { if (field != value) { field = value; invalidate() } }
    var tint = Clay.gold
        set(value) { if (field != value) { field = value; invalidate() } }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat(); val d = resources.displayMetrics.density
        paint.style = Paint.Style.FILL; paint.color = Clay.alpha(Clay.ink, .75f)
        rect.set(0f, 0f, w, h); c.drawRoundRect(rect, h / 2, h / 2, paint)
        val pad = 2 * d
        if (fraction > 0) {
            rect.set(pad, pad, pad + max(8 * d, (w - pad * 2) * fraction), h - pad)
            paint.color = tint; c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        }
    }
}
