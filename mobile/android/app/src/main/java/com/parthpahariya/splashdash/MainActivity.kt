package com.parthpahariya.splashdash

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlin.math.max

class MainActivity : Activity(), Choreographer.FrameCallback {
    private val game = Game()
    private val prefs by lazy { getSharedPreferences("wake-runner", MODE_PRIVATE) }
    private var best = 0
    private var sound = false
    private var tone: ToneGenerator? = null
    private var active = false
    private var previousFrame = 0L
    private var lastHUD = 0L
    private var lastStatus: Status? = null
    private lateinit var water: GameView
    private lateinit var stats: TextView
    private lateinit var healthText: TextView
    private lateinit var healthBar: ProgressBar
    private lateinit var levelText: TextView
    private lateinit var hud: LinearLayout
    private lateinit var controls: FrameLayout
    private lateinit var notice: TextView
    private lateinit var overlay: FrameLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlayCopy: TextView
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var soundButton: Button
    private lateinit var leftButton: Button
    private lateinit var rightButton: Button
    private lateinit var fireButton: Button
    private lateinit var jumpButton: Button
    private var noticeUntil = 0L
    private var fireTouch = false
    private val ink = Color.rgb(16, 44, 52)
    private val cream = Color.rgb(248, 238, 214)
    private val gold = Color.rgb(242, 201, 130)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        best = prefs.getInt("best", 0); sound = prefs.getBoolean("sound", false)
        // Recreating a process restores a safe menu, never a partially running frame.
        game.onEvent = { event ->
            if (event == "over") saveBest()
            val message = when (event) {
                "damage" -> "Hull hit — change lanes!"
                "jump" -> "Catching some air"
                "heart" -> "+25 hull · +50 points"
                "pickupJump" -> "Jump boost +1"
                "sink" -> "Pirate sunk · +250 points"
                "level" -> "Level ${game.level} · rougher waters"
                else -> null
            }
            if (message != null && ::notice.isInitialized) {
                notice.text = message; notice.visibility = View.VISIBLE; noticeUntil = System.currentTimeMillis() + 1600
                if (event == "damage" || event == "sink") notice.announceForAccessibility(message)
            }
            if (sound && event in listOf("fire", "damage", "heart", "jump", "sink")) {
                if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 35) }.getOrNull()
                tone?.startTone(if (event == "damage") ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_BEEP, 70)
            }
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        buildUI()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(size: Float, color: Int = cream) = TextView(this).apply {
        textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    private fun button(title: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = title; textSize = 14f; isAllCaps = false
        minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(if (primary) ink else cream)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) gold else Color.rgb(40, 74, 82))
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun panel(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun thumbButton(title: String, symbol: String, primary: Boolean = false, action: () -> Unit): Button {
        return button(title, primary, action).apply {
            textSize = 12f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (primary) gold else Color.rgb(25, 62, 72))
                setStroke(dp(2), if (primary) gold else Color.rgb(120, 155, 158))
            }
            backgroundTintList = null
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(90, 248, 238, 214)), shape, null)
            val iconSize = dp(if (symbol == "jump") 20 else 24)
            val icon = ControlIcon(symbol, if (primary) ink else cream).apply { setBounds(0, 0, iconSize, iconSize) }
            if (title.isEmpty()) { textSize = 0f; includeFontPadding = false }
            setCompoundDrawables(null, icon, null, null)
            setOnClickListener {
                if (!primary) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
            compoundDrawablePadding = dp(2)
        }
    }
    private fun buildUI() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val sideInset = if (landscape) dp(160) else 0
        val bottomInset = if (landscape) dp(16) else dp(112)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(39, 127, 137)) }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        water = GameView(this, game).apply {
            reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
        root.addView(water, FrameLayout.LayoutParams(-1, -1))
        val safe = FrameLayout(this)
        root.addView(safe, FrameLayout.LayoutParams(-1, -1))

        hud = row().apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = panel(Color.argb(240, 16, 44, 52))
            gravity = Gravity.TOP
        }
        val scoreStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scoreStack.addView(label(10f).apply { text = "SCORE"; letterSpacing = .15f; setTypeface(null, Typeface.BOLD) })
        stats = label(23f).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
        scoreStack.addView(stats)
        val hull = row()
        healthBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(Color.rgb(140, 227, 205))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        hull.addView(healthBar, LinearLayout.LayoutParams(dp(64), dp(10)))
        healthText = label(12f, Color.rgb(140, 227, 205)).apply { setTypeface(null, Typeface.BOLD) }
        hull.addView(healthText); scoreStack.addView(hull)
        hud.addView(scoreStack, LinearLayout.LayoutParams(0, -2, 1f))
        levelText = label(11f, gold).apply { setTypeface(null, Typeface.BOLD); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        hud.addView(levelText)
        pauseButton = thumbButton("", "pause") { game.pause(); updateHUD() }.apply { contentDescription = "Pause" }
        hud.addView(pauseButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        safe.addView(hud, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(8), dp(12), 0) })

        notice = label(14f, gold).apply {
            text = ""; visibility = View.GONE; gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = panel(Color.argb(245, 16, 44, 52))
        }
        safe.addView(notice, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(112) })
        controls = FrameLayout(this)
        val steering = row().apply { gravity = Gravity.BOTTOM }
        leftButton = thumbButton("", "left") { game.move(-1) }.apply { contentDescription = "Steer left" }
        rightButton = thumbButton("", "right") { game.move(1) }.apply { contentDescription = "Steer right" }
        steering.addView(leftButton, LinearLayout.LayoutParams(dp(56), dp(56)))
        steering.addView(rightButton, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(8) })
        controls.addView(steering, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT))
        val actions = row().apply { gravity = Gravity.BOTTOM }
        jumpButton = thumbButton("2", "jump") { game.jump(); updateHUD() }.apply { contentDescription = "Jump (2)" }
        fireButton = thumbButton("FIRE", "fire", true) { if (!fireTouch) game.fire(); fireTouch = false }.apply {
            contentDescription = "Fire cannon. Hold for continuous fire."
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); fireTouch = true; game.firing = true; game.fire() }
                    MotionEvent.ACTION_UP -> game.firing = false
                    MotionEvent.ACTION_CANCEL -> { game.firing = false; fireTouch = false }
                }
                false
            }
        }
        actions.addView(jumpButton, LinearLayout.LayoutParams(dp(56), dp(56)))
        actions.addView(fireButton, LinearLayout.LayoutParams(dp(76), dp(76)).apply { leftMargin = dp(12) })
        controls.addView(actions, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT))
        safe.addView(controls, FrameLayout.LayoutParams(-1, dp(88), Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(12)) })

        overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(235, 16, 44, 52)) }
        val scroller = ScrollView(this).apply { isFillViewport = true }
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        overlayTitle = label(40f).apply {
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            if (android.os.Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
        }
        overlayCopy = label(16f).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(24)) }
        startButton = button("Set sail", true) { if (game.status == Status.PAUSED) game.pause() else game.reset(); updateHUD() }
        center.addView(overlayTitle); center.addView(overlayCopy)
        center.addView(startButton, LinearLayout.LayoutParams(-1, dp(56)))
        val options = row()
        soundButton = button(if (sound) "Sound on" else "Sound off") {
            sound = !sound; prefs.edit().putBoolean("sound", sound).apply()
            soundButton.text = if (sound) "Sound on" else "Sound off"
        }
        options.addView(soundButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        options.addView(button("How to play") { showHelp() }, LinearLayout.LayoutParams(0, -2, 1f))
        center.addView(options, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        center.addView(label(13f, gold).apply { text = "Swipe to steer · hold Fire to shoot\nTap a pirate to aim · Jump to evade"; gravity = Gravity.CENTER })
        scroller.addView(center); overlay.addView(scroller); root.addView(overlay)
        var safeTop = 0
        hud.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom > top) {
                water.contentInsets.top = safeTop + bottom - top + dp(16)
                water.invalidate()
            }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                Rect(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            safeTop = bars.top
            safe.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            overlay.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            water.contentInsets = Rect(bars.left + sideInset, bars.top + dp(104), bars.right + sideInset, bars.bottom + bottomInset)
            water.invalidate()
            insets
        }
        setContentView(root); root.requestApplyInsets(); lastStatus = null; updateHUD()
    }
    private fun updateHUD() {
        stats.text = "%,d".format(game.score)
        stats.contentDescription = "Score ${game.score}"
        healthText.text = "${game.health}%"
        healthText.contentDescription = "Hull ${game.health} percent"
        healthBar.progress = game.health
        healthBar.progressTintList = ColorStateList.valueOf(if (game.health <= 25) Color.rgb(255, 160, 180) else Color.rgb(140, 227, 205))
        levelText.text = "LEVEL ${game.level}"
        val playing = game.status == Status.PLAYING
        leftButton.isEnabled = playing; rightButton.isEnabled = playing; fireButton.isEnabled = playing
        jumpButton.isEnabled = playing && game.jumps > 0 && !game.jumping
        jumpButton.text = "${game.jumps}"
        jumpButton.contentDescription = "Jump (${game.jumps})"
        pauseButton.isEnabled = playing || game.status == Status.PAUSED
        hud.visibility = if (playing) View.VISIBLE else View.GONE
        controls.visibility = if (playing) View.VISIBLE else View.GONE
        if (!playing) notice.visibility = View.GONE
        for (button in listOf(leftButton, rightButton, fireButton, jumpButton, pauseButton)) {
            button.alpha = if (button.isEnabled) 1f else .45f
        }
        overlay.visibility = if (playing) View.GONE else View.VISIBLE
        if (lastStatus != game.status) {
            when (game.status) {
                Status.READY -> {
                    overlayTitle.text = "WAKE\nRUNNER"
                    overlayCopy.text = "Dodge rocks. Sink pirates.\nMake every voyage count.\n\nPERSONAL BEST  $best"
                    startButton.text = "Set sail"
                }
                Status.PAUSED -> { overlayTitle.text = "PAUSED"; overlayCopy.text = "Your voyage is right where you left it.\n\nPERSONAL BEST  $best"; startButton.text = "Back to the water" }
                Status.OVER -> {
                    overlayTitle.text = "What a voyage."
                    overlayCopy.text = "Score ${game.score}\n${game.distance.toInt()} m sailed · ${game.piratesSunk} pirates sunk\nBest $best"
                    startButton.text = "One more voyage"
                }
                else -> Unit
            }
            if (game.status == Status.OVER || game.status == Status.PAUSED) overlayTitle.announceForAccessibility(overlayTitle.text)
            lastStatus = game.status
        }
        if (noticeUntil != 0L && System.currentTimeMillis() > noticeUntil) { notice.text = ""; notice.visibility = View.GONE; noticeUntil = 0 }
        water.invalidate()
    }
    private fun showHelp() {
        if (game.status == Status.PLAYING) game.pause()
        updateHUD()
        AlertDialog.Builder(this).setTitle("Captain’s handbook")
            .setMessage("Use Left and Right or swipe across the water to steer.\n\nHold Fire to shoot ahead. Tap a pirate to aim across lanes; two hits sink it for 250 points.\n\nJump clears rocks, ships and cannonballs for 1.15 seconds. Blue pickups add a jump, up to five.\n\nHearts repair 25 hull. Rocks cost 25 hull, cannonballs 15, ships 30.\n\nEvery 300 meters the water gets faster. Sound starts off. Your best score is saved on this device.")
            .setPositiveButton("Got it", null).show()
    }
    private fun saveBest() { best = max(best, game.score); prefs.edit().putInt("best", best).apply() }
    override fun onResume() { super.onResume(); active = true; previousFrame = 0; Choreographer.getInstance().postFrameCallback(this) }
    override fun onPause() {
        active = false; Choreographer.getInstance().removeFrameCallback(this)
        if (game.status == Status.PLAYING) game.pause()
        game.firing = false; saveBest(); updateHUD(); super.onPause()
    }
    override fun onDestroy() { tone?.release(); tone = null; super.onDestroy() }
    override fun onConfigurationChanged(config: Configuration) { super.onConfigurationChanged(config); game.firing = false; buildUI() }
    override fun doFrame(frameTimeNanos: Long) {
        if (!active) return
        val dt = if (previousFrame == 0L) 0.0 else (frameTimeNanos - previousFrame) / 1e9
        previousFrame = frameTimeNanos; game.update(dt)
        if (game.status == Status.PLAYING) water.invalidate()
        if ((game.status == Status.PLAYING && frameTimeNanos - lastHUD > 100_000_000) || lastStatus != game.status || (noticeUntil != 0L && System.currentTimeMillis() > noticeUntil)) {
            updateHUD(); lastHUD = frameTimeNanos
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> game.move(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> game.move(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (event.repeatCount == 0) game.jump()
            KeyEvent.KEYCODE_SPACE -> { game.firing = true; game.fire() }
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_P -> { if (event.repeatCount == 0) game.pause() }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SPACE) { game.firing = false; return true }
        return super.onKeyUp(keyCode, event)
    }
}
