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
        /** How far above the shoreline the ✗ sits on the home land. */
        const val MARK_DEPTH = 330.0
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
        scene.landBottom?.let { homeLand(c, visibleMinX - 60, visibleMinX + visibleWidth + 60, visibleMinY - 60, it, time) }
        scene.islandY?.let { treasureIsland(c, 400.0, it, time) }
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
            if (!game.jumping) wake(c, boatX, boatY + 46, false, time)
            // Shadow shrinks and fades as the boat leaves the water.
            oval(c, boatX + 6 + jump * 10, boatY + 8 + jump * 20, 32 * (1 - jump * .25), 50 * (1 - jump * .25), OUTLINE, .28 - jump * .12)
            c.save(); c.translate(boatX.toFloat(), (boatY - jump * 28).toFloat())
            c.rotate(if (reducedMotion) 0f else (game.tilt * 180 / PI).toFloat())
            val size = (1 + jump * .16).toFloat(); c.scale(size, size)
            if (game.invincible == 0.0 || (game.elapsed * 12).toInt() % 2 == 0) boat(c, 0.0, 0.0, false)
            c.restore()
        }
        if (game.status == Status.INTRO) {
            val p = 1 - game.introTime / Game.INTRO_DURATION
            if (p > Game.INTRO_LAND_SHARE + .15) caption(c, "Set sail!", 400.0, 300.0, 44f, min(1.0, (p - Game.INTRO_LAND_SHARE - .15) / .1))
            caption(c, "Tap to skip", 400.0, visibleMaxY - 70, 15f, .7)
        }
        c.restore()
    }

    // Journey's end

    private class Scene(val landBottom: Double?, val islandY: Double?, val boatOffset: Double, val boatX: Double?)
    /** Where the home land (intro), the treasure island (sail-in, victory) and the player's boat sit. */
    private fun sceneLayout(top: Double, bottom: Double): Scene = when {
        game.status == Status.INTRO -> {
            // Land fills the screen down to a strip of ocean; then it slides up and away, fast.
            val p = 1 - game.introTime / Game.INTRO_DURATION
            val q = if (p < Game.INTRO_LAND_SHARE) 0.0 else ((p - Game.INTRO_LAND_SHARE) / (1 - Game.INTRO_LAND_SHARE)).pow(1.6)
            val shore = bottom - (bottom - top) * .2
            // The boat waits below the screen and only glides in once the fast-forward starts.
            val hiddenOffset = bottom + 120 - Game.PLAYER_Y
            Scene(shore - q * (bottom - top + 200), null, hiddenOffset * (1 - q), null)
        }
        game.status == Status.WON -> Scene(null, 280.0, -190.0, 400.0)
        game.outro > 0 -> {
            val t = (1 - game.outro / Game.OUTRO_DURATION).coerceIn(0.0, 1.0); val q = t * t * (3 - 2 * t)
            Scene(null, -260 + q * 540, -q * 190, game.x + (400 - game.x) * q)
        }
        else -> Scene(null, null, 0.0, null)
    }

    /** A palm tree: ink-outlined trunk and fronds. */
    private fun palm(c: Canvas, x: Double, y: Double) {
        line(c, x - 16, y + 62, x, y, OUTLINE, 12f)
        line(c, x - 16, y + 62, x, y, "9A6E48", 7f)
        for (i in 0..5) {
            val a = i * PI / 3 + .2
            val ex = x + cos(a) * 44; val ey = y + sin(a) * 28
            line(c, x, y, ex, ey, OUTLINE, 15f, .85)
            line(c, x, y, ex, ey, "2E8A55", 10f)
        }
    }

    /**
     * The home shore at the start of a voyage: green land with an ✗ marking the treasure map,
     * a sandy beach, and the ocean below.
     */
    private fun homeLand(c: Canvas, left: Double, right: Double, top: Double, shore: Double, t: Double) {
        if (shore <= top) return
        val wave = sin(t * 2) * 3
        var x = left
        while (x <= right) { oval(c, x, shore + 14 + wave, 46.0, 20.0, "FFFFFF", .45); oval(c, x, shore + 4, 44.0, 18.0, "7FD8CB"); x += 56 }
        paint.style = Paint.Style.FILL; paint.color = color("F6DCA0")
        c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), shore.toFloat(), paint)
        x = left; while (x <= right) { oval(c, x, shore - 2, 40.0, 14.0, "F6DCA0"); x += 52 }
        val grass = shore - 46
        paint.style = Paint.Style.FILL; paint.color = color("5FB36A")
        c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), max(top, grass).toFloat(), paint)
        x = left; while (x <= right) { oval(c, x, grass, 36.0, 16.0, "5FB36A"); x += 48 }
        for ((dx, dy, rx, ry) in listOf(listOf(120.0, 150.0, 90.0, 50.0), listOf(650.0, 120.0, 110.0, 60.0), listOf(260.0, 520.0, 120.0, 70.0),
                listOf(560.0, 460.0, 90.0, 55.0), listOf(90.0, 700.0, 100.0, 60.0), listOf(700.0, 760.0, 120.0, 70.0))) {
            oval(c, dx, grass - dy, rx, ry, "4C9A5A")
        }
        for ((px, py) in listOf(110.0 to 120.0, 700.0 to 150.0, 180.0 to 430.0, 640.0 to 560.0, 330.0 to 720.0, 90.0 to 860.0)) palm(c, px, grass - py)
        // ✗ marks the spot.
        val xY = shore - MARK_DEPTH
        oval(c, 400.0, xY + 6, 60.0, 26.0, "8A6A3A", .3)
        for ((dx, dy) in listOf(1.0 to 1.0, 1.0 to -1.0)) {
            line(c, 400 - 36 * dx, xY - 36 * dy, 400 + 36 * dx, xY + 36 * dy, OUTLINE, 20f)
            line(c, 400 - 36 * dx, xY - 36 * dy, 400 + 36 * dx, xY + 36 * dy, "D9483B", 12f)
        }
    }

    /** A sandy island with a palm and an open, glowing treasure chest. */
    private fun treasureIsland(c: Canvas, x: Double, y: Double, t: Double) {
        val swell = sin(t * 1.6) * 3; val pulse = (sin(t * 4) + 1) / 2
        oval(c, x, y, 160 + swell, 118 + swell, "FFFFFF", .35)
        oval(c, x, y, 150.0, 108.0, "7FD8CB")
        oval(c, x, y, 134.0, 94.0, "F6DCA0")
        ring(c, x, y, 134.0, 94.0, OUTLINE, 3f, .5)
        oval(c, x - 70, y + 10, 46.0, 34.0, "5FB36A")
        // Palm
        line(c, x - 70, y + 12, x - 54, y - 50, OUTLINE, 12f)
        line(c, x - 70, y + 12, x - 54, y - 50, "9A6E48", 7f)
        for (i in 0..5) {
            val a = i * PI / 3 + .2
            val ex = x - 54 + cos(a) * 44; val ey = y - 50 + sin(a) * 28
            line(c, x - 54, y - 50, ex, ey, OUTLINE, 15f, .85)
            line(c, x - 54, y - 50, ex, ey, "2E8A55", 10f)
        }
        // Chest with a golden glow and sparkles
        val cx = x + 34; val cy = y + 4
        oval(c, cx, cy, 64 + pulse * 8, 50 + pulse * 6, "FFD34D", .22 + pulse * .12)
        polygon(c, doubleArrayOf(cx - 34, cy - 30, cx + 34, cy - 30, cx + 30, cy - 52, cx - 30, cy - 52), "A86E42", 3f)
        for ((dx, dy) in listOf(-16.0 to -12.0, 0.0 to -16.0, 14.0 to -11.0, -6.0 to -6.0, 8.0 to -4.0)) oval(c, cx + dx, cy + dy, 8.0, 6.0, "FFD34D")
        oval(c, cx + 2, cy - 18, 5.0, 5.0, "E0475B")
        polygon(c, doubleArrayOf(cx - 34, cy - 8, cx + 34, cy - 8, cx + 34, cy + 26, cx - 34, cy + 26), "8A5A34", 3f)
        line(c, cx - 34, cy + 2, cx + 34, cy + 2, "F3C75F", 4f)
        line(c, cx - 34, cy + 18, cx + 34, cy + 18, "F3C75F", 4f)
        polygon(c, doubleArrayOf(cx - 6, cy - 4, cx + 6, cy - 4, cx + 6, cy + 10, cx - 6, cy + 10), "F3C75F", 2f)
        for (i in 0 until 4) {
            val a = t * .9 + i * PI / 2; val r = 58.0
            val sx = cx + cos(a) * r; val sy = cy - 10 + sin(a) * r * .6; val s = 4 + pulse * 4
            line(c, sx - s, sy, sx + s, sy, "FFF6E0", 2.5f, .6 + pulse * .4)
            line(c, sx, sy - s, sx, sy + s, "FFF6E0", 2.5f, .6 + pulse * .4)
        }
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

    /** Large caption text centred on [x] with a soft shadow, used by the intro scene. */
    private fun caption(c: Canvas, text: String, x: Double, y: Double, size: Float, alpha: Double) {
        paint.style = Paint.Style.FILL; paint.color = color("FFF6E0", alpha)
        paint.textSize = size; paint.textAlign = Paint.Align.CENTER; paint.typeface = Clay.font(900)
        paint.setShadowLayer(4f, 0f, 3f, color(OUTLINE, .8 * alpha))
        c.drawText(text, x.toFloat(), (y - paint.ascent()).toFloat(), paint)
        paint.clearShadowLayer()
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
