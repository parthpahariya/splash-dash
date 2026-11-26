import XCTest

@testable import WakeRunnerCore

final class GameTests: XCTestCase {
  private func game() -> Game {
    let g = Game(random: { 0.5 })
    g.reset()
    return g
  }
  func testNoSteeringMidAir() {
    let g = game()
    XCTAssertTrue(g.jump())
    g.move(1)
    g.move(-1)
    XCTAssertEqual(g.lane, 1, "lane is locked while jumping")
    for _ in 0..<24 { g.update(0.05) }  // land after 1.15 s
    XCTAssertFalse(g.jumping)
    g.move(1)
    XCTAssertEqual(g.lane, 2, "steering works again after landing")
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
    XCTAssertFalse(g.jump())
    XCTAssertFalse(g.jumpReady)
  }
  func testJumpIsUnlimitedAfterRecharge() {
    let g = game()
    g.invincible = 1000
    for _ in 0..<5 {
      XCTAssertTrue(g.jump())
      XCTAssertEqual(g.jumpCharge, 0, accuracy: 0.001)
      for _ in 0..<24 { g.update(0.05) }  // just over the 1.15 s in the air
      XCTAssertFalse(g.jumping)
      XCTAssertFalse(g.jump(), "still recharging just after landing")
      for _ in 0..<40 { g.update(0.05) }  // past the 2 s recharge
      XCTAssertTrue(g.jumpReady)
      XCTAssertEqual(g.jumpCharge, 1, accuracy: 0.001)
    }
  }
  func testObjectsEnterAndLeaveAtScreenEdges() {
    let g = game()
    g.visibleTop = -220
    g.visibleBottom = 1060
    let rock = g.spawn(.rock, lane: 0)!
    XCTAssertEqual(rock.y, -300)
    rock.y = 1000
    g.update(0)
    XCTAssertTrue(g.entities.contains { $0 === rock }, "still on screen above the bottom edge")
    rock.y = 1170
    g.update(0)
    XCTAssertFalse(g.entities.contains { $0 === rock })
    g.bullets.append(Bullet(x: 337, y: -100, vx: 0, vy: 0, enemy: false))
    g.update(0)
    XCTAssertEqual(g.bullets.count, 1, "shots keep flying to the top edge")
  }
  func testAtMostTwoRocksInARowPerLane() {
    let g = game()
    g.spawn(.rock, lane: 1, y: 300)
    g.spawn(.rock, lane: 1, y: 100)
    XCTAssertEqual(g.rockStreak(in: 1), 2)
    XCTAssertNotEqual(g.spawn(.rock, lane: 1)?.lane, 1, "a third rock moves to another lane")
    g.spawn(.heart, lane: 1, y: -100)
    XCTAssertEqual(g.rockStreak(in: 1), 2, "a heart can be collected, so it does not break the run")
    let spaced = game()
    spaced.spawn(.rock, lane: 2, y: 700)
    spaced.spawn(.rock, lane: 2, y: 100)
    XCTAssertEqual(spaced.rockStreak(in: 2), 1, "a wide gap starts a new run")
    XCTAssertEqual(spaced.spawn(.rock, lane: 2)?.lane, 2)
  }
  func testSeededRunNeverStacksThreeRocks() {
    var seed: UInt64 = 7
    let g = Game(random: {
      seed = seed &* 6364136223846793005 &+ 1442695040888963407
      return Double(seed >> 11) / Double(1 << 53)
    })
    g.reset()
    g.invincible = .infinity
    for _ in 0..<12000 {
      g.update(0.05)
      g.invincible = .infinity
      for lane in 0..<4 {
        let rocks = g.entities.filter { $0.lane == lane && $0.kind == .rock }.map(\.y).sorted()
        var run = 0
        for (i, y) in rocks.enumerated() {
          run = i > 0 && y - rocks[i - 1] < Game.rockRunGap ? run + 1 : 1
          XCTAssertLessThanOrEqual(run, 2)
        }
      }
    }
  }
  func testPiratesFireFromEntryUntilTheyLeave() {
    let g = game()
    g.visibleTop = -200
    let pirate = g.spawn(.pirate, lane: 0)!
    XCTAssertLessThan(pirate.y, g.visibleTop)
    while pirate.y <= g.visibleTop { g.update(0.02) }
    XCTAssertFalse(g.bullets.contains { $0.enemy }, "no shots from off screen")
    var t = 0.0
    while !g.bullets.contains(where: { $0.enemy }) {
      g.update(0.02)
      t += 0.02
    }
    XCTAssertLessThanOrEqual(t, 1.02, "first shot within a second of entering")
    // Still firing after passing the player's row.
    pirate.y = Game.playerY + 60
    pirate.cooldown = 0
    g.bullets.removeAll()
    g.update(0.01)
    XCTAssertTrue(g.bullets.contains { $0.enemy })
  }
  func testReviveContinuesVoyageAfterAd() {
    let g = game()
    g.distance = 400
    g.bonus = 300
    g.update(0)
    let score = g.score, level = g.level
    let near = g.spawn(.rock, lane: 0, y: Game.playerY - 100)!
    let far = g.spawn(.rock, lane: 2, y: Game.playerY - 500)!
    let heart = g.spawn(.heart, lane: 3, y: Game.playerY - 100)!
    g.bullets.append(Bullet(x: g.x, y: 300, vx: 0, vy: 100, enemy: true))
    g.bullets.append(Bullet(x: g.x, y: 300, vx: 0, vy: -650, enemy: false))
    XCTAssertFalse(g.revive(), "only a finished voyage can be revived")
    g.damage(100)
    XCTAssertEqual(g.status, .over)
    var revived = false
    g.onEvent = { if $0 == "revive" { revived = true } }
    XCTAssertTrue(g.revive())
    XCTAssertTrue(revived)
    XCTAssertEqual(g.status, .playing)
    XCTAssertEqual(g.health, Game.reviveHealth)
    XCTAssertEqual(g.score, score)
    XCTAssertEqual(g.level, level)
    XCTAssertEqual(g.revives, 1)
    XCTAssertFalse(g.entities.contains { $0 === near })
    XCTAssertTrue(g.entities.contains { $0 === far })
    XCTAssertTrue(g.entities.contains { $0 === heart })
    XCTAssertFalse(g.bullets.contains { $0.enemy })
    XCTAssertEqual(g.bullets.count, 1)
    XCTAssertFalse(g.damage(25), "grace period after reviving")
    // Up to five continues per voyage.
    for _ in 1..<Game.maxRevives {
      g.invincible = 0
      g.damage(100)
      XCTAssertTrue(g.canRevive)
      XCTAssertTrue(g.revive())
    }
    XCTAssertEqual(g.revives, Game.maxRevives)
    XCTAssertEqual(g.revivesLeft, 0)
    g.invincible = 0
    g.damage(100)
    XCTAssertFalse(g.canRevive)
    XCTAssertFalse(g.revive(), "no sixth continue")
    XCTAssertEqual(g.status, .over)
    g.reset()
    XCTAssertEqual(g.revives, 0)
    XCTAssertEqual(g.revivesLeft, Game.maxRevives)
  }
  func testJumpRechargePausesWithGame() {
    let g = game()
    XCTAssertTrue(g.jump())
    g.pause()
    g.update(5)
    XCTAssertFalse(g.jumpReady)
  }
  func testHeartsRepair() {
    for health in [60, 95, 100] {
      let g = game()
      g.health = health
      g.spawn(.heart, lane: 1, y: Game.playerY)
      g.update(0)
      XCTAssertEqual(g.health, min(100, health + 10))
      XCTAssertEqual(g.score, 50)
      XCTAssertEqual(g.heartsCollected, 1)
      XCTAssertTrue(g.jumpReady)
    }
  }
  func testAutomaticPickupsAreHeartsOnly() {
    for roll in [0.1, 0.9] {
      let g = Game(random: { roll })
      g.reset()
      g.invincible = 100
      XCTAssertTrue(g.jump())
      for _ in 0..<160 { g.update(0.05) }
      XCTAssertTrue(g.jumpReady)
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
  func testJumpClearsRocksOnly() {
    let rocky = game()
    let rock = rocky.spawn(.rock, lane: 1, y: Game.playerY)!
    XCTAssertTrue(rocky.jump())
    rocky.update(0)
    XCTAssertEqual(rocky.health, 100, "rocks pass under the jumping boat")
    XCTAssertTrue(rocky.entities.contains { $0 === rock })
    let shot = game()
    XCTAssertTrue(shot.jump())
    let b = Bullet(x: shot.x, y: Game.playerY, vx: 0, vy: 200, enemy: true)
    shot.bullets.append(b)
    shot.update(0.01)
    XCTAssertEqual(shot.health, 85, "cannonballs still hit mid-air")
    XCTAssertTrue(b.dead)
    let ship = game()
    ship.spawn(.pirate, lane: 1, y: Game.playerY)
    XCTAssertTrue(ship.jump())
    ship.update(0)
    XCTAssertEqual(ship.health, 70, "ships still hit mid-air")
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
    XCTAssertTrue(g.jumpReady)
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
  private func advance(_ g: Game, _ seconds: Double) {
    for _ in 0..<Int(ceil(seconds / 0.01)) { g.update(0.01) }
  }
  func testAllPiratesHaveDistinctHealthAndRewards() {
    XCTAssertEqual(PirateType.allCases.map { $0.stats.hp }, [1, 2, 3, 3, 2, 10])
    for type in PirateType.allCases {
      let g = game(), stats = type.stats
      let e = g.spawn(.pirate, lane: 1, y: 300, pirateType: type)!
      XCTAssertEqual(e.hp, stats.hp)
      for hit in 1...stats.hp {
        g.bullets.append(Bullet(x: e.x, y: e.y, vx: 0, vy: 0, enemy: false)); g.update(0)
        XCTAssertEqual(e.hp, stats.hp - hit); XCTAssertEqual(e.dead, hit == stats.hp)
      }
      XCTAssertEqual(g.bonus, stats.reward); XCTAssertEqual(g.lastSunkReward, stats.reward)
      XCTAssertEqual(g.lastSunkName, stats.name); XCTAssertEqual(g.piratesSunk, 1)
    }
  }
  func testEncounterUnlocksAndOneCaptainAtATime() {
    var roll = 0.0
    let g = Game(random: { roll }); g.reset()
    for level in 1...5 {
      g.level = level; g.distance = Double((level - 1) * 300)
      var seen = Set<PirateType>()
      for i in 0..<100 { roll = Double(i) / 100; seen.insert(g.choosePirate()) }
      XCTAssertEqual(seen, Set(PirateType.allCases.filter { $0.stats.unlock <= level }))
    }
    g.spawn(.pirate, lane: 0, y: 150, pirateType: .captain)
    roll = 0.999; XCTAssertNotEqual(g.choosePirate(), .captain)
  }
  func testScoutBurstAndSpreadAttacks() {
    let scout = game(), burst = game(), spread = game()
    let s = scout.spawn(.pirate, lane: 0, y: 150, pirateType: .scout)!
    s.cooldown = 0; scout.update(0.01)
    XCTAssertEqual(scout.bullets.count, 1); XCTAssertEqual(s.cooldown, 1.4)
    let b = burst.spawn(.pirate, lane: 0, y: 150, pirateType: .burst)!
    b.cooldown = 0; burst.update(0.01); XCTAssertEqual(burst.bullets.count, 1)
    advance(burst, 0.4); XCTAssertEqual(burst.bullets.count, 3)
    advance(burst, 0.6); XCTAssertEqual(burst.bullets.count, 3)
    let e = spread.spawn(.pirate, lane: 1, y: 150, pirateType: .scatter)!
    e.cooldown = 0; spread.update(0.01)
    XCTAssertEqual(spread.bullets.count, 3)
    XCTAssertGreaterThan(spread.bullets[0].vx, spread.bullets[1].vx)
    XCTAssertGreaterThan(spread.bullets[1].vx, spread.bullets[2].vx)
  }
  func testSniperLocksAimBeforeFiring() {
    let g = game(), e = game().spawn(.pirate) // a separate instance must never be a valid target
    XCTAssertFalse(g.fire(target: e))
    let sniper = g.spawn(.pirate, lane: 0, y: 150, pirateType: .sniper)!
    sniper.cooldown = 0; g.update(0.01)
    XCTAssertEqual(sniper.warning, 1.1); XCTAssertTrue(g.bullets.isEmpty)
    let aim = sniper.aimX; g.lane = 3; g.x = Game.lanes[3]
    advance(g, 1.11)
    let shot = g.bullets[0]
    XCTAssertEqual(shot.damage, 25); XCTAssertEqual(hypot(shot.vx, shot.vy), 480, accuracy: 0.001)
    XCTAssertEqual(shot.x + shot.vx * (Game.playerY - shot.y) / shot.vy, aim, accuracy: 0.001)
  }
  func testCaptainPhases() {
    let boss = game(); let captain = boss.spawn(.pirate, lane: 0, y: 150, pirateType: .captain)!
    captain.cooldown = 0; boss.update(0.01); XCTAssertEqual(captain.warning, 0.65)
    advance(boss, 0.66); XCTAssertEqual(captain.burstLeft, 2)
    advance(boss, 0.4); XCTAssertEqual(boss.bullets.count, 3)
    boss.bullets.removeAll(); captain.cooldown = 0; captain.hp = 5
    boss.update(0.01); advance(boss, 0.65)
    XCTAssertEqual(boss.bullets.count, 3); XCTAssertEqual(captain.attackStep, 2)
    XCTAssertEqual(captain.cooldown, 1.6, accuracy: 0.011)
  }
  func testPauseFreezesNewAttacksAndSinkingCancelsWarning() {
    let g = game(); let e = g.spawn(.pirate, lane: 0, y: 150, pirateType: .sniper)!
    e.cooldown = 0; g.update(0.01); g.pause(); advance(g, 2)
    XCTAssertEqual(e.warning, 1.1); g.pause(); e.hp = 1
    g.bullets.append(Bullet(x: e.x, y: e.y, vx: 0, vy: 0, enemy: false)); g.update(0)
    advance(g, 1.2); XCTAssertTrue(g.bullets.isEmpty)
    let paused = game(); let p = paused.spawn(.pirate, lane: 0, y: 150, pirateType: .burst)!
    p.cooldown = 0; paused.update(0.01)
    let timer = p.burstTimer
    paused.pause(); advance(paused, 1)
    XCTAssertEqual(p.burstTimer, timer); XCTAssertEqual(paused.bullets.count, 1)
  }

  func testFasterPiratesCannotCatchCaptain() {
    let g = game(); let captain = g.spawn(.pirate, lane: 0, y: 350, pirateType: .captain)!
    captain.cooldown = 1000
    let gunner = g.spawn(.pirate, lane: 0, y: 200, pirateType: .gunner)!
    XCTAssertGreaterThanOrEqual(captain.y - gunner.y, Game.pirateGap)
    advance(g, 2)
    XCTAssertGreaterThanOrEqual(captain.y - gunner.y, Game.pirateGap)
  }

}
