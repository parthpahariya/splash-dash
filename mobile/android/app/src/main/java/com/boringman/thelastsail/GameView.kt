package com.boringman.thelastsail

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
        /** How far above the shoreline the ✗ sits on the home land. */
        const val MARK_DEPTH = 330.0
        /** The boat moors just left of the jetty (x = 560) at the end of the voyage. */
        const val DOCK_X = 500.0
        // Leviathan palette from the Kraken concept page (concept A).
        const val LEV_SKIN = "2F5C66"; const val LEV_DARK = "16323A"; const val LEV_LIGHT = "5F8F96"; const val LEV_BELLY = "8FB4B0"
        const val LEV_SUCKER = "C9DED8"; const val LEV_SPOT = "5FF3E6"; const val LEV_EYE = "FFB000"; const val LEV_RAGE = "FF5A3C"; const val LEV_PUPIL = "1A0D00"
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


        // Journey's end: the treasure island (intro, sail-in and victory) and the Kraken guarding it.
        val scene = sceneLayout(visibleMinY, visibleMaxY)
        scene.landBottom?.let {
            val layer = if (scene.landAlpha < 1) c.saveLayerAlpha(null, (255 * scene.landAlpha).toInt().coerceIn(0, 255)) else c.save()
            homeLand(c, visibleMinX - 60, visibleMinX + visibleWidth + 60, visibleMinY - 60, it, game.elapsed)
            c.restoreToCount(layer)
        }
        if (scene.pan > .02 && scene.pan < .98 && !reducedMotion) {
            // Fast-forward streaks rushing past on the water.
            val strength = sin(scene.pan * PI)
            for (i in 0 until 16) {
                val x = visibleMinX + (i * 211 % 997) / 997.0 * visibleWidth
                val y = visibleMinY + (i * 173.0 + game.scroll * 1.6) % (visibleHeight + 160) - 80
                line(c, x, y, x, y + 70 + (i % 3) * 30, "FFFFFF", 3f, .28 * strength)
            }
        }
        game.kraken?.let { krakenBody(c, it, time, visibleMaxY) }

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
        game.kraken?.let { krakenSlams(c, it, time, visibleMaxY) }
        val jump = if (game.jumping) sin((1 - game.jumpTime / Game.JUMP_DURATION) * PI) else 0.0
        if (game.status != Status.OVER) {
            val boatX = scene.boatX ?: game.x
            val boatY = Game.PLAYER_Y + scene.boatOffset
            // A moored boat (after winning) leaves no wake.
            if (!game.jumping && game.status != Status.WON) wake(c, boatX, boatY + 46, false, time)
            // Shadow shrinks and fades as the boat leaves the water.
            oval(c, boatX + 6 + jump * 10, boatY + 8 + jump * 20, 32 * (1 - jump * .25), 50 * (1 - jump * .25), OUTLINE, .28 - jump * .12)
            c.save(); c.translate(boatX.toFloat(), (boatY - jump * 28).toFloat())
            c.rotate(if (reducedMotion) 0f else (game.tilt * 180 / PI).toFloat())
            val size = (1 + jump * .16).toFloat(); c.scale(size, size)
            if (game.invincible == 0.0 || (game.elapsed * 12).toInt() % 2 == 0) boat(c, 0.0, 0.0, false)
            c.restore()
        }
        c.restore()
    }

    // Journey's end

    private class Scene(val landBottom: Double?, val boatOffset: Double, val boatX: Double?,
                        val pan: Double = 0.0, val landAlpha: Double = 1.0)
    /** Where the home land (intro), the treasure island (sail-in, victory) and the player's boat sit. */
    private fun sceneLayout(top: Double, bottom: Double): Scene = when {
        game.status == Status.INTRO -> {
            // Land fills the screen down to a strip of ocean; then the view rushes up and out to sea.
            val p = 1 - game.introTime / Game.INTRO_DURATION
            val s = if (p < Game.INTRO_LAND_SHARE) 0.0 else (p - Game.INTRO_LAND_SHARE) / (1 - Game.INTRO_LAND_SHARE)
            // Ease in, rush, ease out: the pan starts gently, speeds through the middle and lands softly.
            val pan = if (s < .5) 4 * s * s * s else 1 - (-2 * s + 2).pow(3) / 2
            val shore = bottom - (bottom - top) * .2
            // The boat waits below the screen and only glides in once the fast-forward starts.
            val hiddenOffset = bottom + 120 - Game.PLAYER_Y
            // No camera motion with reduced motion: the land fades away and the boat is simply there.
            if (reducedMotion) Scene(shore, if (s > 0) 0.0 else hiddenOffset, null, 0.0, 1 - min(1.0, s * 4))
            else Scene(shore - pan * (bottom - top + 200), hiddenOffset * (1 - pan), null, s)
        }
        // Moored at the jetty of the same land the voyage started from.
        game.status == Status.WON -> { val shore = homeShore(top, bottom); Scene(shore, dockY(shore) - Game.PLAYER_Y, DOCK_X) }
        game.outro > 0 -> {
            // Journey's end: the home land slides into view and the boat sails up to its jetty.
            val t = (1 - game.outro / Game.OUTRO_DURATION).coerceIn(0.0, 1.0); val q = t * t * (3 - 2 * t)
            val shore = homeShore(top, bottom); val start = top - 160
            val land = if (reducedMotion) shore else start + (shore - start) * q
            val boatY = Game.PLAYER_Y + (dockY(shore) - Game.PLAYER_Y) * q
            Scene(land, boatY - Game.PLAYER_Y, game.x + (DOCK_X - game.x) * q, 0.0, if (reducedMotion) q else 1.0)
        }
        else -> Scene(null, 0.0, null)
    }

    /** Where the home shore rests at the end of the voyage, and where the boat moors beside the jetty. */
    private fun homeShore(top: Double, bottom: Double) = top + (bottom - top) * .55
    private fun dockY(shore: Double) = shore + 120

    /** A palm tree with a hard drop shadow: ink-outlined trunk and fronds. */
    private fun palm(c: Canvas, x: Double, y: Double) {
        oval(c, x + 12, y + 66, 34.0, 11.0, OUTLINE, .18)
        line(c, x - 16, y + 62, x, y, OUTLINE, 12f)
        line(c, x - 16, y + 62, x, y, "9A6E48", 7f)
        for (i in 0..5) {
            val a = i * PI / 3 + .2
            val ex = x + cos(a) * 44; val ey = y + sin(a) * 28
            line(c, x, y, ex, ey, OUTLINE, 15f, .85)
            line(c, x, y, ex, ey, "2E8A55", 10f)
        }
    }
    /** A round bush with a shadow, ink rim and highlight. */
    private fun bush(c: Canvas, x: Double, y: Double, r: Double) {
        oval(c, x + 6, y + r * .7, r, r * .4, OUTLINE, .18)
        oval(c, x, y, r, r * .8, "3F8A4F")
        ring(c, x, y, r, r * .8, OUTLINE, 3f, .6)
        oval(c, x - r * .3, y - r * .25, r * .4, r * .25, "6FC27A")
    }
    /** Fills the area between two wavy horizontal edges. */
    private fun band(c: Canvas, left: Double, right: Double, top: (Double) -> Double, bottom: (Double) -> Double, hex: String) {
        shape.reset(); shape.moveTo(left.toFloat(), top(left).toFloat())
        var x = left; while (x <= right) { shape.lineTo(x.toFloat(), top(x).toFloat()); x += 10 }
        x = right; while (x >= left) { shape.lineTo(x.toFloat(), bottom(x).toFloat()); x -= 10 }
        shape.close()
        paint.style = Paint.Style.FILL; paint.color = color(hex); c.drawPath(shape, paint)
    }
    /** Strokes a wavy horizontal edge. */
    private fun edge(c: Canvas, left: Double, right: Double, y: (Double) -> Double, hex: String, width: Float, alpha: Double = 1.0) {
        shape.reset(); shape.moveTo(left.toFloat(), y(left).toFloat())
        var x = left; while (x <= right) { shape.lineTo(x.toFloat(), y(x).toFloat()); x += 10 }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeJoin = Paint.Join.ROUND
        paint.color = color(hex, alpha); c.drawPath(shape, paint)
    }

    /**
     * The home shore at the start of a voyage, drawn in the game's toy style: an outlined
     * coastline, a jetty, a dotted treasure-map trail and the ✗ over buried treasure.
     */
    private fun homeLand(c: Canvas, left: Double, right: Double, top: Double, shore: Double, t: Double) {
        if (shore <= top) return
        val motion = if (reducedMotion) 0.0 else t
        val sandEdge = { x: Double -> shore + sin(x * .035) * 6 }
        val grassEdge = { x: Double -> shore - 50 + sin(x * .028 + 1.3) * 9 }
        val foam = { x: Double -> shore + 30 + sin(x * .05 + motion * 2) * 4 }
        // Wet shallows and a foam line that laps at the beach.
        band(c, left, right, sandEdge, foam, "7FD8CB")
        edge(c, left, right, foam, "FFFFFF", 7f, .55)
        // Sand, then grass, each with an ink outline like the game's islands and rocks.
        band(c, left, right, { top }, sandEdge, "F6DCA0")
        edge(c, left, right, sandEdge, OUTLINE, 3f, .5)
        band(c, left, right, { top }, grassEdge, "5FB36A")
        edge(c, left, right, grassEdge, OUTLINE, 3f, .4)
        // A wooden jetty where the voyage begins.
        val jx = 560.0
        polygon(c, doubleArrayOf(jx - 22, shore - 18, jx + 22, shore - 18, jx + 22, shore + 96, jx - 22, shore + 96), "B07B4C", 3f)
        var py = shore - 4; while (py <= shore + 86) { line(c, jx - 20, py, jx + 20, py, "8A5A34", 2f); py += 16 }
        for ((px, qy) in listOf(jx - 22 to shore + 40, jx + 22 to shore + 40, jx - 22 to shore + 96, jx + 22 to shore + 96)) {
            oval(c, px, qy, 6.0, 6.0, "6E4830"); ring(c, px, qy, 6.0, 6.0, OUTLINE, 2f)
        }
        // Grass detail: darker meadows, tufts and a few flowers.
        for ((dx, dy, rx, ry) in listOf(listOf(120.0, 170.0, 90.0, 46.0), listOf(650.0, 140.0, 110.0, 54.0), listOf(260.0, 560.0, 120.0, 64.0),
                listOf(600.0, 480.0, 90.0, 50.0), listOf(90.0, 740.0, 100.0, 56.0), listOf(700.0, 800.0, 120.0, 64.0))) {
            oval(c, dx, shore - dy, rx, ry, "4C9A5A")
        }
        for (i in 0 until 46) {
            val tx = left + (i * 157 % 941) / 941.0 * (right - left)
            val ty = shore - 70 - (i * 89 % 997) / 997.0 * 900
            line(c, tx - 4, ty, tx - 6, ty - 9, "3E8A4C", 2.5f)
            line(c, tx, ty, tx, ty - 11, "3E8A4C", 2.5f)
            line(c, tx + 4, ty, tx + 6, ty - 9, "3E8A4C", 2.5f)
            if (i % 5 == 0) oval(c, tx + 10, ty - 6, 3.5, 3.5, if (i % 10 == 0) "FFF6E0" else "FFC94D")
        }
        for ((bx, by, r) in listOf(Triple(230.0, 250.0, 26.0), Triple(660.0, 330.0, 30.0), Triple(110.0, 470.0, 24.0), Triple(520.0, 650.0, 28.0))) bush(c, bx, shore - by, r)
        for ((px, qy) in listOf(110.0 to 130.0, 700.0 to 170.0, 180.0 to 430.0, 650.0 to 580.0, 330.0 to 760.0, 90.0 to 900.0)) palm(c, px, shore - qy)
        // A dotted treasure-map trail from the jetty up to the ✗.
        val mx = 400.0; val my = shore - MARK_DEPTH
        for (i in 0..16) {
            val u = i / 16.0; val v = 1 - u
            val px = v * v * jx + 2 * v * u * 560 + u * u * (mx + 50)
            val qy = v * v * (shore - 30) + 2 * v * u * (my + 40) + u * u * (my + 34)
            oval(c, px, qy, 4.5, 4.5, "D9483B", .85)
        }
        buriedTreasure(c, mx, my, t)
    }

    /**
     * ✗ marks the spot: two pieces of sun-bleached driftwood crossed on a sand mound in a sandy
     * clearing, with the corner of a buried chest peeking out. No gold on show: it stays buried.
     */
    private fun buriedTreasure(c: Canvas, x: Double, y: Double, t: Double) {
        // The ✗ pops in with a small overshoot as the scene appears.
        val p = 1 - game.introTime / Game.INTRO_DURATION
        val pop = if (game.status != Status.INTRO || reducedMotion) 1.0 else Clay.easeOutBack(min(1.0, p / .12))
        // Sandy clearing with ripples.
        oval(c, x + 4, y + 8, 124.0, 80.0, OUTLINE, .12)
        oval(c, x, y, 120.0, 78.0, "F6DCA0")
        ring(c, x, y, 120.0, 78.0, OUTLINE, 3f, .5)
        for ((dx, dy) in listOf(-92.0 to -36.0, 60.0 to -54.0, -84.0 to 42.0, 70.0 to 48.0)) {
            shape.reset(); shape.moveTo((x + dx).toFloat(), (y + dy).toFloat())
            shape.quadTo((x + dx + 12).toFloat(), (y + dy - 8).toFloat(), (x + dx + 24).toFloat(), (y + dy).toFloat())
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.strokeCap = Paint.Cap.ROUND; paint.color = color("E5C384")
            c.drawPath(shape, paint)
        }
        // The sand mound and the buried chest's corner.
        oval(c, x + 5, y + 10, 76.0, 46.0, OUTLINE, .18)
        oval(c, x, y, 72.0, 44.0, "E9C98A")
        ring(c, x, y, 72.0, 44.0, OUTLINE, 3f)
        oval(c, x - 12, y - 12, 40.0, 18.0, "F7E2B0")
        polygon(c, doubleArrayOf(x + 20, y + 14, x + 56, y + 6, x + 60, y + 32, x + 24, y + 38), "8A5A34", 3f)
        line(c, x + 22, y + 24, x + 58, y + 16, "F3C75F", 4f)
        // Two driftwood branches crossed into the ✗.
        c.save(); c.translate(x.toFloat(), y.toFloat()); c.scale(pop.toFloat(), pop.toFloat())
        fun branch(pts: DoubleArray, wood: String) {
            shape.reset(); shape.moveTo(pts[0].toFloat(), pts[1].toFloat())
            shape.cubicTo(pts[2].toFloat(), pts[3].toFloat(), pts[4].toFloat(), pts[5].toFloat(), pts[6].toFloat(), pts[7].toFloat())
            shape.cubicTo(pts[8].toFloat(), pts[9].toFloat(), pts[10].toFloat(), pts[11].toFloat(), pts[12].toFloat(), pts[13].toFloat())
            paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 18f; paint.color = color(OUTLINE); c.drawPath(shape, paint)
            paint.strokeWidth = 11f; paint.color = color(wood); c.drawPath(shape, paint)
        }
        branch(doubleArrayOf(-56.0, -26.0, -36.0, -22.0, -16.0, -8.0, 0.0, 4.0, 16.0, 16.0, 34.0, 26.0, 50.0, 30.0), "B8A48A")
        branch(doubleArrayOf(54.0, -28.0, 36.0, -20.0, 18.0, -8.0, 2.0, 6.0, -14.0, 20.0, -30.0, 30.0, -46.0, 36.0), "CDBA9E")
        for ((a, b) in listOf(doubleArrayOf(30.0, -14.0) to doubleArrayOf(40.0, -26.0), doubleArrayOf(-30.0, 14.0) to doubleArrayOf(-36.0, 26.0))) {
            line(c, a[0], a[1], b[0], b[1], OUTLINE, 6f)
            line(c, a[0], a[1], b[0], b[1], "CDBA9E", 3f)
        }
        oval(c, -30.0, -12.0, 2.5, 2.5, "7E6B55")
        oval(c, 20.0, 10.0, 2.5, 2.5, "7E6B55")
        c.restore()
    }

    /** A tapering arm along [pts], with a pale underside and suckers. */
    private fun tentacle(c: Canvas, pts: List<Pair<Double, Double>>, w0: Double, alpha: Double) {
        val n = pts.size - 1
        if (n <= 0) return
        for (i in 0 until n) {
            val s = i.toDouble() / n
            val w = max(1.5, w0 * (1 - s).pow(.85))
            val (x1, y1) = pts[i]; val (x2, y2) = pts[i + 1]
            line(c, x1, y1, x2, y2, LEV_DARK, (w + 3).toFloat(), alpha)
            line(c, x1, y1, x2, y2, LEV_SKIN, w.toFloat(), alpha)
            line(c, x1, y1, x2, y2, LEV_BELLY, (w * .35).toFloat(), alpha * .55)
            if (i % 3 == 1 && w > 6) oval(c, x1, y1, w * .18, w * .15, LEV_SUCKER, alpha)
        }
    }
    /** Points along an arm that curls harder toward its tip. */
    private fun spine(x0: Double, y0: Double, length: Double, heading: Double, dir: Double, curl: Double, t: Double): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>(21)
        var x = x0; var y = y0; var h = heading; val n = 20
        for (i in 0..n) {
            val s = i.toDouble() / n
            pts.add(x to y)
            h += dir * (.02 + curl * s.pow(2.4)) + .03 * sin(t * 1.5 + s * 5)
            x += cos(h) * length / n; y += sin(h) * length / n
        }
        return pts
    }

    /** The Leviathan seen from above: warning lanes, submerged arms, mantle off the top of the screen, head and eyes. */
    private fun krakenBody(c: Canvas, k: Kraken, t: Double, bottom: Double) {
        val hx = 400.0; val hy = k.y; val pulse = (sin(t * 9) + 1) / 2
        val layer = if (k.dying > 0) c.saveLayerAlpha(null, (255 * k.dying / 1.6).toInt().coerceIn(0, 255)) else c.save()
        for (l in k.warningLanes) {
            val lx = Game.LANES[l]
            paint.style = Paint.Style.FILL; paint.color = color("14001E", .14 + .12 * pulse)
            c.drawRect((lx - 63).toFloat(), (hy + 60).toFloat(), (lx + 63).toFloat(), max(hy + 61, bottom).toFloat(), paint)
            for (i in 0 until 6) {
                val ph = (t * .8 + i * .17) % 1.0; val r = 5.0 + (i % 3) * 2
                ring(c, lx + sin(i * 2.3) * 30, bottom - 60 - ph * 360, r, r, "FFFFFF", 2f, .75 * (1 - ph))
            }
        }
        // Submerged arms fan out from the crown; feeding tentacles trail down the outer lanes.
        for ((i, a) in doubleArrayOf(-1.05, -.72, -.38, .38, .72, 1.05).withIndex()) {
            tentacle(c, spine(hx + a * 24, hy + 40, 150.0 + (i % 2) * 40, PI / 2 + a * .9, if (a < 0) -1.0 else 1.0, .5, t), 22.0, .38)
        }
        for (side in doubleArrayOf(-1.0, 1.0)) tentacle(c, spine(hx + side * 40, hy + 40, 320.0, PI / 2 - side * .55, side, .06, t), 14.0, .38)
        // Fins near the mantle tip, then the mantle rising off the top of the screen.
        val flap = sin(t * 1.8) * 6
        for (side in doubleArrayOf(-1.0, 1.0)) polygon(c, doubleArrayOf(hx + side * 8, hy - 236, hx + side * (84 + flap), hy - 272, hx + side * 22, hy - 312), LEV_LIGHT, 2.5f)
        shape.reset()
        shape.moveTo((hx - 66).toFloat(), (hy - 20).toFloat())
        shape.quadTo((hx - 86).toFloat(), (hy - 210).toFloat(), hx.toFloat(), (hy - 340).toFloat())
        shape.quadTo((hx + 86).toFloat(), (hy - 210).toFloat(), (hx + 66).toFloat(), (hy - 20).toFloat())
        shape.close()
        paint.style = Paint.Style.FILL; paint.color = color(LEV_SKIN); c.drawPath(shape, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = color(OUTLINE, .6); c.drawPath(shape, paint)
        line(c, hx, hy - 30, hx, hy - 300, LEV_DARK, 8f, .5)
        for (side in doubleArrayOf(-1.0, 1.0)) line(c, hx + side * 26, hy - 30, hx + side * 10, hy - 280, "000000", 3f, .14)
        for ((bx, by) in listOf(-34.0 to -70.0, 30.0 to -96.0, -18.0 to -140.0, 22.0 to -168.0, -28.0 to -200.0, 14.0 to -236.0)) {
            oval(c, hx + bx, hy + by, 5.0, 5.0, "C9C6B8"); oval(c, hx + bx, hy + by, 2.0, 2.0, "3D3A33")
        }
        // Photophores pulse, and blaze before a slam.
        val glow = if (k.warning > 0) 1.0 else .35 + .65 * (.5 + .5 * sin(t * if (k.enraged) 5.0 else 2.4))
        for (side in doubleArrayOf(-1.0, 1.0)) for (i in 1 until 9) {
            val f = i / 9.0; val px = hx + side * 44 * (1 - f * .8); val py = hy - 24 - f * 290; val r = 1 - f * .5
            oval(c, px, py, 6 * r, 6 * r, LEV_SPOT, glow * .35)
            oval(c, px, py, 3 * r, 3 * r, LEV_SPOT, glow)
        }
        // Head with bulging side eyes.
        // A hit briefly lightens the head (no white overlay).
        oval(c, hx, hy + 6, 72.0, 34.0, if (k.hit > 0) LEV_LIGHT else LEV_SKIN)
        ring(c, hx, hy + 6, 72.0, 34.0, LEV_DARK, 3f, .8)
        for (side in doubleArrayOf(-1.0, 1.0)) {
            val ex = hx + side * 62; val ey = hy + 2
            oval(c, ex, ey, 20.0, 17.0, LEV_DARK)
            oval(c, ex, ey, 13.0, 10.0, if (k.enraged) LEV_RAGE else LEV_EYE)
            oval(c, ex, ey, 2.2, 7.0, LEV_PUPIL)
            oval(c, ex - 4, ey - 3, 2.0, 2.0, "FFFFFF", .8)
        }
        c.restoreToCount(layer)
    }

    /** Tentacles slamming down their lanes toward the boat. */
    private fun krakenSlams(c: Canvas, k: Kraken, t: Double, bottom: Double) {
        if (k.slam <= 0) return
        val progress = 1 - k.slam / Game.SLAM_DURATION
        val reach = .25 + .75 * min(1.0, progress / .3).pow(3)
        for (l in k.slamLanes) {
            // Rooted in the arm crown under the head, curving out to the lane, then straight down it.
            val lx = Game.LANES[l]
            val rootX = 400 + (lx - 400).coerceIn(-40.0, 40.0); val rootY = k.y + 34
            val bendY = k.y + 150
            val end = bendY + (bottom - bendY) * reach
            val pts = ArrayList<Pair<Double, Double>>(25)
            for (i in 0..8) {
                val s = i / 8.0; val u = 1 - s
                pts.add((u * u * rootX + 2 * u * s * lx + s * s * lx) to (u * u * rootY + 2 * u * s * (rootY + 30) + s * s * bendY))
            }
            for (i in 1..16) { val s = i / 16.0; pts.add((lx + sin(t * 6 - s * 6) * 8 * s) to (bendY + s * (end - bendY))) }
            tentacle(c, pts, 40.0, 1.0)
            oval(c, lx, end, 44.0, 16.0, "FFFFFF", .7)
        }
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
            MotionEvent.ACTION_DOWN -> if (game.status == Status.INTRO) { game.skipIntro(); return true } else { downX = event.x; downY = event.y; anchor = event.x; swiped = false; verticalSwipe = false; return true }
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
                    val k = game.kraken
                    if (k != null && k.surfaced && y < k.y + 150) { game.fire(); performClick(); return true } // tapping the Kraken fires at it
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
        val state = "Lane ${game.lane + 1} of 4. Jump ${if (game.jumpReady) "ready" else "recharging"}." +
            (game.kraken?.let { " Kraken health ${max(0, it.hp)} of ${Game.KRAKEN_HP}." } ?: "")
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
