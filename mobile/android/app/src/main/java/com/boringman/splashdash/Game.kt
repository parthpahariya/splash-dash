package com.boringman.splashdash

import kotlin.math.*

/** [INTRO] shows the treasure before a voyage; [WON] means the treasure was claimed. */
enum class Status { READY, INTRO, PLAYING, PAUSED, OVER, WON }
enum class Kind { ROCK, PIRATE, HEART }
enum class PirateType(val title: String, val health: Int, val color: String, val unlock: Int, val reward: Int) {
    SCOUT("Scout", 1, "#E9CF80", 1, 125),
    GUNNER("Gunner", 2, "#B85C52", 1, 250),
    BURST("Burst Raider", 3, "#B990D4", 2, 350),
    SCATTER("Scattershot", 3, "#E69A53", 2, 350),
    SNIPER("Sharpshooter", 2, "#DF6472", 3, 300),
    CAPTAIN("Pirate Captain", 10, "#F3C75F", 4, 1500)
}
class Entity(val id: Int, val kind: Kind, val lane: Int, var y: Double, var cooldown: Double, val pirateType: PirateType = PirateType.GUNNER) {
    val x get() = Game.LANES[lane]
    val maxHp get() = pirateType.health
    var hp = maxHp
    var warning = 0.0
    var burstLeft = 0
    var burstTimer = 0.0
    var attackStep = 0
    var aimX = 337.0
    var dead = false
    var hit = 0.0
    var flash = 0.0
}
class Bullet(var x: Double, var y: Double, val vx: Double, val vy: Double, val enemy: Boolean, val damage: Int = 15) {
    val radius get() = if (damage > 15) 11.0 else 8.0
    var dead = false
}

/** The Leviathan that guards the treasure at the end of the journey. */
class Kraken(var y: Double) {
    var hp = Game.KRAKEN_HP
    var hit = 0.0
    /** Seconds until the next attack once the previous one has finished. */
    var attackTimer = 1.2
    var attackStep = 0
    /** Lanes about to be slammed, and the seconds of warning left. */
    var warningLanes: List<Int> = emptyList()
    var warning = 0.0
    /** Lanes a tentacle is slamming right now, and the seconds it stays down. */
    var slamLanes: List<Int> = emptyList()
    var slam = 0.0
    /** Seconds left while it sinks after being defeated. */
    var dying = 0.0
    val enraged get() = hp <= Game.KRAKEN_RAGE_HP
    val surfaced get() = y >= Game.KRAKEN_Y
}

