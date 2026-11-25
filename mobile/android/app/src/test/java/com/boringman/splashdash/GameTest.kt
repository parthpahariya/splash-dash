package com.boringman.splashdash

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GameTest {
    private fun game() = Game({ .5 }).apply { reset() }
    @Test fun steeringIsBoundedAndSmooth() {
        val g = game(); repeat(10) { g.move(-1) }; assertEquals(0, g.lane)
        val x = g.x; g.update(.02); assertTrue(g.x < x && g.x > Game.LANES[0])
        repeat(10) { g.move(1) }; assertEquals(3, g.lane)
    }
    @Test fun damageGraceAndJumpProtection() {
        val g = game(); assertTrue(g.damage(25)); assertFalse(g.damage(25)); assertEquals(75, g.health)
        g.invincible = 0.0; assertTrue(g.jump()); assertFalse(g.damage(25)); assertFalse(g.jump())
        assertEquals(1, g.jumps)
    }
    @Test fun heartsRepairWithoutReplenishingJumps() {
        for (health in listOf(60, 95, 100)) {
            val g = game(); g.health = health; g.spawn(Kind.HEART, 1, Game.PLAYER_Y); g.update(0.0)
            assertEquals(minOf(100, health + 10), g.health); assertEquals(50, g.score); assertEquals(1, g.heartsCollected)
            assertEquals(2, g.jumps)
        }
    }
    @Test fun automaticPickupsAreHeartsOnlyAndNeverRestoreJumps() {
        for (roll in listOf(.1, .9)) {
            val g = Game({ roll }); g.reset(); g.invincible = 100.0
            assertTrue(g.jump())
            repeat(160) { g.update(.05) }
            assertEquals(1, g.jumps)
            assertEquals(roll < .58, g.entities.any { it.kind == Kind.HEART })
        }
    }
    @Test fun twoHitsSinkPirate() {
        val g = game(); val e = g.spawn(Kind.PIRATE, 1, 200.0)!!
        repeat(2) { g.bullets.add(Bullet(e.x, e.y, 0.0, 0.0, false)); g.update(0.0) }
        assertEquals(250, g.score); assertEquals(1, g.piratesSunk); assertTrue(e.dead)
    }
    @Test fun targetedFireAndStaleTarget() {
        val g = game(); val e = g.spawn(Kind.PIRATE, 3, 200.0)!!
        assertTrue(g.fire(e)); assertTrue(g.bullets.last().vx > 0); assertTrue(g.bullets.last().vy < 0)
        assertFalse(g.fire()); g.fireCooldown = 0.0; e.dead = true; assertFalse(g.fire(e))
    }
    @Test fun pirateAimsAcrossLanesAndFlashes() {
        val g = game(); val e = g.spawn(Kind.PIRATE, 3, 150.0)!!; e.cooldown = 0.0
        g.update(.01); val b = g.bullets.first { it.enemy }
        assertTrue(b.vx < 0 && b.vy > 0); assertTrue(e.flash > 0)
        repeat(15) { g.update(.01) }; assertEquals(0.0, e.flash, .00001)
    }
    @Test fun enemyShotsPassUnderJumpingBoat() {
        val g = game(); g.jump()
        val b = Bullet(g.x, Game.PLAYER_Y, 0.0, 200.0, true); g.bullets.add(b); g.update(.01)
        assertEquals(100, g.health); assertFalse(b.dead); assertTrue(b.y > Game.PLAYER_Y)
    }
    @Test fun pauseFreezesAndReleasesFire() {
        val g = game(); g.firing = true; g.pause(); g.update(1.0)
        assertEquals(0.0, g.elapsed, 0.0); assertFalse(g.firing); assertEquals(Status.PAUSED, g.status)
        g.pause(); g.update(.01); assertTrue(g.distance > 0)
    }
    @Test fun lethalCollisionStopsBeforePickup() {
        val g = game(); g.health = 25; g.spawn(Kind.ROCK, 1, Game.PLAYER_Y)
        // Deliberately insert overlapping objects to exercise the lethal-frame guard.
        g.entities.add(Entity(99, Kind.HEART, 1, Game.PLAYER_Y, 1.0)); g.update(0.0)
        assertEquals(Status.OVER, g.status); assertEquals(0, g.health); assertEquals(0, g.heartsCollected)
    }
    @Test fun levelAndReset() {
        val g = game(); g.distance = 300.0; g.update(0.0); assertEquals(2, g.level); assertTrue(g.speed > 125)
        g.damage(100); g.reset(); assertEquals(100, g.health); assertEquals(2, g.jumps)
        assertEquals(Status.PLAYING, g.status); assertTrue(g.entities.isEmpty()); assertTrue(g.bullets.isEmpty())
    }
    @Test fun spawnSpacingAndOccupiedPirateLanes() {
        val g = game(); val rock = g.spawn(Kind.ROCK, 1, -80.0)!!
        val heart = g.spawn(Kind.HEART, 1, -80.0)!!; assertTrue(abs(rock.y-heart.y) >= 120)
        val pirate = g.spawn(Kind.PIRATE, 2, 100.0)!!
        assertNotEquals(pirate.lane, g.spawn(Kind.HEART, 2)!!.lane)
        val crowded = game(); for (lane in 0..3) crowded.spawn(Kind.PIRATE, lane, 100.0)
        assertNull(crowded.spawn(Kind.ROCK)); assertNull(crowded.spawn(Kind.HEART))
    }
    @Test fun longRunKeepsPickupsAndHazardsSeparated() {
        var seed = 42L
        val g = Game({ seed = (1664525 * seed + 1013904223) and 0xffffffffL; seed.toDouble() / 4294967296.0 })
        g.reset()
        repeat(12000) {
            g.health = 100; g.invincible = 10.0; g.update(.05)
            val live = g.entities.filter { !it.dead }
            for (a in live) for (b in live) if (a.id != b.id && a.lane == b.lane && !(a.kind == Kind.PIRATE && b.kind == Kind.PIRATE)) {
                assertTrue("Objects overlap: ${a.kind} and ${b.kind}", abs(a.y-b.y) >= 119.99)
            }
        }
    }
    private fun advance(g: Game, seconds: Double) { repeat(kotlin.math.ceil(seconds / .01).toInt()) { g.update(.01) } }
    @Test fun allPirateHealthAndSinkRewards() {
        assertEquals(listOf(1,2,3,3,2,4,3,6,10), PirateType.values().map { it.health })
        for (type in PirateType.values()) {
            val g = game(); val e = g.spawn(Kind.PIRATE, 1, 300.0, type)!!
            assertEquals(type.health, e.hp)
            repeat(type.health) { hit ->
                g.bullets.add(Bullet(e.x, e.y, 0.0, 0.0, false)); g.update(0.0)
                assertEquals(type.health-hit-1, e.hp); assertEquals(hit+1 == type.health, e.dead)
            }
            assertEquals(type.reward, g.bonus); assertEquals(type.reward, g.lastSunkReward)
            assertEquals(type.title, g.lastSunkName); assertEquals(1, g.piratesSunk)
        }
    }
    @Test fun gradualUnlocksAndOneCaptainAtATime() {
        var roll = 0.0; val g = Game({ roll }); g.reset()
        for (level in 1..5) {
            g.level = level; g.distance = (level-1)*300.0
            val seen = (0..99).map { roll = it/100.0; g.choosePirate() }.toSet()
            assertEquals(PirateType.values().filter { it.unlock <= level }.toSet(), seen)
        }
        g.spawn(Kind.PIRATE, 0, 150.0, PirateType.CAPTAIN); roll = .999
        assertNotEquals(PirateType.CAPTAIN, g.choosePirate())
    }
    @Test fun scoutBurstAndSpreadAttacks() {
        val scout = game(); val s = scout.spawn(Kind.PIRATE, 0, 150.0, PirateType.SCOUT)!!
        s.cooldown = 0.0; scout.update(.01); assertEquals(1, scout.bullets.size); assertEquals(1.4, s.cooldown, .001)
        val burst = game(); val b = burst.spawn(Kind.PIRATE, 0, 150.0, PirateType.BURST)!!
        b.cooldown = 0.0; burst.update(.01); assertEquals(1, burst.bullets.size)
        advance(burst, .4); assertEquals(3, burst.bullets.size); advance(burst, .6); assertEquals(3, burst.bullets.size)
        val spread = game(); val e = spread.spawn(Kind.PIRATE, 1, 150.0, PirateType.SCATTER)!!
        e.cooldown = 0.0; spread.update(.01); assertEquals(3, spread.bullets.size)
        assertTrue(spread.bullets[0].vx > spread.bullets[1].vx && spread.bullets[1].vx > spread.bullets[2].vx)
    }
    @Test fun sniperWarnsThenFiresAtLockedPosition() {
        val g = game(); val e = g.spawn(Kind.PIRATE, 0, 150.0, PirateType.SNIPER)!!
        e.cooldown = 0.0; g.update(.01); assertEquals(1.1, e.warning, .001); assertTrue(g.bullets.isEmpty())
        val aim = e.aimX; g.lane = 3; g.x = Game.LANES[3]; advance(g, 1.11)
        val shot = g.bullets[0]; assertEquals(25, shot.damage)
        assertEquals(480.0, kotlin.math.hypot(shot.vx, shot.vy), .001)
        assertEquals(aim, shot.x+shot.vx*(Game.PLAYER_Y-shot.y)/shot.vy, .001)
    }
    @Test fun rammerWarnsChargesInLaneAndKeepsSpacing() {
        val g = game(); val e = g.spawn(Kind.PIRATE, 0, 150.0, PirateType.RAMMER)!!
        e.cooldown = 0.0; g.update(.01); assertEquals(.9, e.warning, .001); assertFalse(e.charging)
        advance(g, .91); assertTrue(e.charging); assertTrue(g.bullets.isEmpty())
        val y = e.y; advance(g, .1); assertTrue(e.y-y > g.speed*.2); assertEquals(Game.LANES[0], e.x, .001)
        e.y = Game.PLAYER_Y; g.lane = 0; g.x = e.x; g.update(0.0); assertEquals(60, g.health)
        for (kind in listOf(Kind.ROCK, Kind.HEART)) {
            val spaced = game(); val obstacle = spaced.spawn(kind, 0, 350.0)!!
            val rammer = spaced.spawn(Kind.PIRATE, 0, 150.0, PirateType.RAMMER)!!
            rammer.charging = true; advance(spaced, 1.0); assertTrue(obstacle.y-rammer.y >= 120)
        }
    }
    @Test fun minesArmBeforeDamageAndCanBeShot() {
        val g = game(); val e = g.spawn(Kind.PIRATE, 0, 150.0, PirateType.MINELAYER)!!
        e.cooldown = 0.0; g.update(.01); val mine = g.bullets[0]
        assertTrue(mine.mine && mine.armTime > 0); assertTrue(g.fireAt(mine.x, mine.y))
        g.bullets.removeAll { it !== mine }; g.entities.clear()
        mine.x = g.x; mine.y = Game.PLAYER_Y-90
        advance(g, .3); assertEquals(100, g.health); advance(g, .5); assertEquals(75, g.health)
        val clear = game(); val layer = clear.spawn(Kind.PIRATE, 0, 150.0, PirateType.MINELAYER)!!
        layer.cooldown = 0.0; clear.update(.01); val target = clear.bullets[0]
        clear.bullets.add(Bullet(target.x, target.y, 0.0, 0.0, false)); clear.update(0.0)
        assertTrue(target.dead); assertEquals(0, clear.piratesSunk)
    }
    @Test fun ironcladHeavyPairAndCaptainPhases() {
        val g = game(); val iron = g.spawn(Kind.PIRATE, 0, 150.0, PirateType.IRONCLAD)!!
        iron.cooldown = 0.0; g.update(.01); assertEquals(2, g.bullets.size); assertEquals(4.0, iron.cooldown, .001)
        for (shot in g.bullets) { assertEquals(25, shot.damage); assertEquals(190.0, kotlin.math.hypot(shot.vx, shot.vy), .001) }
        val boss = game(); val captain = boss.spawn(Kind.PIRATE, 0, 150.0, PirateType.CAPTAIN)!!
        captain.cooldown = 0.0; boss.update(.01); assertEquals(.65, captain.warning, .001)
        advance(boss, .66); assertEquals(2, captain.burstLeft); advance(boss, .4); assertEquals(3, boss.bullets.size)
        boss.bullets.clear(); captain.cooldown = 0.0; captain.hp = 5; boss.update(.01); advance(boss, .65)
        assertEquals(3, boss.bullets.size); assertEquals(2, captain.attackStep); assertEquals(1.6, captain.cooldown, .011)
    }
    @Test fun pauseFreezesNewAttacksAndSinkCancelsWarning() {
        val g = game(); val e = g.spawn(Kind.PIRATE, 0, 150.0, PirateType.SNIPER)!!
        e.cooldown = 0.0; g.update(.01); g.pause(); advance(g, 2.0); assertEquals(1.1, e.warning, .001)
        g.pause(); e.hp = 1; g.bullets.add(Bullet(e.x, e.y, 0.0, 0.0, false)); g.update(0.0)
        advance(g, 1.2); assertTrue(g.bullets.isEmpty())
        for (type in listOf(PirateType.BURST, PirateType.MINELAYER)) {
            val paused = game(); val p = paused.spawn(Kind.PIRATE, 0, 150.0, type)!!
            p.cooldown = 0.0; paused.update(.01); val timer = p.burstTimer; val arm = paused.bullets[0].armTime
            paused.pause(); advance(paused, 1.0)
            assertEquals(timer, p.burstTimer, .001); assertEquals(arm, paused.bullets[0].armTime, .001)
        }
    }

    @Test fun fasterPiratesCannotCatchCaptain() {
        val g = game(); val captain = g.spawn(Kind.PIRATE, 0, 350.0, PirateType.CAPTAIN)!!
        captain.cooldown = 1000.0
        val gunner = g.spawn(Kind.PIRATE, 0, 200.0, PirateType.GUNNER)!!
        assertTrue(captain.y - gunner.y >= Game.PIRATE_GAP)
        advance(g, 2.0); assertTrue(captain.y - gunner.y >= Game.PIRATE_GAP)
    }
    @Test fun rammersCannotShareLaneWithAnyLivePirate() {
        for (type in PirateType.values()) {
            for ((first, second) in listOf(type to PirateType.RAMMER, PirateType.RAMMER to type)) {
                val g = game(); val existing = g.spawn(Kind.PIRATE, 0, 500.0, first)!!
                assertNull(g.spawn(Kind.PIRATE, 0, -80.0, second))
                assertEquals(1, g.entities.size)
                assertNotNull(g.spawn(Kind.PIRATE, 1, -80.0, second))
                existing.dead = true
                assertNotNull(g.spawn(Kind.PIRATE, 0, -80.0, second))
            }
        }
    }
    @Test fun pendingAttacksFinishWhenShipPassesPlayerApproach() {
        for (type in listOf(PirateType.SNIPER, PirateType.BURST)) {
            val g = game(); g.distance = 3000.0
            val e = g.spawn(Kind.PIRATE, 0, 550.0, type)!!; e.cooldown = 0.0
            g.update(.01); advance(g, if (type == PirateType.SNIPER) 1.11 else .4)
            assertEquals(0.0, e.warning, .001); assertEquals(0, e.burstLeft)
            assertTrue(g.bullets.any { it.enemy })
        }
    }

}
