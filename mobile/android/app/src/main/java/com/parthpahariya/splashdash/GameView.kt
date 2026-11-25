package com.parthpahariya.splashdash

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
        contentDescription = "Four-lane waterway. Swipe left or right to steer, swipe up to jump, or tap a pirate to aim. Custom actions also let you steer and jump."
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
        c.save(); c.clipRect(viewport); c.translate(offsetX, offsetY); c.scale(scale, scale)
        val scroll = if (reducedMotion) 0.0 else game.scroll
        for (i in 0..75) {
            val x = ((i * 137) % 800).toDouble()
            val y = ((i * 83 + scroll * .65) % 840) - 20
            line(c, x - 9, y, x + 9, y + 1, "#65B9B5", 2f)
        }
        for (i in -1..2) {
            val y = i * 480 + scroll * .55 % 480
            island(c, 0.0, y); island(c, 800.0, y + 240)
        }
        paint.pathEffect = laneDashes
        for (i in 0..4) line(c, 148.0 + i * 126, 0.0, 148.0 + i * 126, 800.0, "#529E9F", 1.5f)
        paint.pathEffect = null
        if (game.status == Status.READY) {
            rock(c, 211.0, 230.0); boat(c, 589.0, 170.0, true)
            pickup(c, 463.0, 440.0, false); pickup(c, 211.0, 530.0, true)
        }
        for (e in game.entities) if (!e.dead) {
            when (e.kind) {
                Kind.ROCK -> rock(c, e.x, e.y)
                Kind.PIRATE -> {
                    boat(c, e.x, e.y, true, e.hit > 0)
                    if (e.hp == 1) line(c, e.x - 15, e.y + 15, e.x + 15, e.y - 15, "#F8EED6", 3f)
                    if (e.flash > 0) oval(c, e.x, e.y + 60, 16.0, 19.0, "#FFE2A2")
                }
                Kind.HEART -> pickup(c, e.x, e.y, true)
                Kind.JUMP -> pickup(c, e.x, e.y, false)
            }
        }
        for (b in game.bullets) if (!b.dead) {
            val speed = hypot(b.vx, b.vy).coerceAtLeast(1.0)
            line(c, b.x - b.vx / speed * 30, b.y - b.vy / speed * 30, b.x, b.y, if (b.enemy) "#EEBC87" else "#FFF0C6", 4f)
            oval(c, b.x, b.y, if (b.enemy) 8.0 else 4.0, if (b.enemy) 8.0 else 6.0, if (b.enemy) "#29343B" else "#FFF0C6")
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
    private fun boat(c: Canvas, x: Double, y: Double, pirate: Boolean, hit: Boolean = false) {
        c.save(); c.translate(x.toFloat(), y.toFloat())
        if (pirate) c.rotate(180f)
        polygon(c, doubleArrayOf(0.0,-57.0,-29.0,-24.0,-31.0,27.0,-20.0,48.0,20.0,48.0,31.0,27.0,29.0,-24.0), if (hit) "#FFE2A2" else if (pirate) "#604F40" else "#EDB77D")
        polygon(c, doubleArrayOf(0.0,-45.0,-21.0,-16.0,-20.0,33.0,20.0,33.0,21.0,-16.0), if (pirate) "#96724E" else "#F8E9C4")
        line(c,0.0,-30.0,0.0,25.0,"#584D40",4f)
        polygon(c, doubleArrayOf(-24.0,-24.0,24.0,-24.0,20.0,12.0,0.0,6.0,-20.0,12.0), if (pirate) "#B85C52" else "#E77D60")
        if (pirate) {
            oval(c,0.0,-8.0,7.0,7.0,"#F8EED6")
            oval(c,-3.0,-9.0,2.0,2.0,"#604F40"); oval(c,3.0,-9.0,2.0,2.0,"#604F40")
        } else polygon(c, doubleArrayOf(-16.0,-20.0,17.0,-20.0,12.0,-5.0,-12.0,-5.0),"#FFF4D8")
        line(c,0.0,-45.0,0.0,-61.0,"#283E46",9f)
        c.restore()
    }
    private fun pickup(c: Canvas, x: Double, y: Double, heart: Boolean) {
        oval(c,x,y,27.0,27.0,if (heart) "#885469" else "#305E78")
        if (heart) {
            oval(c,x-7,y-5,8.0,8.0,"#FFB7C4"); oval(c,x+7,y-5,8.0,8.0,"#FFB7C4")
            polygon(c,doubleArrayOf(x-15,y-3,x+15,y-3,x,y+16),"#FFB7C4")
        } else {
            polygon(c,doubleArrayOf(x,y-16,x-13,y,x-5,y,x-5,y+15,x+5,y+15,x+5,y,x+13,y),"#B5EDFF")
        }
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
                    game.entities.filter { it.kind == Kind.PIRATE && !it.dead && abs(it.x-x) < 65 && abs(it.y-y) < 75 }
                        .minByOrNull { hypot(it.x-x, it.y-y) }?.let { game.fire(it) }
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