/** Pure Kotlin simulation. Coordinates and rules match Game.swift on iOS. */
class Game(private val random: () -> Double = { Math.random() }, var onEvent: (String) -> Unit = {}) {
    companion object {
        val LANES = doubleArrayOf(211.0, 337.0, 463.0, 589.0)
        const val PLAYER_Y = 665.0
        const val JUMP_DURATION = 1.15
        const val PIRATE_GAP = 220.0
        /** Metres from the harbour to the treasure island, where the Kraken waits. */
        const val JOURNEY_LENGTH = 1500.0
        const val KRAKEN_HP = 40
        /** At or below this health the Kraken rages: two-lane slams and shorter warnings. */
        const val KRAKEN_RAGE_HP = 20
        /** World y of the Kraken's head once it has surfaced at the top of the lanes. */
        const val KRAKEN_Y = 90.0
        const val SLAM_DAMAGE = 30
        const val SLAM_DURATION = .4
        const val TREASURE_BONUS = 5000
        /** Seconds of the opening treasure scene before each voyage. */
        const val INTRO_DURATION = 3.5
        /** Share of the intro spent on the home shore before the fast-forward out to sea. */
        const val INTRO_LAND_SHARE = .5
        /** Seconds of sailing to the treasure after the Kraken sinks. */
        const val OUTRO_DURATION = 3.0
        /** Jumps are unlimited; after landing the boat needs this long before it can jump again. */
        const val JUMP_RECHARGE = 2.0
        /** Hull after watching an ad to continue a finished voyage. */
        const val REVIVE_HEALTH = 20
        /** Ad continues allowed per voyage. */
        const val MAX_REVIVES = 5
        /** Seconds of invincibility after a revive, so the boat is not hit straight away. */
        const val REVIVE_GRACE = 2.0
        /** Hazards closer than this above the boat are cleared on revive. */
        const val REVIVE_CLEARANCE = 360.0
        /** A lane never holds more than this many rocks back to back. */
        const val MAX_ROCKS_IN_A_ROW = 2
        /**
         * Rocks closer than this (world units) belong to the same run. Hearts and ships between
         * them do not break a run, because they can be collected or sunk, leaving the rocks adjacent.
         */
        const val ROCK_RUN_GAP = 360.0
    }
    var status = Status.READY
    var health = 100
    var lane = 1
    var x = LANES[1]
    var tilt = 0.0
    /**
     * World-space y of the screen's top and bottom edges, set by the renderer. Objects enter just
     * above the top edge and leave just below the bottom edge, so nothing pops in or out
     * mid-screen. Defaults match the 800-unit playfield for tests and headless use.
     */
    var visibleTop = 0.0
    var visibleBottom = 800.0
    val spawnY get() = visibleTop - 80
    var jumpTime = 0.0
    var jumpCooldown = 0.0
    var invincible = 0.0
    var fireCooldown = 0.0
    var firing = false
    var distance = 0.0
    var bonus = 0
    var elapsed = 0.0
    var scroll = 0.0
    var level = 1
    var lastSunkName = "Gunner"
    var lastSunkReward = 250
    var piratesSunk = 0
    var heartsCollected = 0
    /** Times this voyage was continued after an ad, up to [MAX_REVIVES]. */
    var revives = 0
    /** The Kraken fight has begun; normal rocks and pirates stop. */
    var bossActive = false
    var kraken: Kraken? = null
    /** Seconds left in the opening scene ([Status.INTRO]) and the sail to the treasure. */
    var introTime = 0.0
    var outro = 0.0
    /** 0 at the harbour, 1 at the treasure island. */
    val journeyProgress get() = min(1.0, distance / JOURNEY_LENGTH)
    val canRevive get() = status == Status.OVER && revives < MAX_REVIVES
    val revivesLeft get() = max(0, MAX_REVIVES - revives)
    val entities = mutableListOf<Entity>()
    val bullets = mutableListOf<Bullet>()
    private var spawnTimer = 1.3
    private var pickupTimer = 4.8
    private var pirateTimer = 5.0
    private var nextID = 0
    private var lastCaptainDistance = -900.0
    val score get() = floor(distance * 2).toInt() + bonus
    val speed get() = 125.0 + min(145, (level - 1) * 17)
    val jumping get() = jumpTime > 0
    val jumpReady get() = !jumping && jumpCooldown <= 0
    /** 0 right after takeoff, 1 when the next jump is available. */
    val jumpCharge get() = 1 - min(1.0, jumpCooldown / (JUMP_DURATION + JUMP_RECHARGE))

    fun reset() {
        status = Status.PLAYING
        health = 100; lane = 1; x = LANES[1]; tilt = 0.0
        jumpTime = 0.0; jumpCooldown = 0.0; invincible = 0.0; fireCooldown = 0.0; firing = false
        distance = 0.0; bonus = 0; elapsed = 0.0; scroll = 0.0; level = 1
        lastSunkName = "Gunner"; lastSunkReward = 250
        piratesSunk = 0; heartsCollected = 0; revives = 0
        bossActive = false; kraken = null; introTime = 0.0; outro = 0.0
        entities.clear(); bullets.clear()
        spawnTimer = 1.3; pickupTimer = 4.8; pirateTimer = 5.0; nextID = 0; lastCaptainDistance = -900.0
        onEvent("start")
    }

