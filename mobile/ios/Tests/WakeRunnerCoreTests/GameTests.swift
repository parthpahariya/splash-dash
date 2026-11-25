import XCTest

@testable import WakeRunnerCore

final class GameTests: XCTestCase {
  private func game() -> Game {
    let g = Game(random: { 0.5 })
    g.reset()
    return g
  }
  func testSteeringIsBoundedAndSmooth() {
    let g = game()
    for _ in 0..<10 { g.move(-1) }
    XCTAssertEqual(g.lane, 0)
    let x = g.x
    g.update(0.02)
    XCTAssertLessThan(g.x, x)
    XCTAssertGreaterThan(g.x, Game.lanes[0])
    for _ in 0..<10 { g.move(1) }
    XCTAssertEqual(g.lane, 3)
  }
  func testDamageGraceAndJumpProtection() {
    let g = game()
    XCTAssertTrue(g.damage(25))
    XCTAssertFalse(g.damage(25))
    XCTAssertEqual(g.health, 75)
    g.invincible = 0
    XCTAssertTrue(g.jump())
    XCTAssertFalse(g.damage(25))
    XCTAssertFalse(g.jump())
    XCTAssertEqual(g.jumps, 1)
  }
  func testHeartsRepairWithoutReplenishingJumps() {
    let g = game()
    g.health = 90
    g.spawn(.heart, lane: 1, y: Game.playerY)
    g.update(0)
    XCTAssertEqual(g.health, 100)
    XCTAssertEqual(g.score, 50)
    XCTAssertEqual(g.heartsCollected, 1)
    XCTAssertEqual(g.jumps, 2)
  }
  func testAutomaticPickupsAreHeartsOnlyAndNeverRestoreJumps() {
    for roll in [0.1, 0.9] {
      let g = Game(random: { roll })
      g.reset()
      g.invincible = 100
      XCTAssertTrue(g.jump())
      for _ in 0..<160 { g.update(0.05) }
      XCTAssertEqual(g.jumps, 1)
      XCTAssertEqual(g.entities.contains { $0.kind == .heart }, roll < 0.58)
    }
  }
  func testTwoHitsSinkPirate() {
    let g = game()
    let e = g.spawn(.pirate, lane: 1, y: 200)!
    for _ in 0..<2 {
      g.bullets.append(Bullet(x: e.x, y: e.y, vx: 0, vy: 0, enemy: false))
      g.update(0)
    }
    XCTAssertEqual(g.score, 250)
    XCTAssertEqual(g.piratesSunk, 1)
    XCTAssertTrue(e.dead)
  }
  func testTargetedFireAndStaleTarget() {
    let g = game()
    let e = g.spawn(.pirate, lane: 3, y: 200)!
    XCTAssertTrue(g.fire(target: e))
    XCTAssertGreaterThan(g.bullets.last!.vx, 0)
    XCTAssertLessThan(g.bullets.last!.vy, 0)
    XCTAssertFalse(g.fire())
    g.fireCooldown = 0
    e.dead = true
    XCTAssertFalse(g.fire(target: e))
  }
  func testPirateAimsAcrossLanesAndFlashes() {
    let g = game()
    let e = g.spawn(.pirate, lane: 3, y: 150)!
    e.cooldown = 0
    g.update(0.01)
    let b = g.bullets.first { $0.enemy }!
    XCTAssertLessThan(b.vx, 0)
    XCTAssertGreaterThan(b.vy, 0)
    XCTAssertGreaterThan(e.flash, 0)
    for _ in 0..<15 { g.update(0.01) }
    XCTAssertEqual(e.flash, 0, accuracy: 0.00001)
  }
  func testEnemyShotsPassUnderJumpingBoat() {
    let g = game()
    g.jump()
    let b = Bullet(x: g.x, y: Game.playerY, vx: 0, vy: 200, enemy: true)
    g.bullets.append(b)
    g.update(0.01)
    XCTAssertEqual(g.health, 100)
    XCTAssertFalse(b.dead)
    XCTAssertGreaterThan(b.y, Game.playerY)
  }
  func testPauseFreezesAndReleasesFire() {
    let g = game()
    g.firing = true
    g.pause()
    g.update(1)
    XCTAssertEqual(g.elapsed, 0)
    XCTAssertFalse(g.firing)
    XCTAssertEqual(g.status, .paused)
    g.pause()
    g.update(0.01)
    XCTAssertGreaterThan(g.distance, 0)
  }
  func testLethalCollisionStopsBeforePickup() {
    let g = game()
    g.health = 25
    g.spawn(.rock, lane: 1, y: Game.playerY)
    g.entities.append(Entity(id: 99, kind: .heart, lane: 1, y: Game.playerY, cooldown: 1))
    g.update(0)
    XCTAssertEqual(g.status, .over)
    XCTAssertEqual(g.health, 0)
    XCTAssertEqual(g.heartsCollected, 0)
  }
  func testLevelAndReset() {
    let g = game()
    g.distance = 300
    g.update(0)
    XCTAssertEqual(g.level, 2)
    XCTAssertGreaterThan(g.speed, 125)
    g.damage(100)
    g.reset()
    XCTAssertEqual(g.health, 100)
    XCTAssertEqual(g.jumps, 2)
    XCTAssertEqual(g.status, .playing)
    XCTAssertTrue(g.entities.isEmpty)
    XCTAssertTrue(g.bullets.isEmpty)
  }
  func testSpawnSpacingAndOccupiedPirateLanes() {
    let g = game()
    let rock = g.spawn(.rock, lane: 1, y: -80)!
    let heart = g.spawn(.heart, lane: 1, y: -80)!
    XCTAssertGreaterThanOrEqual(abs(rock.y - heart.y), 120)
    let pirate = g.spawn(.pirate, lane: 2, y: 100)!
    XCTAssertNotEqual(pirate.lane, g.spawn(.heart, lane: 2)!.lane)
    let crowded = game()
    for lane in 0..<4 { crowded.spawn(.pirate, lane: lane, y: 100) }
    XCTAssertNil(crowded.spawn(.rock))
    XCTAssertNil(crowded.spawn(.heart))
  }
  func testLongRunKeepsPickupsAndHazardsSeparated() {
    var seed: UInt64 = 42
    let g = Game(random: {
      seed = (1_664_525 * seed + 1_013_904_223) & 0xffff_ffff
      return Double(seed) / 4_294_967_296
    })
    g.reset()
    for _ in 0..<12000 {
      g.health = 100
      g.invincible = 10
      g.update(0.05)
      let live = g.entities.filter { !$0.dead }
      for a in live {
        for b in live
        where a.id != b.id && a.lane == b.lane && !(a.kind == .pirate && b.kind == .pirate) {
          XCTAssertGreaterThanOrEqual(
            abs(a.y - b.y), 119.99, "Objects overlap: \(a.kind) and \(b.kind)")
        }
      }
    }
  }
}
