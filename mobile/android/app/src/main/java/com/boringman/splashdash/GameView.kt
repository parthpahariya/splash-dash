package com.boringman.splashdash

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import kotlin.math.*

/** Native Canvas renderer; all artwork is drawn locally at any screen density. */
class GameView(context: Context, val game: Game) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val waterGradient = LinearGradient(0f, 0f, 800f, 800f, Color.rgb(49, 142, 148), Color.rgb(31, 111, 124), Shader.TileMode.CLAMP)
    private val gradientMatrix = Matrix()
    private val laneDashes = DashPathEffect(floatArrayOf(7f, 14f), 0f)
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

    init {
        contentDescription = "Four-lane waterway. Swipe left or right to steer, swipe up to jump, or tap a pirate or mine to aim. Custom actions also let you steer and jump."
        isClickable = true
        clipToOutline = true
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradientMatrix.setScale(w / 800f, h / 800f)
        waterGradient.setLocalMatrix(gradientMatrix)
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        paint.shader = waterGradient
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        val viewport = RectF(contentInsets.left.toFloat(), contentInsets.top.toFloat(), (width-contentInsets.right).toFloat(), (height-contentInsets.bottom).toFloat())
        if (viewport.width() <= 0 || viewport.height() <= 0) return
        scale = min(viewport.width() / 620f, viewport.height() / 800f)
        offsetX = viewport.centerX() - 400 * scale
        offsetY = viewport.centerY() - 400 * scale
        // Insets place gameplay safely; scenery continues beneath the HUD and Fire.
        // Only the physical screen edges clip drawing.
        val visibleMinX = -offsetX / scale
        val visibleMinY = -offsetY / scale
        val visibleWidth = width / scale
        val visibleHeight = height / scale
        val visibleMaxY = visibleMinY + visibleHeight
        c.save(); c.translate(offsetX, offsetY); c.scale(scale, scale)
        val scroll = if (reducedMotion) 0.0 else game.scroll
        val rippleCount = max(76, min(240, (visibleWidth * visibleHeight / 8400).toInt()))
        for (i in 0 until rippleCount) {
            val x = visibleMinX + (i * 137.0) % visibleWidth
            val y = visibleMinY - 20 + (i * 83 + scroll * .65) % (visibleHeight + 40)
            line(c, x - 9, y, x + 9, y + 1, "#65B9B5", 2f)
        }
        val shorePhase = scroll * .55 % 480
        val firstIsland = floor((visibleMinY - shorePhase - 400) / 480).toInt()
        val lastIsland = ceil((visibleMaxY - shorePhase + 160) / 480).toInt()
        for (i in firstIsland..lastIsland) {
            val y = i * 480 + shorePhase
            island(c, 0.0, y); island(c, 800.0, y + 240)
        }
        paint.pathEffect = laneDashes
        for (i in 0..4) line(c, 148.0 + i * 126, visibleMinY.toDouble(), 148.0 + i * 126, visibleMaxY.toDouble(), "#529E9F", 1.5f)
        paint.pathEffect = null
        if (game.status == Status.READY) {
            rock(c, 211.0, 230.0); boat(c, 589.0, 170.0, true)
            heart(c, 211.0, 530.0)
        }
        for (e in game.entities) if (!e.dead) {
            when (e.kind) {
                Kind.ROCK -> rock(c, e.x, e.y)
                Kind.PIRATE -> {
                    pirateWarning(c, e)
                    boat(c, e.x, e.y, true, e.hit > 0, e.pirateType)
                    pirateHealth(c, e)
                    if (e.hp == 1) line(c, e.x - 15, e.y + 15, e.x + 15, e.y - 15, "#F8EED6", 3f)
                    if (e.flash > 0) oval(c, e.x, e.y + 60, 16.0, 19.0, "#FFE2A2")
                }
                Kind.HEART -> heart(c, e.x, e.y)
            }
        }
        for (b in game.bullets) if (!b.dead) {
            if (b.mine) {
                val color = if (b.armTime > 0) "#E8D6A6" else "#FFBD73"
                oval(c, b.x, b.y + 6, 25.0, 14.0, "#80BDE6CC")
                for (i in 0..7) { val a = i * PI / 4; line(c, b.x + cos(a) * 13, b.y + sin(a) * 13, b.x + cos(a) * 22, b.y + sin(a) * 22, color, 4f) }
                oval(c, b.x, b.y, 16.0, 16.0, "#293E3D")
                line(c, b.x - 5, b.y - 5, b.x + 5, b.y + 5, color, 3f)
                line(c, b.x + 5, b.y - 5, b.x - 5, b.y + 5, color, 3f)
                continue
            }
            val speed = hypot(b.vx, b.vy).coerceAtLeast(1.0)
            line(c, b.x - b.vx / speed * 30, b.y - b.vy / speed * 30, b.x, b.y, if (b.enemy) "#EEBC87" else "#FFF0C6", 4f)
            oval(c, b.x, b.y, if (b.enemy) b.radius else 4.0, if (b.enemy) b.radius else 6.0, if (b.enemy) "#29343B" else "#FFF0C6")
        }
        val jump = if (game.jumping) sin((1 - game.jumpTime / Game.JUMP_DURATION) * PI) else 0.0
        oval(c, game.x, Game.PLAYER_Y + 15, 34.0, 26.0, "#246A72")
        if (game.status != Status.OVER) {
            c.save(); c.translate(game.x.toFloat(), (Game.PLAYER_Y - jump * 28).toFloat())
            c.rotate(if (reducedMotion) 0f else (game.tilt * 180 / PI).toFloat())
            val size = 1 + jump * .16; c.scale(size.toFloat(), size.toFloat())
            if (game.invincible == 0.0 || (game.elapsed * 12).toInt() % 2 == 0) boat(c, 0.0, 0.0, false)
            c.restore()
        }
        c.restore()
    }
    private fun fill(color: String) { paint.color = Color.parseColor(color); paint.style = Paint.Style.FILL }
    private fun oval(c: Canvas, x: Double, y: Double, rx: Double, ry: Double, color: String) {
        fill(color); c.drawOval((x-rx).toFloat(), (y-ry).toFloat(), (x+rx).toFloat(), (y+ry).toFloat(), paint)
    }
    private fun line(c: Canvas, x1: Double, y1: Double, x2: Double, y2: Double, color: String, width: Float) {
        fill(color); paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), paint)
    }
    private fun polygon(c: Canvas, points: DoubleArray, color: String) {
        shape.reset(); shape.moveTo(points[0].toFloat(), points[1].toFloat())
        for (i in 2 until points.size step 2) shape.lineTo(points[i].toFloat(), points[i+1].toFloat())
        shape.close(); fill(color); c.drawPath(shape, paint)
    }
    private fun island(c: Canvas, x: Double, y: Double) {
        oval(c, x, y, 112.0, 160.0, "#9BD4BA")
        oval(c, x, y, 99.0, 150.0, "#E5D39B")
        oval(c, x, y, 82.0, 130.0, "#789764")
        line(c, x, y + 20, x + 22, y - 35, "#8A6848", 8f)
        for (i in 0..5) {
            val a = i * PI / 3
            line(c, x + 22, y - 35, x + 22 + cos(a) * 44, y - 35 + sin(a) * 28, "#426F52", 12f)
        }
    }
    private fun rock(c: Canvas, x: Double, y: Double) {
        oval(c, x, y + 19, 43.0, 23.0, "#81C6BA")
        polygon(c, doubleArrayOf(x-35,y+17,x-28,y-19,x-8,y-33,x+22,y-23,x+37,y+10,x+20,y+29,x-15,y+29), "#6B7A75")
        polygon(c, doubleArrayOf(x-28,y-19,x-8,y-33,x+22,y-23,x+8,y-5,x-16,y), "#9AA399")
        line(c,x-10,y-24,x+6,y-8,"#B6BBB0",3f)
    }
    private fun pirateText(c: Canvas, text: String, x: Double, y: Double, size: Float = 12f) {
        fill("#FFF1D0"); paint.textSize = size; paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        c.drawText(text, x.toFloat(), y.toFloat(), paint)
    }
    private fun pirateWarning(c: Canvas, e: Entity) {
        if (e.warning <= 0 && !e.charging) return
        if (e.pirateType == PirateType.RAMMER) {
            fill("#33FF977B"); c.drawRect((e.x - 43).toFloat(), (e.y + 60).toFloat(), (e.x + 43).toFloat(), 950f, paint)
            var y = e.y + 100
            while (y < 950) { line(c, e.x - 14, y - 10, e.x, y + 4, "#FFE4B9", 3f); line(c, e.x, y + 4, e.x + 14, y - 10, "#FFE4B9", 3f); y += 65 }
        } else if (e.pirateType == PirateType.SNIPER) {
            paint.pathEffect = laneDashes
            line(c, e.x, e.y + 43, e.aimX, Game.PLAYER_Y, "#FFB6AC", 3f)
            paint.pathEffect = null
            fill("#FFF1CC"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            c.drawCircle(e.aimX.toFloat(), Game.PLAYER_Y.toFloat(), 26f, paint); paint.style = Paint.Style.FILL
        }
    }
    private fun pirateHealth(c: Canvas, e: Entity) {
        val captain = e.pirateType == PirateType.CAPTAIN
        val y = e.y - if (captain) 91 else 83
        fill("#173E46"); c.drawRoundRect((e.x-59).toFloat(), (y-15).toFloat(), (e.x+59).toFloat(), (y+19).toFloat(), 5f, 5f, paint)
        pirateText(c, e.pirateType.title.uppercase(java.util.Locale.ROOT), e.x, y)
        if (captain) {
            line(c, e.x-48, y+11, e.x+48, y+11, "#526265", 7f)
            if (e.hp > 0) line(c, e.x-48, y+11, e.x-48+96.0*e.hp/e.maxHp, y+11, e.pirateType.color, 7f)
            pirateText(c, "${e.hp}/${e.maxHp}" + if (e.hp <= 5) " · ENRAGED" else "", e.x, y-21)
        } else for (i in 0 until e.maxHp) {
            val x = e.x - (e.maxHp * 10 - 3) / 2.0 + i * 10
            fill(if (i < e.hp) e.pirateType.color else "#526265")
            c.drawRect(x.toFloat(), (y+7).toFloat(), (x+7).toFloat(), (y+13).toFloat(), paint)
        }
        if (e.warning > 0 || e.charging) {
            val label = when (e.pirateType) {
                PirateType.SNIPER -> "AIM LOCKED"
                PirateType.RAMMER -> if (e.charging) "CHARGE!" else "BRACE!"
                else -> if (e.attackStep % 2 == 0) "BURST INCOMING" else "SPREAD INCOMING"
            }
            fill("#442F35"); c.drawRect((e.x-63).toFloat(), (e.y+78).toFloat(), (e.x+63).toFloat(), (e.y+100).toFloat(), paint)
            pirateText(c, label, e.x, e.y+93)
        }
    }
    private fun boat(c: Canvas, x: Double, y: Double, pirate: Boolean, hit: Boolean = false, type: PirateType = PirateType.GUNNER) {
        c.save(); c.translate(x.toFloat(), y.toFloat())
        if (pirate) { c.rotate(180f); if (type == PirateType.CAPTAIN) c.scale(1.12f, 1.12f) }
        polygon(c, doubleArrayOf(0.0,-57.0,-29.0,-24.0,-31.0,27.0,-20.0,48.0,20.0,48.0,31.0,27.0,29.0,-24.0), if (hit) "#FFE2A2" else if (pirate) "#604F40" else "#EDB77D")
        polygon(c, doubleArrayOf(0.0,-45.0,-21.0,-16.0,-20.0,33.0,20.0,33.0,21.0,-16.0), if (pirate) "#96724E" else "#F8E9C4")
        line(c,0.0,-30.0,0.0,25.0,"#584D40",4f)
        polygon(c, doubleArrayOf(-24.0,-24.0,24.0,-24.0,20.0,12.0,0.0,6.0,-20.0,12.0), if (pirate) type.color else "#E77D60")
        if (pirate) {
            if (type == PirateType.SCATTER || type == PirateType.BURST) for (x in listOf(-19.0, 19.0)) line(c, x, -31.0, x, -59.0, "#29343B", 8f)
            if (type == PirateType.SNIPER) line(c, 0.0, -40.0, 0.0, -77.0, "#29343B", 8f)
            if (type == PirateType.RAMMER) polygon(c, doubleArrayOf(-18.0,-40.0,0.0,-77.0,18.0,-40.0), "#C9C3A7")
            if (type == PirateType.IRONCLAD) for (x in listOf(-27.0, 27.0)) line(c, x, -26.0, x, 30.0, "#9AB6C3", 7f)
            if (type == PirateType.MINELAYER) for (x in listOf(-20.0, 20.0)) { oval(c, x, 34.0, 8.0, 9.0, "#354E3C"); line(c, x-5, 34.0, x+5, 34.0, "#C4D6A3", 2f) }
            if (type == PirateType.CAPTAIN) polygon(c, doubleArrayOf(-12.0,30.0,-12.0,42.0,-5.0,36.0,0.0,46.0,5.0,36.0,12.0,42.0,12.0,30.0), "#F3C75F")
            oval(c,0.0,-8.0,7.0,7.0,"#F8EED6")
            oval(c,-3.0,-9.0,2.0,2.0,"#604F40"); oval(c,3.0,-9.0,2.0,2.0,"#604F40")
        } else polygon(c, doubleArrayOf(-16.0,-20.0,17.0,-20.0,12.0,-5.0,-12.0,-5.0),"#FFF4D8")
        line(c,0.0,-45.0,0.0,-61.0,"#283E46",9f)
        c.restore()
    }
    private fun heart(c: Canvas, x: Double, y: Double) {
        oval(c,x,y,27.0,27.0,"#885469")
        oval(c,x-7,y-5,8.0,8.0,"#FFB7C4"); oval(c,x+7,y-5,8.0,8.0,"#FFB7C4")
        polygon(c,doubleArrayOf(x-15,y-3,x+15,y-3,x,y+16),"#FFB7C4")
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
                if (!swiped && hypot((event.x-downX).toDouble(), (event.y-downY).toDouble()) < 20 * resources.displayMetrics.density) {
                    val x = (event.x - offsetX) / scale; val y = (event.y - offsetY) / scale
                    val target = game.entities.filter { it.kind == Kind.PIRATE && !it.dead && abs(it.x-x) < 65 && abs(it.y-y) < 75 }
                        .minByOrNull { hypot(it.x-x, it.y-y) }
                    if (target != null) game.fire(target)
                    else game.bullets.firstOrNull { it.mine && !it.dead && abs(it.x-x) < 30 && abs(it.y-y) < 30 }?.let { game.fireAt(it.x, it.y) }
                    performClick()
                }
            }
        }
        return true
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val state = "Lane ${game.lane + 1} of 4. Jump charges: ${game.jumps}."
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
