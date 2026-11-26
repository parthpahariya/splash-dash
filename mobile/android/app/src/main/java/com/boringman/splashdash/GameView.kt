package com.boringman.splashdash

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.*

/**
 * Native Canvas renderer; all artwork is drawn locally at any screen density.
 * Shapes, colors and layering match the iOS WaterView (GameSurface.swift).
 */
class GameView(context: Context, val game: Game) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val box = RectF()
    private val palette = HashMap<String, Int>()
    private var sea: LinearGradient? = null
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var downX = 0f
    private var downY = 0f
    private var anchor = 0f
    private var swiped = false
    private var verticalSwipe = false
    private val steerLeftAction = R.id.action_steer_left
    private val steerRightAction = R.id.action_steer_right
    private val jumpAction = R.id.action_jump
    var reducedMotion = false
    var contentInsets = Rect()

    private companion object {
        const val OUTLINE = "0B2530"
        const val SEA_TOP = "2BB0BC"
        const val SEA_BOTTOM = "12687A"
    }

    init {
        contentDescription = "Four-lane waterway. Swipe left or right to steer, swipe up to jump, or tap a pirate to aim. Custom actions also let you steer and jump."
        isClickable = true
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sea = LinearGradient(0f, 0f, w * .4f, h.toFloat(), color(SEA_TOP), color(SEA_BOTTOM), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        paint.style = Paint.Style.FILL; paint.color = color(SEA_BOTTOM)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = sea
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        val viewport = RectF(contentInsets.left.toFloat(), contentInsets.top.toFloat(), (width - contentInsets.right).toFloat(), (height - contentInsets.bottom).toFloat())
        if (viewport.width() <= 0 || viewport.height() <= 0) return
        scale = min(viewport.width() / 620f, viewport.height() / 800f)
        offsetX = viewport.centerX() - 400 * scale
        offsetY = viewport.centerY() - 400 * scale
        // Insets place gameplay safely; scenery continues beneath the HUD and Fire.
        // Only the physical screen edges clip drawing.
        val visibleMinX = (-offsetX / scale).toDouble()
        val visibleMinY = (-offsetY / scale).toDouble()
        val visibleWidth = (width / scale).toDouble()
        val visibleHeight = (height / scale).toDouble()
        val visibleMaxY = visibleMinY + visibleHeight
        // Spawn above and despawn below the physical screen edges, not the gameplay insets.
        game.visibleTop = visibleMinY
        game.visibleBottom = visibleMaxY
        c.save(); c.translate(offsetX, offsetY); c.scale(scale, scale)
        val scroll = if (reducedMotion) 0.0 else game.scroll
        val time = if (reducedMotion) 0.0 else game.elapsed

        // Lane bands: alternate lanes get a faint sheen so the four lanes read instantly.
        paint.color = color("FFFFFF", .045)
        for (i in intArrayOf(0, 2)) c.drawRect((148f + i * 126), visibleMinY.toFloat(), (148f + i * 126 + 126), visibleMaxY.toFloat(), paint)

        // Wave crests: small curved strokes drifting with the current.
        val rippleCount = max(60, min(200, (visibleWidth * visibleHeight / 9800).toInt()))
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = 2.5f
        for (i in 0 until rippleCount) {
            val x = visibleMinX + (i * 137.0) % visibleWidth
            val y = visibleMinY - 20 + (i * 83.0 + scroll * .65) % (visibleHeight + 40)
            val w = 8.0 + (i % 3) * 3
            paint.color = color("FFFFFF", if (i % 4 == 0) .32 else .16)
            shape.reset(); shape.moveTo((x - w).toFloat(), y.toFloat())
            shape.quadTo(x.toFloat(), (y - 6).toFloat(), (x + w).toFloat(), y.toFloat())
            c.drawPath(shape, paint)
        }

        val shorePhase = scroll * .55 % 480
        val firstIsland = floor((visibleMinY - shorePhase - 400) / 480).toInt()
        val lastIsland = ceil((visibleMaxY - shorePhase + 160) / 480).toInt()
        for (i in firstIsland..lastIsland) {
            val y = i * 480 + shorePhase
            island(c, 0.0, y, time); island(c, 800.0, y + 240, time)
        }

        // Lane dividers scroll with the water to sell speed.
        paint.pathEffect = DashPathEffect(floatArrayOf(14f, 22f), (36 - scroll % 36).toFloat())
        for (i in 1..3) {
            val x = 148.0 + i * 126
            line(c, x, visibleMinY, x, visibleMaxY, "FFFFFF", 3f, .18)
        }
        paint.pathEffect = null

        if (game.status == Status.READY) {
            rock(c, 211.0, 230.0, time); boat(c, 589.0, 170.0, true)
            heart(c, 211.0, 530.0, time)
        }
        for (e in game.entities) if (!e.dead) when (e.kind) {
            Kind.ROCK -> rock(c, e.x, e.y, time)
            Kind.PIRATE -> {
                wake(c, e.x, e.y - 50, true, time)
                pirateWarning(c, e)
                boat(c, e.x, e.y, true, e.hit > 0, e.pirateType)
                pirateHealth(c, e)
                if (e.hp == 1) {
                    line(c, e.x - 14, e.y + 14, e.x + 2, e.y, OUTLINE, 4f)
                    line(c, e.x + 2, e.y, e.x + 14, e.y - 16, OUTLINE, 4f)
                }
                if (e.flash > 0) {
                    oval(c, e.x, e.y + 62, 20.0, 22.0, "FFB35C", .7)
                    oval(c, e.x, e.y + 62, 11.0, 13.0, "FFF1B8")
                }
            }
            Kind.HEART -> heart(c, e.x, e.y, time)
        }
        for (b in game.bullets) if (!b.dead) {
            val speed = hypot(b.vx, b.vy).coerceAtLeast(1.0)
            val tx = b.x - b.vx / speed * 34
            val ty = b.y - b.vy / speed * 34
            if (b.enemy) {
                line(c, tx, ty, b.x, b.y, "FF9A4D", 6f, .55)
                oval(c, b.x, b.y, b.radius + 2, b.radius + 2, OUTLINE)
                oval(c, b.x, b.y, 7.0, 7.0, "3A4A52")
                oval(c, b.x - 2.5, b.y - 2.5, 2.5, 2.5, "8FA1A8")
            } else {
                line(c, tx, ty, b.x, b.y, "FFE08A", 7f, .35)
                line(c, (tx + b.x) / 2, (ty + b.y) / 2, b.x, b.y, "FFF6E0", 3f)
                oval(c, b.x, b.y, 6.0, 7.0, OUTLINE)
                oval(c, b.x, b.y, 4.0, 5.0, "FFE08A")
            }
        }
        val jump = if (game.jumping) sin((1 - game.jumpTime / Game.JUMP_DURATION) * PI) else 0.0
        if (game.status != Status.OVER) {
            if (!game.jumping) wake(c, game.x, Game.PLAYER_Y + 46, false, time)
            // Shadow shrinks and fades as the boat leaves the water.
            oval(c, game.x + 6 + jump * 10, Game.PLAYER_Y + 8 + jump * 20, 32 * (1 - jump * .25), 50 * (1 - jump * .25), OUTLINE, .28 - jump * .12)
            c.save(); c.translate(game.x.toFloat(), (Game.PLAYER_Y - jump * 28).toFloat())
            c.rotate(if (reducedMotion) 0f else (game.tilt * 180 / PI).toFloat())
            val size = (1 + jump * .16).toFloat(); c.scale(size, size)
            if (game.invincible == 0.0 || (game.elapsed * 12).toInt() % 2 == 0) boat(c, 0.0, 0.0, false)
            c.restore()
        }
        c.restore()
    }

    // Primitives

    private fun color(hex: String, alpha: Double = 1.0): Int {
        val base = palette.getOrPut(hex) { Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") }
        return if (alpha >= 1) base else Clay.alpha(base, alpha.toFloat())
    }
    private fun oval(c: Canvas, x: Double, y: Double, rx: Double, ry: Double, hex: String, alpha: Double = 1.0) {
        paint.style = Paint.Style.FILL; paint.color = color(hex, alpha)
        box.set((x - rx).toFloat(), (y - ry).toFloat(), (x + rx).toFloat(), (y + ry).toFloat())
        c.drawOval(box, paint)
    }
    private fun ring(c: Canvas, x: Double, y: Double, rx: Double, ry: Double, hex: String, width: Float, alpha: Double = 1.0) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.color = color(hex, alpha)
        box.set((x - rx).toFloat(), (y - ry).toFloat(), (x + rx).toFloat(), (y + ry).toFloat())
        c.drawOval(box, paint)
    }
    private fun line(c: Canvas, x1: Double, y1: Double, x2: Double, y2: Double, hex: String, width: Float, alpha: Double = 1.0) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
        paint.color = color(hex, alpha)
        c.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), paint)
    }
    /** Filled polygon with an optional ink outline for the chunky toy look. */
    private fun polygon(c: Canvas, p: DoubleArray, hex: String, outline: Float = 0f) {
        shape.reset(); shape.moveTo(p[0].toFloat(), p[1].toFloat())
        for (i in 2 until p.size step 2) shape.lineTo(p[i].toFloat(), p[i + 1].toFloat())
        shape.close()
        paint.style = Paint.Style.FILL; paint.color = color(hex)
        c.drawPath(shape, paint)
        if (outline > 0) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = outline; paint.strokeJoin = Paint.Join.ROUND
            paint.color = color(OUTLINE)
            c.drawPath(shape, paint)
        }
    }

    // Scenery and sprites

    private fun island(c: Canvas, x: Double, y: Double, t: Double) {
        val swell = sin(t * 1.6 + y * .01) * 3
        oval(c, x, y, 124 + swell, 172 + swell, "FFFFFF", .35)
        oval(c, x, y, 114.0, 162.0, "7FD8CB")
        oval(c, x, y, 100.0, 150.0, "F6DCA0")
        ring(c, x, y, 100.0, 150.0, OUTLINE, 3f, .5)
        oval(c, x, y, 82.0, 130.0, "5FB36A")
        oval(c, x - 10, y + 12, 58.0, 96.0, "4C9A5A")
        oval(c, x + 34, y + 70, 12.0, 9.0, "8C9A92")
        line(c, x, y + 22, x + 22, y - 35, OUTLINE, 12f)
        line(c, x, y + 22, x + 22, y - 35, "9A6E48", 7f)
        for (i in 0..5) {
            val a = i * PI / 3 + .2
            val ex = x + 22 + cos(a) * 46
            val ey = y - 35 + sin(a) * 30
            line(c, x + 22, y - 35, ex, ey, OUTLINE, 16f, .85)
            line(c, x + 22, y - 35, ex, ey, "2E8A55", 11f)
            line(c, x + 22, y - 35, (x + 22 + ex) / 2, (y - 35 + ey) / 2 - 2, "52B36F", 4f)
        }
        oval(c, x + 22, y - 35, 7.0, 7.0, "8A5A34")
    }

    private fun rock(c: Canvas, x: Double, y: Double, t: Double) {
        val foam = sin(t * 4 + x) * 2
        oval(c, x, y + 20, 48 + foam, 26 + foam / 2, "FFFFFF", .45)
        oval(c, x, y + 19, 42.0, 22.0, "7FD8CB")
        polygon(c, doubleArrayOf(x - 35, y + 17, x - 28, y - 19, x - 8, y - 33, x + 22, y - 23, x + 37, y + 10, x + 20, y + 29, x - 15, y + 29), "5C6C72", 3.5f)
        polygon(c, doubleArrayOf(x - 28, y - 19, x - 8, y - 33, x + 22, y - 23, x + 8, y - 5, x - 16, y), "8E9FA4")
        line(c, x - 12, y - 25, x + 6, y - 12, "C7D2D3", 3.5f)
        oval(c, x + 16, y + 14, 5.0, 3.0, "4A585D")
    }

    /** V-shaped foam trail behind a hull. [flip] points it upward for oncoming pirates. */
    private fun wake(c: Canvas, x: Double, y: Double, flip: Boolean, t: Double) {
        val d = if (flip) -1.0 else 1.0
        val wobble = sin(t * 10) * 2
        for ((spread, length, alpha) in listOf(Triple(26.0, 70.0, .45), Triple(14.0, 44.0, .6))) {
            line(c, x - 14, y, x - 14 - spread + wobble, y + d * length, "FFFFFF", 5f, alpha)
            line(c, x + 14, y, x + 14 + spread - wobble, y + d * length, "FFFFFF", 5f, alpha)
        }
        oval(c, x, y + d * 8, 14.0, 9.0, "FFFFFF", .5)
    }

    /** Draws [text] centered on [x] with its top edge at [y], like NSString.draw(at:). */
    private fun pirateText(c: Canvas, text: String, x: Double, y: Double, size: Float = 12f) {
        paint.style = Paint.Style.FILL; paint.color = color("FFF1D0")
        paint.textSize = size; paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.DEFAULT_BOLD
        c.drawText(text, x.toFloat(), (y - paint.ascent()).toFloat(), paint)
    }
    private fun pirateWarning(c: Canvas, e: Entity) {
        if (e.warning <= 0) return
        if (e.pirateType == PirateType.SNIPER) {
            paint.pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
            line(c, e.x, e.y + 43, e.aimX, Game.PLAYER_Y, "FFB6AC", 3f)
            paint.pathEffect = null
            ring(c, e.aimX, Game.PLAYER_Y, 26.0, 26.0, "FFF1CC", 2f)
        }
    }
    private fun pirateHealth(c: Canvas, e: Entity) {
        val captain = e.pirateType == PirateType.CAPTAIN
        val y = e.y - if (captain) 91 else 83
        paint.style = Paint.Style.FILL; paint.color = color("173E46")
        c.drawRect((e.x - 59).toFloat(), (y - 3).toFloat(), (e.x + 59).toFloat(), (y + 31).toFloat(), paint)
        pirateText(c, e.pirateType.title.uppercase(java.util.Locale.ROOT), e.x, y)
        if (captain) {
            line(c, e.x - 48, y + 23, e.x + 48, y + 23, "526265", 7f)
            if (e.hp > 0) line(c, e.x - 48, y + 23, e.x - 48 + 96.0 * e.hp / e.maxHp, y + 23, e.pirateType.color, 7f)
            pirateText(c, "${e.hp}/${e.maxHp}" + if (e.hp <= 5) " · ENRAGED" else "", e.x, y - 19)
        } else for (i in 0 until e.maxHp) {
            val x = e.x - (e.maxHp * 10 - 3) / 2.0 + i * 10
            paint.style = Paint.Style.FILL; paint.color = color(if (i < e.hp) e.pirateType.color else "526265")
            c.drawRect(x.toFloat(), (y + 20).toFloat(), (x + 7).toFloat(), (y + 26).toFloat(), paint)
        }
        if (e.warning > 0) {
            val label = when (e.pirateType) {
                PirateType.SNIPER -> "AIM LOCKED"
                else -> if (e.attackStep % 2 == 0) "BURST INCOMING" else "SPREAD INCOMING"
            }
            paint.style = Paint.Style.FILL; paint.color = color("442F35")
            c.drawRect((e.x - 63).toFloat(), (e.y + 78).toFloat(), (e.x + 63).toFloat(), (e.y + 100).toFloat(), paint)
            pirateText(c, label, e.x, e.y + 81)
        }
    }
    private fun boat(c: Canvas, x: Double, y: Double, pirate: Boolean, hit: Boolean = false, type: PirateType = PirateType.GUNNER) {
        c.save(); c.translate(x.toFloat(), y.toFloat())
        if (pirate) {
            c.rotate(180f)
            if (type == PirateType.CAPTAIN) c.scale(1.12f, 1.12f)
            oval(c, -6.0, -6.0, 32.0, 50.0, OUTLINE, .28)
        }
        // Bow cannon / bowsprit sits under the hull outline.
        line(c, 0.0, -46.0, 0.0, -64.0, OUTLINE, 11f)
        line(c, 0.0, -50.0, 0.0, -62.0, if (pirate) "3A4A52" else "FFE08A", 5f)
        polygon(c, doubleArrayOf(0.0, -58.0, -30.0, -24.0, -32.0, 27.0, -21.0, 49.0, 21.0, 49.0, 32.0, 27.0, 30.0, -24.0),
            if (hit) "FFF1B8" else if (pirate) "4A3328" else "FFF6E0", 4f)
        polygon(c, doubleArrayOf(0.0, -46.0, -22.0, -16.0, -21.0, 34.0, 21.0, 34.0, 22.0, -16.0), if (pirate) "8A5C3A" else "F3C98B")
        var plank = -6.0
        while (plank <= 30) { line(c, -18.0, plank, 18.0, plank, if (pirate) "6E4830" else "D9A86A", 1.5f); plank += 12 }
        if (pirate) {
            // Sail in the ship's colour with a skull and red trim.
            polygon(c, doubleArrayOf(-26.0, -22.0, 26.0, -22.0, 22.0, 14.0, 0.0, 7.0, -22.0, 14.0), type.color, 3f)
            line(c, -24.0, -20.0, 24.0, -20.0, "D9483B", 4f)
            if (type == PirateType.SCATTER || type == PirateType.BURST) for (gx in doubleArrayOf(-19.0, 19.0)) line(c, gx, -31.0, gx, -59.0, "29343B", 8f)
            if (type == PirateType.SNIPER) line(c, 0.0, -40.0, 0.0, -77.0, "29343B", 8f)
            if (type == PirateType.CAPTAIN) polygon(c, doubleArrayOf(-12.0, 30.0, -12.0, 42.0, -5.0, 36.0, 0.0, 46.0, 5.0, 36.0, 12.0, 42.0, 12.0, 30.0), "F3C75F", 1f)
            // The pirate frame is rotated 180°, so "up" on screen is +y here.
            line(c, -9.0, -16.0, 9.0, -10.0, "FFF6E0", 2.5f)
            line(c, 9.0, -16.0, -9.0, -10.0, "FFF6E0", 2.5f)
            oval(c, 0.0, -1.0, 8.0, 7.5, "FFF6E0")
            oval(c, -3.0, 1.0, 2.2, 2.2, "22292E")
            oval(c, 3.0, 1.0, 2.2, 2.2, "22292E")
        } else {
            // Coral cabin with a sky-tinted windshield.
            polygon(c, doubleArrayOf(-23.0, -24.0, 23.0, -24.0, 19.0, 13.0, 0.0, 7.0, -19.0, 13.0), "FF7A59", 3f)
            polygon(c, doubleArrayOf(-16.0, -20.0, 16.0, -20.0, 12.0, -6.0, -12.0, -6.0), "A8ECF2")
            line(c, -11.0, -17.0, -4.0, -9.0, "FFFFFF", 2.5f, .8)
            line(c, -20.0, 24.0, 20.0, 24.0, "FF7A59", 4f)
        }
        line(c, 0.0, -30.0, 0.0, 28.0, OUTLINE, 4f)
        c.restore()
    }

    private fun heart(c: Canvas, x: Double, y0: Double, t: Double) {
        val pulse = (sin(t * 5) + 1) / 2
        val y = y0 + sin(t * 3) * 3
        oval(c, x, y, 34 + pulse * 6, 34 + pulse * 6, "FFFFFF", .18 + pulse * .12)
        oval(c, x + 3, y + 5, 27.0, 27.0, OUTLINE, .3)
        oval(c, x, y, 28.0, 28.0, OUTLINE)
        oval(c, x, y, 24.5, 24.5, "FF5D73")
        oval(c, x - 8, y - 9, 9.0, 6.0, "FFFFFF", .25)
        oval(c, x - 7, y - 4, 8.0, 8.0, "FFF6E0")
        oval(c, x + 7, y - 4, 8.0, 8.0, "FFF6E0")
        polygon(c, doubleArrayOf(x - 14.6, y - 1, x + 14.6, y - 1, x, y + 15), "FFF6E0")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; anchor = event.x; swiped = false; verticalSwipe = false; return true }
            MotionEvent.ACTION_MOVE -> {
                val threshold = 28 * resources.displayMetrics.density
                val dx = event.x - downX
                val dy = event.y - downY
                if (!swiped && max(abs(dx), abs(dy)) > threshold) {
                    swiped = true
                    verticalSwipe = abs(dy) > abs(dx)
                    if (verticalSwipe && dy < 0 && game.jump()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                if (swiped && !verticalSwipe && abs(event.x - anchor) > threshold) {
                    val lane = game.lane
                    game.move(if (event.x > anchor) 1 else -1)
                    if (game.lane != lane) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    anchor = event.x
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!swiped && hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) < 20 * resources.displayMetrics.density) {
                    val x = (event.x - offsetX) / scale; val y = (event.y - offsetY) / scale
                    val target = game.entities.filter { it.kind == Kind.PIRATE && !it.dead && abs(it.x - x) < 65 && abs(it.y - y) < 75 }
                        .minByOrNull { hypot(it.x - x, it.y - y) }
                    if (target != null) game.fire(target)
                    performClick()
                }
            }
        }
        return true
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val state = "Lane ${game.lane + 1} of 4. Jump ${if (game.jumpReady) "ready" else "recharging"}."
        if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription = state else info.text = state
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(steerLeftAction, "Steer left"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(steerRightAction, "Steer right"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(jumpAction, "Jump"))
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (game.status == Status.PLAYING) when (action) {
            steerLeftAction -> { game.move(-1); return true }
            steerRightAction -> { game.move(1); return true }
            jumpAction -> return game.jump()
        }
        return super.performAccessibilityAction(action, arguments)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