    /** Changes lane; the boat cannot steer while it is in the air. */
    fun move(direction: Int) {
        if (status == Status.PLAYING && !jumping) lane = (lane + direction.sign).coerceIn(0, 3)
    }
    fun jump(): Boolean {
        if (status != Status.PLAYING || !jumpReady) return false
        jumpTime = JUMP_DURATION; jumpCooldown = JUMP_DURATION + JUMP_RECHARGE
        onEvent("jump"); return true
    }
    fun fire(target: Entity? = null): Boolean {
        if (status != Status.PLAYING || fireCooldown > 0) return false
        if (target != null && (target.kind != Kind.PIRATE || target.dead || target !in entities)) return false
        return fireAt(target?.x ?: x, target?.y ?: (PLAYER_Y - 100))
    }
    fun fireAt(targetX: Double, targetY: Double): Boolean {
        if (status != Status.PLAYING || fireCooldown > 0) return false
        val y = PLAYER_Y - 49
        val dx = targetX - x; val dy = targetY - y; val d = hypot(dx, dy).coerceAtLeast(1.0)
        bullets.add(Bullet(x, y, dx / d * 650, dy / d * 650, false))
        fireCooldown = .25; onEvent("fire"); return true
    }
    /**
     * Continues a finished voyage (after a rewarded ad) with score, distance and level kept.
     * Nearby hazards and enemy shots are cleared and the boat gets a short grace period.
     */
    fun revive(): Boolean {
        if (!canRevive) return false
        status = Status.PLAYING
        health = REVIVE_HEALTH; invincible = REVIVE_GRACE
        jumpTime = 0.0; jumpCooldown = 0.0; fireCooldown = 0.0; firing = false
        entities.removeAll { it.kind != Kind.HEART && it.y > PLAYER_Y - REVIVE_CLEARANCE }
        bullets.removeAll { it.enemy }
        kraken?.let { it.warningLanes = emptyList(); it.warning = 0.0; it.slamLanes = emptyList(); it.slam = 0.0; it.attackTimer = 2.0 }
        revives++
        onEvent("revive")
        return true
    }
    /** Starts a new voyage with the opening treasure scene. */
    fun beginVoyage() { reset(); status = Status.INTRO; introTime = INTRO_DURATION }
    /** Ends the opening scene early (a tap) and starts sailing. */
    fun skipIntro() { if (status == Status.INTRO) { introTime = 0.0; status = Status.PLAYING } }
    private fun startBoss() { bossActive = true; kraken = Kraken(-260.0); onEvent("kraken") }

