package com.boringman.splashdash

import android.app.Activity
import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import kotlin.math.max
import kotlin.math.min

/** Mirrors the SwiftUI GameScreen in WakeRunnerApp.swift: clay HUD, menu card and handbook. */
class MainActivity : Activity(), Choreographer.FrameCallback {
    private val game = Game()
    /** Swap for a real ad network; see RewardedAd.kt. */
    private val ads: RewardedAds by lazy {
        PlaceholderRewardedAds(isAvailable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }
    /** Back pauses a running voyage instead of leaving the app (Android 13+ uses this callback). */
    private var backCallback: OnBackInvokedCallback? = null
    private val prefs by lazy { getSharedPreferences("wake-runner", MODE_PRIVATE) }
    private var best = 0
    private var sound = false
    /** Treasures claimed by beating the Kraken, kept on this device. */
    private var tone: ToneGenerator? = null
    private var active = false
    private var previousFrame = 0L
    private var lastHUD = 0L
    private var lastStatus: Status? = null
    private var lastHullIcon = ""
    private var noticeUntil = 0L
    private var fireTouch = false
    private var reducedMotion = false
    private lateinit var water: GameView
    private lateinit var hud: LinearLayout
    private lateinit var hullCard: LinearLayout
    private lateinit var hullIcon: ImageView
    private lateinit var hullText: TextView
    private lateinit var hullBar: HullBar
    private lateinit var scoreCard: LinearLayout
    private lateinit var levelText: TextView
    private lateinit var scoreText: TextView
    private lateinit var toast: LinearLayout
    private lateinit var toastIcon: ImageView
    private lateinit var toastText: TextView
    private lateinit var fireButton: LinearLayout
    private lateinit var fireFace: ClayDrawable
    private lateinit var menu: FrameLayout
    private lateinit var menuCard: LinearLayout
    private lateinit var journey: LinearLayout
    private lateinit var journeyStart: ImageView
    private lateinit var journeyLabel: TextView
    private lateinit var journeyTrack: JourneyTrack
    private lateinit var introSkip: View
    private lateinit var introUi: FrameLayout
    /** The treasure sequence plays once when a voyage is won, then gives way to the score screen. */
    private lateinit var treasureLayer: FrameLayout
    private lateinit var treasureContent: LinearLayout
    private var showingTreasure = false
    private lateinit var skipProgress: JourneyTrack
    private lateinit var sailBanner: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        best = prefs.getInt("best", 0); sound = prefs.getBoolean("sound", false)
        // Recreating a process restores a safe menu, never a partially running frame.
        game.onEvent = { event ->
            if (event == "over") saveBest()
            when (event) {
                "damage" -> notice("Hull hit — change lanes!", "warning", Clay.danger)
                "jump" -> notice("Catching some air", "arrow.up", Clay.gold)
                "heart" -> notice("+10 hull · +50 points", "heart", Clay.heart)
                "sink" -> notice("${game.lastSunkName} sunk · +${game.lastSunkReward} points", "flag", Clay.gold)
                "level" -> notice("Level ${game.level} · rougher waters", "wind", Clay.mint)
                "revive" -> notice("Second wind · ${Game.REVIVE_HEALTH} hull", "heart", Clay.mint)
                "kraken" -> notice("The Kraken rises! Fire at its head", "warning", Clay.danger)
                "krakenRage" -> notice("The Kraken is enraged!", "warning", Clay.danger)
                "krakenDefeated" -> notice("The Kraken sinks · +${Game.TREASURE_BONUS} points", "crown", Clay.gold)
                "over" -> if (::water.isInitialized) water.announceForAccessibility("Voyage complete. Score ${game.score}.")
                "won" -> {
                    saveBest()
                    if (::water.isInitialized) water.announceForAccessibility("Treasure found! Score ${game.score}.")
                }
            }
            if (sound && event in listOf("fire", "damage", "heart", "jump", "sink")) {
                if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 35) }.getOrNull()
                tone?.startTone(if (event == "damage") ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_BEEP, 70)
            }
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        buildUI()
    }

    // Building blocks

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dpf(value: Float) = value * resources.displayMetrics.density
    private val depth get() = dp(Clay.DEPTH_DP.toInt())
    private val accessibleText get() = resources.configuration.fontScale >= 1.6f

    private fun clay(top: Int = Clay.surfaceTop, fill: Int = Clay.surface, radius: Int = 18) =
        ClayDrawable(top, fill, dpf(radius.toFloat()), dpf(Clay.DEPTH_DP), dpf(Clay.BORDER_DP))

    private fun text(size: Float, color: Int = Clay.cream, weight: Int = 800) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = Clay.font(weight)
        includeFontPadding = false
    }
    private fun icon(symbol: String, color: Int, size: Int) = ImageView(this).apply {
        setImageDrawable(ControlIcon(symbol, color))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
    private fun capsule(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dpf(999f) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dpf(radius.toFloat()) }
    private fun row(spacing: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        if (spacing > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = gap(spacing) }
    }
    private fun column(spacing: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (spacing > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = gap(spacing) }
    }
    private fun gap(size: Int) = GradientDrawable().apply { setSize(dp(size), dp(size)); setColor(Color.TRANSPARENT) }
    private fun asButton(view: View) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
            }
        }
    }

    /** Clay button: icon and label centred together, with the hard shadow below the face. */
    private fun clayButton(
        title: String, symbol: String?, primary: Boolean = false, radius: Int = 18,
        size: Float = 15f, minHeight: Int = 50, action: () -> Unit,
    ) = row(8).apply {
        gravity = Gravity.CENTER
        val ink = if (primary) Clay.ink else Clay.cream
        if (symbol != null) addView(icon(symbol, ink, (size * 1.15f).toInt()))
        addView(text(size, ink, if (primary) 900 else 800).apply { text = title; gravity = Gravity.CENTER })
        background = if (primary) clay(Clay.goldTop, Clay.gold, radius) else clay(radius = radius)
        minimumHeight = dp(minHeight) + depth
        setPadding(dp(14), dp(8), dp(14), dp(8) + depth)
        isClickable = true; isFocusable = true
        contentDescription = title
        asButton(this)
        setOnClickListener { action() }
    }

    // Screen

    private fun buildUI() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val root = FrameLayout(this).apply { setBackgroundColor(Clay.sea) }
        water = GameView(this, game).apply { reducedMotion = this@MainActivity.reducedMotion }
        root.addView(water, FrameLayout.LayoutParams(-1, -1))

        hud = column()
        val top = row(10)
        val pause = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(icon("pause", Clay.cream, 18))
            background = clay(radius = 24)
            setPadding(0, 0, 0, depth)
            isClickable = true; isFocusable = true; contentDescription = "Pause"; asButton(this)
            setOnClickListener { game.pause(); updateHUD() }
        }
        top.addView(pause, LinearLayout.LayoutParams(dp(48), dp(48) + depth))
        hullCard = buildHullCard()
        top.addView(hullCard, if (landscape) LinearLayout.LayoutParams(dp(280), -2) else LinearLayout.LayoutParams(0, -2, 1f))
        if (landscape) top.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        scoreCard = buildScoreCard()
        top.addView(scoreCard, LinearLayout.LayoutParams(-2, -2))
        hud.addView(top, LinearLayout.LayoutParams(-1, -2))
        journey = buildJourney()
        hud.addView(journey, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        toastIcon = icon("heart", Clay.gold, 17)
        toastText = text(15f, weight = 800).apply { gravity = Gravity.CENTER }
        toast = row(8).apply {
            addView(toastIcon); addView(toastText)
            background = clay(Clay.surface, Clay.deep, 22)
            setPadding(dp(16), dp(10), dp(16), dp(10) + depth)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        hud.addView(toast, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(14) })
        hud.addView(Space(this), LinearLayout.LayoutParams(-1, 0, 1f))
        fireButton = buildFire()
        hud.addView(fireButton, LinearLayout.LayoutParams(dp(84), dp(84) + depth).apply { gravity = Gravity.END })
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))

        menu = buildMenu(landscape)
        root.addView(menu, FrameLayout.LayoutParams(-1, -1))
        // During the intro the whole scene can be tapped to skip; the Skip button is the labelled control.
        introSkip = View(this).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { game.skipIntro(); updateHUD() }
            visibility = View.GONE
        }
        root.addView(introSkip, FrameLayout.LayoutParams(-1, -1))
        treasureContent = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        treasureLayer = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Clay.deep, Color.parseColor("#123A47"), Clay.deep))
            isClickable = true
            addView(treasureContent, FrameLayout.LayoutParams(min(dp(460), resources.displayMetrics.widthPixels), -1, Gravity.CENTER_HORIZONTAL))
            visibility = View.GONE
        }
        root.addView(treasureLayer, FrameLayout.LayoutParams(-1, -1))
        introUi = buildIntro()
        root.addView(introUi, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                Rect(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            val side = dp(if (landscape) 24 else 16)
            hud.setPadding(bars.left + side, bars.top + dp(8), bars.right + side, bars.bottom + dp(12))
            menu.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            introUi.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            treasureLayer.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            val big = resources.configuration.fontScale >= 1.3f
            water.contentInsets = Rect(
                bars.left, bars.top + dp(if (big) 112 else 100),
                bars.right + if (landscape) dp(112) else 0, bars.bottom + dp(if (landscape) 16 else 116))
            water.invalidate()
            insets
        }
        setContentView(root)
        // The window's insets controller only exists once the content view (and decor) is installed.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        root.requestApplyInsets(); lastStatus = null; lastHullIcon = ""; noticeUntil = 0; updateHUD()
    }

    /**
     * Intro UI: the scene itself can be tapped to skip, plus a labelled clay Skip button that
     * fills as the intro plays, and a gold "Set sail!" banner as the boat heads out to sea.
     */
    private fun buildIntro(): FrameLayout {
        skipProgress = JourneyTrack(this).apply { showBoat = false; tint = Clay.gold }
        val skip = column(4).apply {
            gravity = Gravity.CENTER
            addView(row(6).apply {
                addView(text(15f, weight = 800).apply { text = "Skip" })
                addView(icon("forward", Clay.cream, 13))
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            // Progress through the intro, so players know how long is left.
            addView(skipProgress, LinearLayout.LayoutParams(dp(54), dp(6)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            background = clay(radius = 22)
            minimumHeight = dp(48) + depth
            setPadding(dp(18), dp(6), dp(18), dp(6) + depth)
            isClickable = true; isFocusable = true; asButton(this)
            contentDescription = "Skip intro and set sail"
            setOnClickListener { game.skipIntro(); updateHUD() }
        }
        sailBanner = row(10).apply {
            gravity = Gravity.CENTER
            addView(icon("sailboat", Clay.ink, 26))
            addView(text(28f, Clay.ink, 900).apply { text = "Set sail!" })
            background = clay(Clay.goldTop, Clay.gold, 24)
            setPadding(dp(26), dp(14), dp(26), dp(14) + depth)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            translationY = -dpf(90f)
            visibility = View.INVISIBLE
        }
        return FrameLayout(this).apply {
            addView(sailBanner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(16), dp(8), dp(16), 0) })
            visibility = View.GONE
        }
    }
    private fun updateIntro() {
        val p = (1 - game.introTime / Game.INTRO_DURATION).coerceIn(0.0, 1.0)
        skipProgress.fraction = p.toFloat()
        // The banner pops in once the land has mostly scrolled away, so it never covers the ✗.
        val t = ((p - Game.INTRO_LAND_SHARE - .3) / .12).coerceIn(0.0, 1.0)
        if (t <= 0) { sailBanner.visibility = View.INVISIBLE; return }
        val pop = (if (reducedMotion) 1.0 else Clay.easeOutBack(t)).toFloat()
        sailBanner.visibility = View.VISIBLE
        sailBanner.scaleX = pop; sailBanner.scaleY = pop; sailBanner.alpha = min(1.0, t * 1.5).toFloat()
    }

    /** Harbour-to-treasure progress; during the fight it becomes the Kraken's health bar. */
    private fun buildJourney(): LinearLayout {
        journeyStart = icon("flag", Clay.creamMuted, 14)
        journeyLabel = text(11f, weight = 900).apply { text = "KRAKEN"; letterSpacing = .11f; visibility = View.GONE }
        journeyTrack = JourneyTrack(this)
        return row(8).apply {
            addView(journeyStart); addView(journeyLabel)
            addView(journeyTrack, LinearLayout.LayoutParams(0, dp(14), 1f))
            addView(icon("chest", Clay.gold, 15))
            background = clay(Clay.surface, Clay.deep, 16)
            setPadding(dp(12), dp(6), dp(12), dp(6) + depth)
            isFocusable = true
        }
    }
    private fun updateJourney() {
        val k = game.kraken
        if (k == null) {
            journeyTrack.fraction = game.journeyProgress.toFloat(); journeyTrack.tint = Clay.gold; journeyTrack.showBoat = true
            journeyLabel.visibility = View.GONE
            journey.contentDescription = "Journey ${(game.journeyProgress * 100).toInt()} percent to the treasure"
        } else {
            journeyTrack.fraction = max(0, k.hp).toFloat() / Game.KRAKEN_HP
            journeyTrack.tint = if (k.enraged) Clay.danger else Clay.mint; journeyTrack.showBoat = false
            journeyLabel.visibility = View.VISIBLE
            journey.contentDescription = "Kraken health ${max(0, k.hp)} of ${Game.KRAKEN_HP}"
        }
        val start = if (k == null) "flag" else "warning"
        if (journeyStart.tag != start) {
            journeyStart.setImageDrawable(ControlIcon(start, if (k == null) Clay.creamMuted else Clay.danger)); journeyStart.tag = start
        }
    }

    private fun buildHullCard(): LinearLayout {
        hullIcon = icon("heart", Clay.mint, 14)
        hullText = text(13f, weight = 900).apply { fontFeatureSettings = "tnum" }
        hullBar = HullBar(this).apply { animate = !reducedMotion }
        return column(5).apply {
            addView(row(5).apply { addView(hullIcon); addView(hullText) })
            addView(hullBar, LinearLayout.LayoutParams(-1, dp(10)))
            gravity = Gravity.CENTER_VERTICAL
            background = clay()
            setPadding(dp(12), dp(8), dp(12), dp(8) + depth)
            minimumHeight = dp(48) + depth
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
        }
    }

    private fun buildScoreCard(): LinearLayout {
        levelText = text(11f, Clay.gold, 900).apply { letterSpacing = .11f; gravity = Gravity.END }
        scoreText = text(20f, weight = 900).apply { fontFeatureSettings = "tnum"; gravity = Gravity.END; setSingleLine() }
        return column(1).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(levelText, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
            addView(scoreText, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
            background = clay()
            setPadding(dp(12), dp(6), dp(12), dp(6) + depth)
            minimumWidth = dp(84); minimumHeight = dp(48) + depth
            isFocusable = true
        }
    }

    private fun buildFire(): LinearLayout {
        fireFace = clay(Clay.goldTop, Clay.goldDeep, 42)
        return column(1).apply {
            gravity = Gravity.CENTER
            addView(icon("scope", Clay.ink, 26), LinearLayout.LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(text(12f, Clay.ink, 900).apply { text = "FIRE"; letterSpacing = .12f; gravity = Gravity.CENTER })
            background = fireFace
            setPadding(0, 0, 0, depth)
            isClickable = true; isFocusable = true
            contentDescription = "Fire cannon. Hold for continuous fire."
            asButton(this)
            // Accessibility activation and keyboards send a click; touches fire from ACTION_DOWN.
            setOnClickListener { if (!fireTouch) game.fire(); fireTouch = false }
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        fireTouch = true; game.firing = true; game.fire()
                    }
                    MotionEvent.ACTION_UP -> game.firing = false
                    MotionEvent.ACTION_CANCEL -> { game.firing = false; fireTouch = false }
                }
                false
            }
        }
    }

    private fun buildMenu(landscape: Boolean): FrameLayout {
        val shell = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Clay.alpha(Clay.ink, .35f), Clay.alpha(Clay.ink, .8f)))
            isClickable = true // the water underneath stays still while a menu is up
        }
        val scroller = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val center = FrameLayout(this)
        menuCard = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = clay(Clay.surface, Clay.deep, 32)
            setPadding(dp(20), dp(20), dp(20), dp(20) + depth)
        }
        val metrics = resources.displayMetrics
        val width = min(dp(460), metrics.widthPixels - dp(32))
        center.addView(menuCard, FrameLayout.LayoutParams(width, -2, Gravity.CENTER).apply { setMargins(0, dp(24), 0, dp(24)) })
        scroller.addView(center, FrameLayout.LayoutParams(-1, -2))
        shell.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        if (landscape) scroller.isVerticalFadingEdgeEnabled = true
        return shell
    }

    private fun renderMenu() {
        val status = game.status
        menuCard.removeAllViews()
        fun add(view: View, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-2, -2)) {
            if (menuCard.childCount > 0) lp.topMargin = dp(18)
            lp.gravity = Gravity.CENTER_HORIZONTAL
            menuCard.addView(view, lp)
        }
        // The win screen opens straight on its title; the other menus keep a caption chip.
        if (status != Status.WON) add(text(12f, Clay.gold, 900).apply {
            text = when (status) {
                Status.PAUSED -> "TAKE A BREATHER"; Status.OVER -> "VOYAGE COMPLETE"
                else -> "SAIL 1,500 M TO THE TREASURE"
            }
            letterSpacing = .13f
            background = capsule(Clay.alpha(Clay.ink, .85f))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        })
        add(text(if (accessibleText) 34f else 42f, weight = 900).apply {
            text = when (status) { Status.PAUSED -> "PAUSED"; Status.OVER -> "WHAT A\nVOYAGE!"; Status.WON -> "VOYAGE\nCOMPLETE!"; else -> "WAKE\nRUNNER" }
            gravity = Gravity.CENTER
            setLineSpacing(0f, .92f)
            setShadowLayer(.01f, 0f, dpf(4f), Clay.ink)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            setPadding(0, dp(6), 0, dp(6))
        })
        if (status == Status.OVER || status == Status.WON) {
            val newBest = game.score > 0 && game.score >= best
            val results = column(14)
            val headline = column(2).apply { gravity = Gravity.CENTER_HORIZONTAL }
            if (newBest) headline.addView(row(5).apply {
                addView(icon("crown", Clay.ink, 13))
                addView(text(12f, Clay.ink, 900).apply { text = "NEW BEST"; letterSpacing = .1f })
                background = capsule(Clay.mint)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            headline.addView(text(52f, Clay.gold, 900).apply {
                text = Clay.digits(game.score); fontFeatureSettings = "tnum"; gravity = Gravity.CENTER
                setSingleLine(); contentDescription = "Score ${game.score}"
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            results.addView(headline, LinearLayout.LayoutParams(-1, -2))
            val stats = row(10)
            stats.addView(stat("waves", "${game.distance.toInt()} m", "sailed"), LinearLayout.LayoutParams(0, -2, 1f))
            stats.addView(stat("flag", "${game.piratesSunk}", "sunk"), LinearLayout.LayoutParams(0, -2, 1f))
            stats.addView(stat("heart", "${game.heartsCollected}", "hearts"), LinearLayout.LayoutParams(0, -2, 1f))
            results.addView(stats, LinearLayout.LayoutParams(-1, -2))
            add(results, LinearLayout.LayoutParams(-1, -2))
        }
        add(row(6).apply {
            addView(icon("trophy", Clay.gold, 16))
            addView(text(15f, Clay.creamMuted, 800).apply { text = "Personal best  ${Clay.digits(best)}"; fontFeatureSettings = "tnum" })
        })
        val canContinue = ads.isAvailable && game.canRevive
        if (canContinue) add(continueButton(), LinearLayout.LayoutParams(-1, -2))
        add(clayButton(
            when (status) { Status.PAUSED -> "Back to the water"; Status.OVER -> "One more voyage"; Status.WON -> "Set sail again"; else -> "Set sail" },
            when (status) { Status.PAUSED -> "play"; Status.OVER -> "restart"; else -> "sailboat" },
            // After a game over the ad continue is the main action; a fresh voyage is secondary.
            primary = !canContinue, radius = 22, size = 20f, minHeight = 60,
        ) { if (game.status == Status.PAUSED) game.pause() else game.beginVoyage(); updateHUD() }, LinearLayout.LayoutParams(-1, -2))
        val options = if (accessibleText) column(12) else row(12)
        val soundButton = clayButton(if (sound) "Sound on" else "Sound off", if (sound) "speaker.on" else "speaker.off", radius = 16) {
            sound = !sound; prefs.edit().putBoolean("sound", sound).apply(); renderMenu()
        }.apply { contentDescription = if (sound) "Turn sound off" else "Turn sound on" }
        val helpButton = clayButton("How to play", "book", radius = 16) { showHelp() }
        val share = if (accessibleText) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)
        options.addView(soundButton, share)
        options.addView(helpButton, if (accessibleText) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        add(options, LinearLayout.LayoutParams(-1, -2))
        if (status == Status.READY) add(tips(), LinearLayout.LayoutParams(-1, -2))
    }

    /** "Watch ad to continue": plays a rewarded ad, then revives this voyage (up to 5 times). */
    private fun continueButton() = column(3).apply {
        gravity = Gravity.CENTER
        addView(row(8).apply {
            gravity = Gravity.CENTER
            addView(icon("ad", Clay.ink, 23))
            addView(text(20f, Clay.ink, 900).apply { text = "Watch ad to continue" })
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(text(12f, Clay.alpha(Clay.ink, .75f), 800).apply { text = "Back in with ${Game.REVIVE_HEALTH}% hull · ${game.revivesLeft} left" },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        background = clay(Clay.goldTop, Clay.gold, 22)
        minimumHeight = dp(64) + depth
        setPadding(dp(14), dp(10), dp(14), dp(10) + depth)
        isClickable = true; isFocusable = true; asButton(this)
        contentDescription = "Watch ad to continue. Continues this voyage with ${Game.REVIVE_HEALTH} percent hull. ${game.revivesLeft} continues left."
        setOnClickListener {
            isEnabled = false
            ads.show(this@MainActivity, onReward = { game.revive(); updateHUD() }, onClose = { isEnabled = true })
        }
    }

    // Treasure sequence: Treasure Found (tap the chest), the chest opens, the letter.
    // Skip on every step goes straight to the score. Matches TreasureSequence on iOS.

    private fun finishTreasure() {
        showingTreasure = false
        treasureLayer.visibility = View.GONE
        reveal(menu, true, false, .96f)
    }

    private fun renderTreasure(step: Int) {
        val c = treasureContent
        c.removeAllViews()
        c.setPadding(dp(20), dp(8), dp(20), dp(16))
        fun spacer() = c.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        fun title(textValue: String, size: Float) = text(size, weight = 900).apply {
            text = textValue; gravity = Gravity.CENTER; setLineSpacing(0f, .92f)
            setShadowLayer(.01f, 0f, dpf(4f), Clay.ink)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        fun centered(v: View, w: Int = -2, h: Int = -2, top: Int = 0) =
            c.addView(v, LinearLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(top) })
        val skip = row().apply {
            gravity = Gravity.CENTER
            addView(text(15f, weight = 800).apply { text = "Skip" })
            background = clay(radius = 22)
            minimumHeight = dp(44) + depth
            setPadding(dp(18), dp(6), dp(18), dp(6) + depth)
            isClickable = true; isFocusable = true; asButton(this)
            contentDescription = "Skip to score"
            setOnClickListener { finishTreasure() }
        }
        c.addView(skip, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        when (step) {
            0 -> {
                centered(text(12f, Clay.gold, 900).apply {
                    text = "THE ISLAND'S SECRET"; letterSpacing = .13f
                    background = capsule(Clay.alpha(Clay.ink, .85f)); setPadding(dp(12), dp(6), dp(12), dp(6))
                }, top = 12)
                centered(title("TREASURE\nFOUND!", if (accessibleText) 34f else 46f), top = 14)
                spacer()
                val chest = TreasureRevealView(this, reducedMotion, TreasureRevealView.Mode.CLOSED).apply {
                    isClickable = true; isFocusable = true; asButton(this)
                    contentDescription = "Open the chest"
                    setOnClickListener { renderTreasure(1) }
                }
                centered(chest, -1, dp(210))
                centered(text(15f, Clay.ink, 800).apply {
                    text = "Tap the chest to open it"
                    background = GradientDrawable().apply { setColor(Clay.cream); cornerRadius = dpf(999f); setStroke(dp(2), Clay.ink) }
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, top = 10)
                spacer()
            }
            1 -> {
                centered(title("A ship in a bottle!", if (accessibleText) 26f else 30f), top = 12)
                spacer()
                centered(TreasureRevealView(this, reducedMotion, TreasureRevealView.Mode.OPENING), -1, dp(230))
                centered(text(15f, Clay.creamMuted, 700).apply { text = "…and a sealed letter tucked beside it."; gravity = Gravity.CENTER }, top = 8)
                spacer()
                c.addView(clayButton("Read the letter", "envelope", primary = true, radius = 22, size = 20f, minHeight = 60) { renderTreasure(2) },
                    LinearLayout.LayoutParams(-1, -2))
            }
            else -> {
                spacer()
                centered(TreasureRevealView(this, reducedMotion, TreasureRevealView.Mode.BOTTLE).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, -1, dp(90))
                val note = column(14).apply {
                    addView(TextView(this@MainActivity).apply {
                        text = "“Dunk this ship in water.”"; setTextColor(Clay.ink); gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "— Barbosa"; setTextColor(Clay.ink); gravity = Gravity.END
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                    }, LinearLayout.LayoutParams(-1, -2))
                    // A paper scroll; the padding clears the rolled ends and the seal on the bottom roll.
                    background = ScrollDrawable(resources.displayMetrics.density)
                    setPadding(dp(34), dp(40), dp(34), dp(52))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = "The letter reads: Dunk this ship in water. Signed, Barbosa."
                }
                centered(note, -1, -2, top = 18)
                spacer()
                c.addView(clayButton("Keep the bottle", "check", primary = true, radius = 22, size = 20f, minHeight = 60) { finishTreasure() },
                    LinearLayout.LayoutParams(-1, -2))
            }
        }
        if (!reducedMotion) { c.alpha = 0f; c.animate().alpha(1f).setDuration(250).start() }
    }

    private fun stat(symbol: String, value: String, label: String) = column(2).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(icon(symbol, Clay.mint, 16), LinearLayout.LayoutParams(dp(16), dp(16)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(text(17f, weight = 900).apply { text = value; fontFeatureSettings = "tnum"; setSingleLine(); gravity = Gravity.CENTER })
        addView(text(12f, Clay.creamMuted, 700).apply { text = label; gravity = Gravity.CENTER })
        background = clay(radius = 16)
        setPadding(dp(4), dp(10), dp(4), dp(10) + depth)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        contentDescription = "$value $label"
    }

    private fun tips(): LinearLayout {
        val items = listOf("swipe" to "Swipe to steer", "arrow.up" to "Swipe up to jump", "scope" to "Hold Fire to shoot", "tap" to "Tap a pirate to aim")
        val columns = if (accessibleText) 1 else 2
        return column(8).apply {
            background = rounded(Clay.alpha(Clay.ink, .45f), 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            for (chunk in items.chunked(columns)) addView(row(8).apply {
                for ((symbol, label) in chunk) addView(row(8).apply {
                    addView(icon(symbol, Clay.gold, 18))
                    addView(text(13f, Clay.creamMuted, 700).apply { text = label })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    // HUD state

    private fun hullColor() = when {
        game.health <= 25 -> Clay.danger
        game.health <= 50 -> Clay.gold
        else -> Clay.mint
    }

    private fun updateHUD() {
        val playing = game.status == Status.PLAYING
        val intro = game.status == Status.INTRO
        updateJourney()
        scoreText.text = Clay.digits(game.score)
        levelText.text = "LEVEL ${game.level}"
        scoreCard.contentDescription = "Score ${game.score}, level ${game.level}"
        val tint = hullColor()
        val hullSymbol = (if (game.health <= 25) "heart.slash" else "heart") + tint
        if (hullSymbol != lastHullIcon) {
            hullIcon.setImageDrawable(ControlIcon(if (game.health <= 25) "heart.slash" else "heart", tint))
            lastHullIcon = hullSymbol
        }
        hullText.text = "${game.health}%"
        hullBar.set(game.health, tint)
        hullCard.contentDescription = "Hull ${game.health} percent"
        water.importantForAccessibility = if (playing) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (lastStatus != game.status) {
            val first = lastStatus == null
            if (!playing && !intro) renderMenu()
            if (!playing) {
                game.firing = false; fireFace.held = false
                noticeUntil = 0; toast.animate().cancel(); toast.visibility = View.GONE
            }
            updateBackHandling(playing)
            reveal(hud, playing, first, 1f)
            // Plays when a voyage is won; a rotation mid-sequence (which rebuilds the screen) restarts it.
            if (game.status == Status.WON && (!first || showingTreasure)) { showingTreasure = true; renderTreasure(0) }
            if (game.status != Status.WON) { showingTreasure = false; treasureLayer.visibility = View.GONE }
            treasureLayer.visibility = if (showingTreasure) View.VISIBLE else View.GONE
            reveal(menu, !playing && !intro && !showingTreasure, first, .96f)
            introSkip.visibility = if (intro) View.VISIBLE else View.GONE
            introUi.visibility = if (intro) View.VISIBLE else View.GONE
            if (intro) updateIntro()
            if (intro) introUi.announceForAccessibility("Setting sail")
            if (game.status == Status.OVER || game.status == Status.PAUSED || game.status == Status.WON) menu.announceForAccessibility(
                when (game.status) { Status.PAUSED -> "Paused"; Status.WON -> "Treasure found!"; else -> "What a voyage!" })
            lastStatus = game.status
        }
        if (noticeUntil != 0L && System.currentTimeMillis() > noticeUntil) { noticeUntil = 0; hideNotice() }
        water.invalidate()
    }

    /** Fades a layer in or out; menus also grow slightly into place like the iOS transition. */
    private fun reveal(view: View, show: Boolean, immediate: Boolean, from: Float) {
        view.animate().cancel()
        if (immediate || reducedMotion) {
            view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f
            view.visibility = if (show) View.VISIBLE else View.GONE
            return
        }
        if (show) {
            view.alpha = 0f; view.scaleX = from; view.scaleY = from; view.visibility = View.VISIBLE
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        } else {
            view.animate().alpha(0f).setDuration(160).withEndAction { view.visibility = View.GONE; view.alpha = 1f }.start()
        }
    }

    private fun notice(message: String, symbol: String, tint: Int) {
        if (!::toast.isInitialized || game.status != Status.PLAYING) return
        toastIcon.setImageDrawable(ControlIcon(symbol, tint))
        toastText.text = message
        noticeUntil = System.currentTimeMillis() + 1600
        toast.animate().cancel()
        toast.visibility = View.VISIBLE
        if (reducedMotion) { toast.alpha = 1f; toast.translationY = 0f; toast.scaleX = 1f; toast.scaleY = 1f }
        else {
            toast.alpha = 0f; toast.translationY = -dpf(16f); toast.scaleX = .9f; toast.scaleY = .9f
            toast.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }
    private fun hideNotice() {
        toast.animate().cancel()
        if (reducedMotion) { toast.visibility = View.GONE; return }
        toast.animate().alpha(0f).translationY(-dpf(10f)).setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction { toast.visibility = View.GONE }.start()
    }

    // Handbook

    private fun showHelp() {
        if (game.status == Status.PLAYING) game.pause()
        updateHUD()
        val rules = listOf(
            listOf("swipe", Clay.mint, "Steer", "Swipe left or right across the water to change lanes. Screen-reader actions on the waterway also let you steer and jump."),
            listOf("scope", Clay.gold, "Fire", "Hold Fire to shoot ahead. Tap a pirate to aim across lanes. Each hit removes one health pip; tougher ships earn more points."),
            listOf("arrow.up", Clay.gold, "Jump", "Swipe up any time to jump over rocks for 1.15 seconds. You can't change lanes in the air, and ships and cannonballs still hit you. Jumps never run out: after landing, you can jump again two seconds later."),
            listOf("heart", Clay.heart, "Hull", "Hearts repair 10 hull. Rocks cost 25 hull; cannonballs cost 15, or 25 for Sharpshooter shots. Ramming a ship costs 30."),
            listOf("flag", Clay.gold, "Pirate fleet", "Scout 1 hull · Gunner 2 · Burst Raider 3 · Scattershot 3 · Sharpshooter 2 · Captain 10. New types arrive as levels increase."),
            listOf("warning", Clay.danger, "Read the attack", "Dodge aimed shots and three-shot bursts, find gaps in spreads, and use long reloads to attack. A Sharpshooter marks its aim before firing; move after the lock. Captains alternate bursts and spreads and attack faster at half health."),
            listOf("chest", Clay.gold, "The treasure", "Sail 1,500 m to the treasure island. The Kraken guards it: dodge its tentacle slams and sweeps, jump the rocks it hurls, and fire at its head. It rages at half health. Beat it to claim the treasure and 5,000 points."),
            listOf("wind", Clay.mint, "Levels", "Every 300 meters the water gets faster. Pause is at the top left; sound and help are in the pause menu."),
        )
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        val list = column(14).apply { setPadding(dp(20), dp(8), dp(20), dp(20)) }
        list.addView(text(17f, Clay.gold, 800).apply {
            text = "Done"; gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(4), dp(12)); minimumHeight = dp(48)
            isClickable = true; isFocusable = true; asButton(this)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        list.addView(text(34f, weight = 900).apply {
            text = "Captain’s handbook"
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        for ((symbol, tint, heading, body) in rules) {
            val bubble = FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Clay.alpha(Clay.ink, .6f)) }
                addView(ImageView(this@MainActivity).apply { setImageDrawable(ControlIcon(symbol as String, tint as Int)) },
                    FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val copy = column(4).apply {
                addView(text(17f, weight = 900).apply { text = heading as String })
                addView(text(16f, Clay.creamMuted, 400).apply { text = body as String; setLineSpacing(0f, 1.15f) })
            }
            list.addView(row(14).apply {
                gravity = Gravity.TOP
                addView(bubble, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
                background = clay(radius = 20)
                setPadding(dp(14), dp(14), dp(14), dp(14) + depth)
                isFocusable = true
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val scroller = ScrollView(this).apply {
            setBackgroundColor(Clay.deep); isVerticalScrollBarEnabled = false
            val wrap = FrameLayout(this@MainActivity)
            wrap.addView(list, FrameLayout.LayoutParams(min(dp(560), resources.displayMetrics.widthPixels), -2, Gravity.CENTER_HORIZONTAL))
            addView(wrap)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).let { Rect(it.left, it.top, it.right, it.bottom) }
                else @Suppress("DEPRECATION") Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        }
        dialog.setContentView(scroller)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Clay.deep))
        dialog.show()
    }

    // Back: pause a running voyage first; from a menu, Back leaves as usual. (Android only: iOS has
    // no system Back, and its pause button covers the same need.)

    private fun updateBackHandling(playing: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val current = backCallback
        if (playing && current == null) {
            val callback = OnBackInvokedCallback { if (game.status == Status.PLAYING) { game.pause(); updateHUD() } }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!playing && current != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(current)
            backCallback = null
        }
    }
    @Deprecated("Used before Android 13; newer versions use updateBackHandling.")
    override fun onBackPressed() {
        if (game.status == Status.PLAYING) { game.pause(); updateHUD() }
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // Lifecycle

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
        if (game.status == Status.PLAYING || game.status == Status.INTRO) water.invalidate()
        if (game.status == Status.INTRO) updateIntro()
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
