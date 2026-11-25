package com.boringman.splashdash

import kotlin.math.*

enum class Status { READY, PLAYING, PAUSED, OVER }
enum class Kind { ROCK, PIRATE, HEART }
class Entity(val id: Int, val kind: Kind, val lane: Int, var y: Double, var cooldown: Double) {
    val x get() = Game.LANES[lane]
    var hp = 2
    var dead = false
    var hit = 0.0
    var flash = 0.0
}
class Bullet(var x: Double, var y: Double, val vx: Double, val vy: Double, val enemy: Boolean) {
    var dead = false
}

/** Pure Kotlin simulation. Coordinates and rules match src/engine.js. */
class Game(private val random: () -> Double = { Math.random() }, var onEvent: (String) -> Unit = {}) {
    companion object {
        val LANES = doubleArrayOf(211.0, 337.0, 463.0, 589.0)
        const val PLAYER_Y = 665.0
        const val JUMP_DURATION = 1.15
    }
    var status = Status.READY
    var health = 100
    var lane = 1
    var x = LANES[1]
    var tilt = 0.0
    var jumps = 2
    var jumpTime = 0.0
    var invincible = 0.0
    var fireCooldown = 0.0
    var firing = false
    var distance = 0.0
    var bonus = 0
    var elapsed = 0.0
    var scroll = 0.0
    var level = 1
    var piratesSunk = 0
    var heartsCollected = 0
    val entities = mutableListOf<Entity>()
    val bullets = mutableListOf<Bullet>()
    private var spawnTimer = 1.3
    private var pickupTimer = 4.8
    private var pirateTimer = 5.0
    private var nextID = 0
    val score get() = floor(distance * 2).toInt() + bonus
    val speed get() = 125.0 + min(145, (level - 1) * 17)
    val jumping get() = jumpTime > 0

    fun reset() {
        status = Status.PLAYING
        health = 100; lane = 1; x = LANES[1]; tilt = 0.0
        jumps = 2; jumpTime = 0.0; invincible = 0.0; fireCooldown = 0.0; firing = false
        distance = 0.0; bonus = 0; elapsed = 0.0; scroll = 0.0; level = 1
        piratesSunk = 0; heartsCollected = 0
        entities.clear(); bullets.clear()
        spawnTimer = 1.3; pickupTimer = 4.8; pirateTimer = 5.0; nextID = 0
        onEvent("start")
    }

    fun move(direction: Int) {
        if (status == Status.PLAYING) lane = (lane + direction.sign).coerceIn(0, 3)
    }
    fun jump(): Boolean {
        if (status != Status.PLAYING || jumping || jumps == 0) return false
        jumps--; jumpTime = JUMP_DURATION; onEvent("jump"); return true
    }
    fun fire(target: Entity? = null): Boolean {
        if (status != Status.PLAYING || fireCooldown > 0) return false
        if (target != null && (target.kind != Kind.PIRATE || target.dead || target !in entities)) return false
        val y = PLAYER_Y - 49
        var vx = 0.0; var vy = -650.0
        if (target != null) {
            val dx = target.x - x; val dy = target.y - y; val d = hypot(dx, dy)
            if (d > 0) { vx = dx / d * 650; vy = dy / d * 650 }
        }
        bullets.add(Bullet(x, y, vx, vy, false))
        fireCooldown = .25; onEvent("fire"); return true
    }
    fun pause() {
        when (status) {
            Status.PLAYING -> { status = Status.PAUSED; firing = false }
            Status.PAUSED -> status = Status.PLAYING
            else -> Unit
        }
    }
    fun damage(amount: Int): Boolean {
        if (status != Status.PLAYING || invincible > 0 || jumping) return false
        health = max(0, health - amount); invincible = 1.15; onEvent("damage")
        if (health == 0) { status = Status.OVER; firing = false; onEvent("over") }
        return true
    }
    fun spawn(kind: Kind, requestedLane: Int = (random() * 4).toInt(), atY: Double = -80.0): Entity? {
        var lane = requestedLane.coerceIn(0, 3); var y = atY
        if (kind == Kind.ROCK || kind == Kind.HEART) {
            lane = (0..3).map { (lane + it) % 4 }.firstOrNull { candidate ->
                entities.none { !it.dead && it.lane == candidate && it.kind == Kind.PIRATE }
            } ?: return null
        }
        if ((kind == Kind.ROCK || kind == Kind.PIRATE) && entities.any {
            !it.dead && (it.kind == Kind.ROCK || it.kind == Kind.PIRATE) && it.y < 60 && it.lane == lane
        }) return null
        val neighbors = entities.filter { !it.dead && it.lane == lane }
        if (kind == Kind.PIRATE) for (e in neighbors) {
            if (e.kind == Kind.ROCK || e.kind == Kind.HEART) y = min(y, e.y - 120)
        }
        for (e in neighbors.sortedByDescending { it.y }) if (abs(e.y - y) < 120) y = e.y - 120
        return Entity(nextID++, kind, lane, y, 1.5 + random()).also { entities.add(it) }
    }