    /** The Kraken rises, then cycles slam, hurled rocks and sweep; it rages at half health. */
    private fun updateKraken(k: Kraken, dt: Double) {
        k.hit = max(0.0, k.hit - dt)
        if (k.dying > 0) {
            k.dying = max(0.0, k.dying - dt)
            // The sea clears as the boat sails up to the treasure island.
            if (k.dying == 0.0) { kraken = null; entities.clear(); bullets.clear(); outro = OUTRO_DURATION; onEvent("treasure") }
            return
        }
        if (!k.surfaced) { k.y = min(KRAKEN_Y, k.y + 140 * dt); return }
        if (k.slam > 0) {
            k.slam = max(0.0, k.slam - dt)
            if (k.slamLanes.any { abs(LANES[it] - x) < 43 }) damage(SLAM_DAMAGE)
            if (k.slam == 0.0) k.slamLanes = emptyList()
            return
        }
        if (k.warning > 0) {
            k.warning = max(0.0, k.warning - dt)
            if (k.warning == 0.0) { k.slamLanes = k.warningLanes; k.warningLanes = emptyList(); k.slam = SLAM_DURATION; onEvent("slam") }
            return
        }
        k.attackTimer -= dt
        if (k.attackTimer > 0) return
        val others = (0..3).filter { it != lane }
        when (k.attackStep % 3) {
            // Slam the player's lane (and a second lane when enraged).
            0 -> k.warningLanes = listOf(lane) + if (k.enraged) listOf(others[min(2, (random() * 3).toInt())]) else emptyList()
            // Hurl two rocks into clear lanes; jump them.
            1 -> {
                val y = k.y + 140
                val open = (0..3).filter { l -> entities.none { !it.dead && it.lane == l && abs(it.y - y) < 120 } }.toMutableList()
                repeat(min(2, open.size)) {
                    val l = open.removeAt(min(open.size - 1, (random() * open.size).toInt()))
                    entities.add(Entity(nextID++, Kind.ROCK, l, y, 1.0))
                }
                onEvent("hurl")
            }
            // Sweep every lane but one.
            else -> { val safe = (random() * 4).toInt() % 4; k.warningLanes = (0..3).filter { it != safe } }
        }
        if (k.warningLanes.isNotEmpty()) k.warning = if (k.enraged) .7 else 1.0
        k.attackStep++
        k.attackTimer = if (k.enraged) 1.4 else 2.0
    }
    fun pause() {
        when (status) {
            Status.PLAYING -> { status = Status.PAUSED; firing = false }
            Status.PAUSED -> status = Status.PLAYING
            else -> Unit
        }
    }
    fun damage(amount: Int): Boolean {
        // Jumping only clears rocks (see update); ships and cannonballs still hit mid-air.
        if (status != Status.PLAYING || invincible > 0) return false
        health = max(0, health - amount); invincible = 1.15; onEvent("damage")
        if (health == 0) { status = Status.OVER; firing = false; onEvent("over") }
        return true
    }
    /** Rocks in the run a new rock would join at the top of [lane]. */
    fun rockStreak(lane: Int): Int {
        var edge = spawnY
        var count = 0
        for (rock in entities.filter { !it.dead && it.lane == lane && it.kind == Kind.ROCK }.sortedBy { it.y }) {
            if (rock.y - edge >= ROCK_RUN_GAP) break
            count++; edge = rock.y
        }
        return count
    }
    fun spawn(kind: Kind, requestedLane: Int? = null, atY: Double? = null, pirateType: PirateType = PirateType.GUNNER): Entity? {
        var lane = (requestedLane ?: (random() * 4).toInt()).coerceIn(0, 3); var y = atY ?: spawnY
        if (kind == Kind.ROCK || kind == Kind.HEART) {
            lane = (0..3).map { (lane + it) % 4 }.firstOrNull { candidate ->
                entities.none { !it.dead && it.lane == candidate && it.kind == Kind.PIRATE } &&
                    (kind != Kind.ROCK || rockStreak(candidate) < MAX_ROCKS_IN_A_ROW)
            } ?: return null
        }
        if ((kind == Kind.ROCK || kind == Kind.PIRATE) && entities.any {
            !it.dead && (it.kind == Kind.ROCK || it.kind == Kind.PIRATE) && it.y < spawnY + 140 && it.lane == lane
        }) return null
        val neighbors = entities.filter { !it.dead && it.lane == lane }
        if (kind == Kind.PIRATE) {
            for (e in neighbors) {
                if (e.kind == Kind.ROCK || e.kind == Kind.HEART) y = min(y, e.y - 120)
            }
        }
        for (e in neighbors.sortedByDescending { it.y }) {
            val gap = if (kind == Kind.PIRATE && e.kind == Kind.PIRATE) PIRATE_GAP else 120.0
            if (abs(e.y - y) < gap) y = e.y - gap
        }
        // A pirate's first shot comes within a second of entering the screen.
        val cooldown = if (kind == Kind.PIRATE) random() else 1.5 + random()
        return Entity(nextID++, kind, lane, y, cooldown, pirateType).also { entities.add(it) }
    }

