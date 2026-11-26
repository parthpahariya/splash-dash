package com.boringman.splashdash

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*

/**
 * A rewarded ad that continues a finished voyage. [onReward] runs only when the ad was watched
 * to the end; closing early calls [onClose] alone. Replace [PlaceholderRewardedAds] with a real
 * ad network here (and PlaceholderAdView in WakeRunnerApp.swift on iOS).
 */
interface RewardedAds {
    fun show(activity: Activity, onReward: () -> Unit, onClose: () -> Unit)
}

/** Stand-in ad: a full-screen countdown, matching the iOS PlaceholderAdView. */
class PlaceholderRewardedAds(private val seconds: Int = 5) : RewardedAds {
    override fun show(activity: Activity, onReward: () -> Unit, onClose: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun text(size: Float, color: Int, weight: Int) = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = Clay.font(weight)
            includeFontPadding = false; gravity = Gravity.CENTER
        }
        fun clay(top: Int, fill: Int, radius: Int) =
            ClayDrawable(top, fill, radius * density, Clay.DEPTH_DP * density, Clay.BORDER_DP * density)
        fun asButton(view: View) {
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
                }
            }
        }
        val depth = dp(Clay.DEPTH_DP.toInt())
        val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
        val handler = Handler(Looper.getMainLooper())
        var remaining = seconds
        var rewarded = false

        val badge = text(12f, Clay.ink, 900).apply {
            text = "AD"; letterSpacing = .12f
            background = GradientDrawable().apply { setColor(Clay.gold); cornerRadius = 999f }
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val close = FrameLayout(activity).apply {
            addView(ImageView(activity).apply { setImageDrawable(ControlIcon("xmark", Clay.cream)) },
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER).apply { bottomMargin = depth })
            background = clay(Clay.surfaceTop, Clay.surface, 22)
            isClickable = true; isFocusable = true; contentDescription = "Close ad without reward"; asButton(this)
            setOnClickListener { dialog.dismiss() }
        }
        val top = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(badge)
            addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(close, LinearLayout.LayoutParams(dp(44), dp(44) + depth))
        }
        val ring = CountdownRing(activity, seconds)
        val title = text(22f, Clay.cream, 900).apply { text = "Placeholder ad" }
        val subtitle = text(15f, Clay.creamMuted, 700).apply { text = "A real rewarded ad will play here." }
        val rewardLabel = text(20f, Clay.ink, 900)
        val reward = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            addView(ImageView(activity).apply { setImageDrawable(ControlIcon("play", Clay.ink)) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(rewardLabel, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            background = clay(Clay.goldTop, Clay.gold, 22)
            minimumHeight = dp(60) + depth
            setPadding(dp(14), dp(8), dp(14), dp(8) + depth)
            isClickable = true; isFocusable = true; asButton(this)
            setOnClickListener { rewarded = true; dialog.dismiss() }
        }
        fun refresh() {
            ring.remaining = remaining
            val done = remaining <= 0
            rewardLabel.text = if (done) "Continue voyage" else "Reward in ${remaining}s"
            reward.contentDescription = rewardLabel.text
            reward.isEnabled = done; reward.alpha = if (done) 1f else .45f
        }
        val tick = object : Runnable {
            override fun run() {
                if (remaining <= 0) return
                remaining--; refresh()
                if (remaining > 0) handler.postDelayed(this, 1000)
                else reward.announceForAccessibility("Reward ready")
            }
        }
        refresh()

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Clay.ink)
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(Space(activity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(ring, LinearLayout.LayoutParams(dp(140), dp(140)))
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
            addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            addView(Space(activity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(reward, LinearLayout.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).let { Rect(it.left, it.top, it.right, it.bottom) }
                else @Suppress("DEPRECATION") Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                v.setPadding(bars.left + dp(20), bars.top + dp(20), bars.right + dp(20), bars.bottom + dp(20)); insets
            }
        }
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Clay.ink))
        dialog.setOnDismissListener {
            handler.removeCallbacks(tick)
            if (rewarded) onReward() else onClose()
        }
        dialog.show()
        handler.postDelayed(tick, 1000)
    }
}

/** Gold progress ring with the seconds left, or a check mark once the reward is ready. */
private class CountdownRing(context: Context, private val total: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val box = RectF()
    var remaining = total
        set(value) { field = value; invalidate(); contentDescription = if (value > 0) "$value seconds left" else "Reward ready" }

    override fun onDraw(c: Canvas) {
        val stroke = 10 * resources.displayMetrics.density
        box.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke
        paint.color = Clay.surface; c.drawOval(box, paint)
        paint.color = Clay.gold
        c.drawArc(box, -90f, 360f * (total - remaining) / total, false, paint)
        if (remaining > 0) {
            paint.style = Paint.Style.FILL; paint.color = Clay.cream
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 48f, resources.displayMetrics); paint.textAlign = Paint.Align.CENTER
            paint.typeface = Clay.font(900)
            c.drawText("$remaining", width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        } else {
            val icon = ControlIcon("check", Clay.gold)
            val s = (width * .4f).toInt()
            icon.setBounds((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
            icon.draw(c)
        }
    }
}
