package com.parthpahariya.splashdash

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
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
                notice.text = message; noticeUntil = System.currentTimeMillis() + 1600
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
    private fun addControl(row: LinearLayout, view: View, weight: Float = 1f) {
        row.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(dp(4), 0, dp(4), 0) })
    }
    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(ink)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(dp(12) + insets.systemWindowInsetLeft, dp(8) + insets.systemWindowInsetTop, dp(12) + insets.systemWindowInsetRight, dp(8) + insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = row()
        header.addView(label(19f).apply { text = "WAKE RUNNER"; setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        soundButton = button(if (sound) "Sound on" else "Sound off") {
            sound = !sound; prefs.edit().putBoolean("sound", sound).apply()
            soundButton.text = if (sound) "Sound on" else "Sound off"
            soundButton.contentDescription = "Sound ${if (sound) "on" else "off"}"
        }
        header.addView(soundButton)
        header.addView(button("Help") { showHelp() })
        root.addView(header)
        stats = label(14f).apply { typeface = Typeface.MONOSPACE }
        root.addView(stats)
        val body = LinearLayout(this).apply { orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val field = FrameLayout(this)
        water = GameView(this, game).apply {
            reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            background = GradientDrawable().apply { setColor(Color.rgb(39, 127, 137)); cornerRadius = dp(16).toFloat() }
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        field.addView(water, FrameLayout.LayoutParams(-1, -1))
        overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(220, 16, 44, 52)) }
        val scroller = ScrollView(this).apply { isFillViewport = true }
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        overlayTitle = label(32f).apply {
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            if (android.os.Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
        }
        overlayCopy = label(16f).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(20)) }
        startButton = button("Set sail", true) { if (game.status == Status.PAUSED) game.pause() else game.reset(); updateHUD() }
        center.addView(overlayTitle); center.addView(overlayCopy)
        center.addView(startButton, LinearLayout.LayoutParams(-1, -2))
        scroller.addView(center); overlay.addView(scroller); field.addView(overlay)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        notice = label(13f, gold).apply { text = "Swipe to steer · tap a pirate to aim"; gravity = Gravity.CENTER }
        controls.addView(notice)
        val steering = row()
        leftButton = button("Left") { game.move(-1) }.apply { contentDescription = "Steer left" }
        rightButton = button("Right") { game.move(1) }.apply { contentDescription = "Steer right" }
        addControl(steering, leftButton); addControl(steering, rightButton)
        controls.addView(steering)
        val actions = row()
        fireButton = button("Fire", true) { if (!fireTouch) game.fire(); fireTouch = false }.apply {
            contentDescription = "Fire cannon. Hold for continuous fire."
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { fireTouch = true; game.firing = true; game.fire() }
                    MotionEvent.ACTION_UP -> game.firing = false
                    MotionEvent.ACTION_CANCEL -> { game.firing = false; fireTouch = false }
                }
                false
            }
        }
        jumpButton = button("Jump") { game.jump(); updateHUD() }
        pauseButton = button("Pause") { game.pause(); updateHUD() }
        addControl(actions, fireButton); addControl(actions, jumpButton); addControl(actions, pauseButton)
        controls.addView(actions)
        if (body.orientation == LinearLayout.HORIZONTAL) {
            body.addView(field, LinearLayout.LayoutParams(0, -1, 1f))
            val controlScroll = ScrollView(this).apply { addView(controls); isFillViewport = true }
            body.addView(controlScroll, LinearLayout.LayoutParams(dp(240), -1))
            controls.gravity = Gravity.CENTER
        } else {
            body.addView(field, LinearLayout.LayoutParams(-1, 0, 1f))
            body.addView(controls, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root); root.requestApplyInsets(); lastStatus = null; updateHUD()
    }
    private fun updateHUD() {
        stats.text = "Score %06d  ·  Best %d\nHull %d%%  ·  Jumps %d  ·  Level %d".format(game.score, best, game.health, game.jumps, game.level)
        val playing = game.status == Status.PLAYING
        leftButton.isEnabled = playing; rightButton.isEnabled = playing; fireButton.isEnabled = playing
        jumpButton.isEnabled = playing && game.jumps > 0 && !game.jumping
        jumpButton.text = "Jump (${game.jumps})"
        pauseButton.isEnabled = playing || game.status == Status.PAUSED
        pauseButton.text = if (game.status == Status.PAUSED) "Resume" else "Pause"
        for (button in listOf(leftButton, rightButton, fireButton, jumpButton, pauseButton)) {
            button.alpha = if (button.isEnabled) 1f else .45f
        }
        overlay.visibility = if (playing) View.GONE else View.VISIBLE
        if (lastStatus != game.status) {
            when (game.status) {
                Status.READY -> {
                    overlayTitle.text = "A little boat.\nA big adventure."
                    overlayCopy.text = "Dodge rocks. Sink pirates.\nCollect hearts and jump over danger.\n\nStart with 100 hull and two jumps."
                    startButton.text = "Set sail"
                }
                Status.PAUSED -> { overlayTitle.text = "Take a breather."; overlayCopy.text = "Your voyage is right where you left it."; startButton.text = "Back to the water" }
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
        if (noticeUntil != 0L && System.currentTimeMillis() > noticeUntil) { notice.text = "Swipe to steer · tap a pirate to aim"; noticeUntil = 0 }
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