    fun choosePirate(): PirateType {
        val pool = mutableListOf(PirateType.GUNNER, PirateType.GUNNER, PirateType.GUNNER, PirateType.GUNNER, PirateType.SCOUT, PirateType.SCOUT, PirateType.SCOUT)
        for (type in PirateType.values()) {
            if (type.unlock > level || type == PirateType.GUNNER || type == PirateType.SCOUT) continue
            if (type == PirateType.CAPTAIN && (distance - lastCaptainDistance < 900 || entities.any { !it.dead && it.kind == Kind.PIRATE && it.pirateType == PirateType.CAPTAIN })) continue
            pool.add(type)
            if (type != PirateType.CAPTAIN) pool.add(type)
        }
        return pool[(random() * pool.size).toInt().coerceAtMost(pool.lastIndex)]
    }
    private fun enemyShot(e: Entity, angle: Double = 0.0, velocity: Double = 265.0 + level * 8, damage: Int = 15, targetX: Double = x) {
        val shotX = e.x; val shotY = e.y + 43
        val direction = atan2(PLAYER_Y - shotY, targetX - shotX) + angle
        bullets.add(Bullet(shotX, shotY, cos(direction) * velocity, sin(direction) * velocity, true, damage))
        e.flash = .12; onEvent("enemyFire")
    }
    private fun updatePirate(e: Entity, dt: Double) {
        if (e.burstLeft > 0) {
            e.burstTimer -= dt
            if (e.burstTimer <= 0) { enemyShot(e); e.burstLeft--; e.burstTimer = .18 }
            return
        }
        if (e.warning > 0) {
            e.warning = max(0.0, e.warning - dt)
            if (e.warning > 0) return
            when (e.pirateType) {
                PirateType.SNIPER -> { enemyShot(e, velocity = 480.0, damage = 25, targetX = e.aimX); e.cooldown = 3.4; return }
                PirateType.CAPTAIN -> {
                    if (e.attackStep % 2 == 0) { enemyShot(e); e.burstLeft = 2; e.burstTimer = .18 }
                    else for (angle in listOf(-.26, 0.0, .26)) enemyShot(e, angle = angle)
                    e.attackStep++; e.cooldown = if (e.hp <= 5) 1.6 else 2.7; return
                }
                else -> Unit
            }
        }
        e.cooldown -= dt
        if (e.cooldown > 0) return
        when (e.pirateType) {
            PirateType.SNIPER -> { e.aimX = x; e.warning = 1.1 }
            PirateType.CAPTAIN -> { e.aimX = x; e.warning = .65 }
            PirateType.SCATTER -> { for (angle in listOf(-.26, 0.0, .26)) enemyShot(e, angle = angle); e.cooldown = 3.2 }
            PirateType.BURST -> { enemyShot(e); e.burstLeft = 2; e.burstTimer = .18; e.cooldown = 3.2 }
            PirateType.SCOUT -> { enemyShot(e); e.cooldown = 1.4 }
            PirateType.GUNNER -> { enemyShot(e); e.cooldown = max(1.1, 2.6 - level * .1) }
        }
    }

