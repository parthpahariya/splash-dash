package com.parthpahariya.splashdash

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
    @Test fun pickupsRepairAndCapInventory() {
        val g = game(); g.health = 90; g.spawn(Kind.HEART, 1, Game.PLAYER_Y); g.update(0.0)
        assertEquals(100, g.health); assertEquals(50, g.score); assertEquals(1, g.heartsCollected)
        g.jumps = 5; g.spawn(Kind.JUMP, 1, Game.PLAYER_Y); g.update(0.0)
        assertEquals(5, g.jumps); assertEquals(75, g.score)
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
        assertNotEquals(pirate.lane, g.spawn(Kind.JUMP, 2)!!.lane)
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
}
