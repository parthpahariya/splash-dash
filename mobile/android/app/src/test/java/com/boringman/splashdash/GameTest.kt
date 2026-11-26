package com.boringman.splashdash

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GameTest {
    private fun game() = Game({ .5 }).apply { reset() }
    @Test fun noSteeringMidAir() {
        val g = game(); assertTrue(g.jump())
        g.move(1); g.move(-1); assertEquals("lane is locked while jumping", 1, g.lane)
        repeat(24) { g.update(.05) } // land after 1.15 s
        assertFalse(g.jumping); g.move(1); assertEquals("steering works again after landing", 2, g.lane)
    }
    @Test fun steeringIsBoundedAndSmooth() {
        val g = game(); repeat(10) { g.move(-1) }; assertEquals(0, g.lane)
        val x = g.x; g.update(.02); assertTrue(g.x < x && g.x > Game.LANES[0])
        repeat(10) { g.move(1) }; assertEquals(3, g.lane)
    }
    @Test fun damageGraceAndJumpProtection() {
        val g = game(); assertTrue(g.damage(25)); assertFalse(g.damage(25)); assertEquals(75, g.health)
        g.invincible = 0.0; assertTrue(g.jump()); assertFalse(g.jump()); assertFalse(g.jumpReady)
    }
    @Test fun jumpIsUnlimitedAfterRecharge() {
        val g = game(); g.invincible = 1000.0
        repeat(5) {
            assertTrue(g.jump()); assertEquals(0.0, g.jumpCharge, .001)
            repeat(24) { g.update(.05) } // just over the 1.15 s in the air
            assertFalse(g.jumping); assertFalse("still recharging just after landing", g.jump())
            repeat(40) { g.update(.05) } // past the 2 s recharge
            assertTrue(g.jumpReady); assertEquals(1.0, g.jumpCharge, .001)
        }
    }
    @Test fun reviveContinuesVoyageAfterAd() {
        val g = game(); g.distance = 400.0; g.bonus = 300; g.update(0.0)
        val score = g.score; val level = g.level
        val near = g.spawn(Kind.ROCK, 0, Game.PLAYER_Y - 100)!!
        val far = g.spawn(Kind.ROCK, 2, Game.PLAYER_Y - 500)!!
        val heart = g.spawn(Kind.HEART, 3, Game.PLAYER_Y - 100)!!
        g.bullets.add(Bullet(g.x, 300.0, 0.0, 100.0, true)); g.bullets.add(Bullet(g.x, 300.0, 0.0, -650.0, false))
        assertFalse("only a finished voyage can be revived", g.revive())
        g.damage(100); assertEquals(Status.OVER, g.status)
        var revived = false; g.onEvent = { if (it == "revive") revived = true }
        assertTrue(g.revive()); assertTrue(revived)
        assertEquals(Status.PLAYING, g.status); assertEquals(Game.REVIVE_HEALTH, g.health)
        assertEquals(score, g.score); assertEquals(level, g.level); assertEquals(1, g.revives)
        assertFalse(near in g.entities); assertTrue(far in g.entities); assertTrue(heart in g.entities)
        assertTrue(g.bullets.none { it.enemy }); assertEquals(1, g.bullets.size)
        assertFalse("grace period after reviving", g.damage(25))
        // Up to five continues per voyage.
        repeat(Game.MAX_REVIVES - 1) { g.invincible = 0.0; g.damage(100); assertTrue(g.canRevive); assertTrue(g.revive()) }
        assertEquals(Game.MAX_REVIVES, g.revives); assertEquals(0, g.revivesLeft)
        g.invincible = 0.0; g.damage(100)
        assertFalse(g.canRevive); assertFalse("no sixth continue", g.revive()); assertEquals(Status.OVER, g.status)
        g.reset(); assertEquals(0, g.revives); assertEquals(Game.MAX_REVIVES, g.revivesLeft)
    }
    @Test fun jumpRechargePausesWithGame() {
        val g = game(); assertTrue(g.jump()); g.pause(); g.update(5.0); assertFalse(g.jumpReady)
    }
    @Test fun objectsEnterAndLeaveAtScreenEdges() {
        val g = game(); g.visibleTop = -220.0; g.visibleBottom = 1060.0
        val rock = g.spawn(Kind.ROCK, 0)!!; assertEquals(-300.0, rock.y, 0.0)
        rock.y = 1000.0; g.update(0.0); assertTrue("still on screen above the bottom edge", rock in g.entities)
        rock.y = 1170.0; g.update(0.0); assertFalse(rock in g.entities)
        g.bullets.add(Bullet(337.0, -100.0, 0.0, 0.0, false)); g.update(0.0)
        assertEquals("shots keep flying to the top edge", 1, g.bullets.size)
    }
    @Test fun atMostTwoRocksInARowPerLane() {
        val g = game()
        g.spawn(Kind.ROCK, 1, 300.0); g.spawn(Kind.ROCK, 1, 100.0)
        assertEquals(2, g.rockStreak(1))
        assertNotEquals("a third rock moves to another lane", 1, g.spawn(Kind.ROCK, 1)?.lane)
        g.spawn(Kind.HEART, 1, -100.0)
        assertEquals("a heart can be collected, so it does not break the run", 2, g.rockStreak(1))
        val spaced = game()
        spaced.spawn(Kind.ROCK, 2, 700.0); spaced.spawn(Kind.ROCK, 2, 100.0)
        assertEquals("a wide gap starts a new run", 1, spaced.rockStreak(2))
        assertEquals(2, spaced.spawn(Kind.ROCK, 2)?.lane)
    }
    @Test fun seededRunNeverStacksThreeRocks() {
        var seed = 7L
        val g = Game({ seed = seed * 6364136223846793005L + 1442695040888963407L; (seed ushr 11).toDouble() / (1L shl 53).toDouble() })
        g.reset(); g.invincible = Double.POSITIVE_INFINITY
        repeat(12000) {
            g.update(.05); g.invincible = Double.POSITIVE_INFINITY
            for (lane in 0..3) {
                val rocks = g.entities.filter { it.lane == lane && it.kind == Kind.ROCK }.map { it.y }.sorted()
                var run = 0
                for ((i, y) in rocks.withIndex()) {
                    run = if (i > 0 && y - rocks[i - 1] < Game.ROCK_RUN_GAP) run + 1 else 1
                    assertTrue(run <= 2)
                }
            }
        }
    }
    @Test fun piratesFireFromEntryUntilTheyLeave() {
        val g = game(); g.visibleTop = -200.0
        val pirate = g.spawn(Kind.PIRATE, 0)!!; assertTrue(pirate.y < g.visibleTop)
        while (pirate.y <= g.visibleTop) g.update(.02)
        assertFalse("no shots from off screen", g.bullets.any { it.enemy })
        var t = 0.0
        while (g.bullets.none { it.enemy }) { g.update(.02); t += .02 }
        assertTrue("first shot within a second of entering", t <= 1.02)
        // Still firing after passing the player's row.
        pirate.y = Game.PLAYER_Y + 60; pirate.cooldown = 0.0; g.bullets.clear(); g.update(.01)
        assertTrue(g.bullets.any { it.enemy })
    }
    @Test fun heartsRepair() {
        for (health in listOf(60, 95, 100)) {
            val g = game(); g.health = health; g.spawn(Kind.HEART, 1, Game.PLAYER_Y); g.update(0.0)
            assertEquals(minOf(100, health + 10), g.health); assertEquals(50, g.score); assertEquals(1, g.heartsCollected)
            assertTrue(g.jumpReady)
        }
    }
    @Test fun automaticPickupsAreHeartsOnly() {
        for (roll in listOf(.1, .9)) {
            val g = Game({ roll }); g.reset(); g.invincible = 100.0
            assertTrue(g.jump())
            repeat(160) { g.update(.05) }
            assertTrue(g.jumpReady)
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
    @Test fun jumpClearsRocksOnly() {
        val rocky = game(); val rock = rocky.spawn(Kind.ROCK, 1, Game.PLAYER_Y)!!
        assertTrue(rocky.jump()); rocky.update(0.0)
        assertEquals("rocks pass under the jumping boat", 100, rocky.health); assertTrue(rock in rocky.entities)
        val shot = game(); assertTrue(shot.jump())
        val b = Bullet(shot.x, Game.PLAYER_Y, 0.0, 200.0, true); shot.bullets.add(b); shot.update(.01)
        assertEquals("cannonballs still hit mid-air", 85, shot.health); assertTrue(b.dead)
        val ship = game(); ship.spawn(Kind.PIRATE, 1, Game.PLAYER_Y); assertTrue(ship.jump()); ship.update(0.0)
        assertEquals("ships still hit mid-air", 70, ship.health)
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
        g.damage(100); g.reset(); assertEquals(100, g.health); assertTrue(g.jumpReady)
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
        assertEquals(listOf(1,2,3,3,2,10), PirateType.values().map { it.health })
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
    @Test fun captainPhases() {
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
        val paused = game(); val p = paused.spawn(Kind.PIRATE, 0, 150.0, PirateType.BURST)!!
        p.cooldown = 0.0; paused.update(.01); val timer = p.burstTimer
        paused.pause(); advance(paused, 1.0)
        assertEquals(timer, p.burstTimer, .001); assertEquals(1, paused.bullets.size)
    }

    @Test fun fasterPiratesCannotCatchCaptain() {
        val g = game(); val captain = g.spawn(Kind.PIRATE, 0, 350.0, PirateType.CAPTAIN)!!
        captain.cooldown = 1000.0
        val gunner = g.spawn(Kind.PIRATE, 0, 200.0, PirateType.GUNNER)!!
        assertTrue(captain.y - gunner.y >= Game.PIRATE_GAP)
        advance(g, 2.0); assertTrue(captain.y - gunner.y >= Game.PIRATE_GAP)
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