    fun update(delta: Double) {
        val dt = delta.coerceIn(0.0, .05)
        if (status == Status.INTRO) {
            // The water is calm at the shore, then rushes past as the boat sets sail.
            val p = 1 - introTime / INTRO_DURATION
            elapsed += dt; scroll += dt * speed * (if (p < INTRO_LAND_SHARE) .2 else 4.0)
            introTime = max(0.0, introTime - dt)
            if (introTime == 0.0) status = Status.PLAYING
            return
        }
        if (status != Status.PLAYING) return
        elapsed += dt; scroll += dt * speed
        if (outro > 0) {
            // Sailing up to the treasure island: nothing can hurt the boat now.
            outro = max(0.0, outro - dt)
            if (outro == 0.0) { status = Status.WON; firing = false; onEvent("won") }
            return
        }
        if (!bossActive) {
            distance = min(JOURNEY_LENGTH, distance + dt * speed * .12)
            val nextLevel = 1 + floor(min(distance, JOURNEY_LENGTH - 1) / 300).toInt()
            if (level != nextLevel) { level = nextLevel; onEvent("level") }
            if (distance >= JOURNEY_LENGTH) startBoss()
        }
        val dx = LANES[lane] - x
        x += dx * (1 - exp(-15 * dt))
        tilt += ((dx / 170).coerceIn(-.23, .23) - tilt) * min(1.0, dt * 12)
        jumpTime = max(0.0, jumpTime - dt); jumpCooldown = max(0.0, jumpCooldown - dt); invincible = max(0.0, invincible - dt)
        fireCooldown = max(0.0, fireCooldown - dt)
        if (firing) fire()
        spawnTimer -= dt; pickupTimer -= dt; pirateTimer -= dt
        kraken?.let { updateKraken(it, dt); if (status == Status.OVER) return }
        if (spawnTimer <= 0 && !bossActive) { spawn(Kind.ROCK); spawnTimer = max(.9, 1.8 - level * .07) + random() * .5 }
        if (pickupTimer <= 0) { if (random() < .58) spawn(Kind.HEART); pickupTimer = 10 + random() * 5 } // a new heart chance every 10–15 s
        if (pirateTimer <= 0 && !bossActive) { val type = choosePirate(); if (spawn(Kind.PIRATE, pirateType = type) != null && type == PirateType.CAPTAIN) lastCaptainDistance = distance; pirateTimer = max(3.0, 7 - level * .35) + random() * 2 }
        for (e in entities) {
            var nextY = e.y + speed * dt * (if (e.kind == Kind.PIRATE) (if (e.pirateType == PirateType.CAPTAIN) .32 else .44) else 1.0)
            if (e.kind == Kind.PIRATE) for (ahead in entities) {
                if (ahead.dead || ahead.kind != Kind.PIRATE || ahead.lane != e.lane || ahead.y <= e.y) continue
                nextY = min(nextY, ahead.y - PIRATE_GAP)
            }
            e.y = max(e.y, nextY)
            e.hit = max(0.0, e.hit - dt); e.flash = max(0.0, e.flash - dt)
            // Visible ships can attack from the moment they enter until they leave the screen.
            if (e.kind == Kind.PIRATE && e.y > visibleTop && !e.dead) updatePirate(e, dt)
            if (!e.dead && abs(e.x - x) < 43 && abs(e.y - PLAYER_Y) < (if (e.kind == Kind.PIRATE) 64 else 42)) {
                when (e.kind) {
                    Kind.HEART -> { health = min(100, health + 10); heartsCollected++; bonus += 50; e.dead = true; onEvent("heart") }
                    Kind.ROCK -> if (!jumping && damage(25)) e.dead = true // the boat sails over rocks
                    Kind.PIRATE -> if (damage(30)) e.dead = true
                }
                if (status == Status.OVER) return
            }
            if (e.y > visibleBottom + 100) e.dead = true
        }
        for (b in bullets) {
            if (b.dead) continue
            b.x += b.vx * dt; b.y += b.vy * dt
            if (b.enemy) {
                if (abs(b.x - x) < 20 + b.radius && abs(b.y - PLAYER_Y) < 34 + b.radius) {
                    damage(b.damage); b.dead = true
                    if (status == Status.OVER) return
                }
            } else {
                val k = kraken
                if (k != null && k.surfaced && k.dying == 0.0 && b.y < k.y + 70 && abs(b.x - 400) < 240) {
                    b.dead = true; k.hp--; k.hit = .13
                    if (k.hp <= 0) {
                        k.dying = 1.6; k.warningLanes = emptyList(); k.slamLanes = emptyList(); k.warning = 0.0; k.slam = 0.0
                        bonus += TREASURE_BONUS; onEvent("krakenDefeated")
                    } else onEvent(if (k.hp == KRAKEN_RAGE_HP) "krakenRage" else "hit")
                    continue
                }
                for (e in entities) {
                    if (e.kind != Kind.PIRATE || e.dead || abs(e.x - b.x) > 34 || abs(e.y - b.y) > 52) continue
                    e.hp--; e.hit = .13; b.dead = true
                    if (e.hp <= 0) {
                        e.dead = true; piratesSunk++
                        lastSunkName = e.pirateType.title; lastSunkReward = e.pirateType.reward
                        bonus += lastSunkReward; onEvent("sink")
                    } else onEvent("hit")
                    break
                }
            }
            if (b.x < -30 || b.x > 830 || b.y < visibleTop - 30 || b.y > visibleBottom + 50) b.dead = true
        }
        entities.removeAll { it.dead }; bullets.removeAll { it.dead }
    }
}