    fun update(delta: Double) {
        if (status != Status.PLAYING) return
        val dt = delta.coerceIn(0.0, .05)
        elapsed += dt; distance += dt * speed * .12; scroll += dt * speed
        val nextLevel = 1 + floor(distance / 300).toInt()
        if (level != nextLevel) { level = nextLevel; onEvent("level") }
        val dx = LANES[lane] - x
        x += dx * (1 - exp(-15 * dt))
        tilt += ((dx / 170).coerceIn(-.23, .23) - tilt) * min(1.0, dt * 12)
        jumpTime = max(0.0, jumpTime - dt); invincible = max(0.0, invincible - dt)
        fireCooldown = max(0.0, fireCooldown - dt)
        if (firing) fire()
        spawnTimer -= dt; pickupTimer -= dt; pirateTimer -= dt
        if (spawnTimer <= 0) { spawn(Kind.ROCK); spawnTimer = max(.9, 1.8 - level * .07) + random() * .5 }
        if (pickupTimer <= 0) { if (random() < .58) spawn(Kind.HEART); pickupTimer = 4 + random() * 3 }
        if (pirateTimer <= 0) { spawn(Kind.PIRATE); pirateTimer = max(3.0, 7 - level * .35) + random() * 2 }
        for (e in entities) {
            e.y += speed * dt * (if (e.kind == Kind.PIRATE) .44 else 1.0)
            e.hit = max(0.0, e.hit - dt); e.flash = max(0.0, e.flash - dt)
            if (e.kind == Kind.PIRATE && e.y > 45 && e.y < PLAYER_Y - 90 && !e.dead) {
                e.cooldown -= dt
                if (e.cooldown <= 0) {
                    val dxShot = x - e.x; val dy = PLAYER_Y - e.y - 43; val d = hypot(dxShot, dy)
                    val velocity = 265 + level * 8
                    bullets.add(Bullet(e.x, e.y + 43, dxShot / d * velocity, dy / d * velocity, true))
                    e.flash = .12; e.cooldown = max(1.1, 2.6 - level * .1); onEvent("enemyFire")
                }
            }
            if (!e.dead && abs(e.x - x) < 43 && abs(e.y - PLAYER_Y) < (if (e.kind == Kind.PIRATE) 64 else 42)) {
                when (e.kind) {
                    Kind.HEART -> { health = min(100, health + 25); heartsCollected++; bonus += 50; e.dead = true; onEvent("heart") }
                    else -> if (!jumping && damage(if (e.kind == Kind.ROCK) 25 else 30)) e.dead = true
                }
                if (status == Status.OVER) return
            }
            if (e.y > 900) e.dead = true
        }
        for (b in bullets) {
            b.x += b.vx * dt; b.y += b.vy * dt
            if (b.enemy) {
                if (!jumping && abs(b.x - x) < 28 && abs(b.y - PLAYER_Y) < 42) {
                    damage(15); b.dead = true
                    if (status == Status.OVER) return
                }
            } else for (e in entities) {
                if (e.kind != Kind.PIRATE || e.dead || abs(e.x - b.x) > 34 || abs(e.y - b.y) > 52) continue
                e.hp--; e.hit = .13; b.dead = true
                if (e.hp <= 0) { e.dead = true; piratesSunk++; bonus += 250; onEvent("sink") } else onEvent("hit")
                break
            }
            if (b.x < -30 || b.x > 830 || b.y < -30 || b.y > 850) b.dead = true
        }
        entities.removeAll { it.dead }; bullets.removeAll { it.dead }
    }
}
