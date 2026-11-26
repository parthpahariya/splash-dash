import Foundation

// Pure Swift simulation. Coordinates and gameplay rules mirror src/engine.js.
enum GameStatus { case ready, playing, paused, over }
enum EntityKind { case rock, pirate, heart }
enum PirateType: CaseIterable {
  case scout, gunner, burst, scatter, sniper, rammer, minelayer, ironclad, captain
  var stats: (name: String, hp: Int, color: String, unlock: Int, reward: Int) {
    switch self {
    case .scout: return ("Scout", 1, "E9CF80", 1, 125)
    case .gunner: return ("Gunner", 2, "B85C52", 1, 250)
    case .burst: return ("Burst Raider", 3, "B990D4", 2, 350)
    case .scatter: return ("Scattershot", 3, "E69A53", 2, 350)
    case .sniper: return ("Sharpshooter", 2, "DF6472", 3, 300)
    case .rammer: return ("Rammer", 4, "C6403E", 3, 400)
    case .minelayer: return ("Minelayer", 3, "86B879", 3, 350)
    case .ironclad: return ("Ironclad", 6, "9AB6C3", 4, 600)
    case .captain: return ("Pirate Captain", 10, "F3C75F", 5, 1500)
    }
  }
}
final class Entity {
  let id: Int
  let kind: EntityKind
  let lane: Int
  var x: Double { Game.lanes[lane] }
  var y: Double
  var cooldown: Double
  let pirateType: PirateType
  var maxHp: Int { pirateType.stats.hp }
  var hp: Int
  var warning = 0.0
  var charging = false
  var burstLeft = 0
  var burstTimer = 0.0
  var attackStep = 0
  var aimX = 337.0
  var dead = false
  var hit = 0.0
  var flash = 0.0
  init(id: Int, kind: EntityKind, lane: Int, y: Double, cooldown: Double, pirateType: PirateType = .gunner) {
    self.pirateType = pirateType
    self.hp = pirateType.stats.hp
    self.id = id
    self.kind = kind
    self.lane = lane
    self.y = y
    self.cooldown = cooldown
  }
}
final class Bullet {
  var x: Double
  var y: Double
  let vx: Double
  let vy: Double
  let enemy: Bool
  let damage: Int
  let mine: Bool
  var armTime: Double
  var radius: Double { mine ? 18 : damage > 15 ? 11 : 8 }
  var dead = false
  init(x: Double, y: Double, vx: Double, vy: Double, enemy: Bool, damage: Int = 15, mine: Bool = false) {
    self.damage = damage
    self.mine = mine
    self.armTime = mine ? 0.8 : 0
    self.x = x
    self.y = y
    self.vx = vx
    self.vy = vy
    self.enemy = enemy
  }
}
final class Game {
  static let lanes = [211.0, 337.0, 463.0, 589.0]
  static let playerY = 665.0
  static let jumpDuration = 1.15
  static let pirateGap = 220.0
  /// Jumps are unlimited; after landing the boat needs this long before it can jump again.
  static let jumpRecharge = 2.0
  /// Hull after watching an ad to continue a finished voyage.
  static let reviveHealth = 20
  /// Seconds of invincibility after a revive, so the boat is not hit straight away.
  static let reviveGrace = 2.0
  /// Hazards closer than this above the boat are cleared on revive.
  static let reviveClearance = 360.0
  let random: () -> Double
  var onEvent: (String) -> Void = { _ in }
  var status = GameStatus.ready
  var health = 100
  var lane = 1
  var x = lanes[1]
  var tilt = 0.0
  /// World-space y of the screen's top and bottom edges, set by the renderer. Objects enter
  /// just above the top edge and leave just below the bottom edge, so nothing pops in or out
  /// mid-screen. Defaults match the 800-unit playfield for tests and headless use.
  var visibleTop = 0.0
  var visibleBottom = 800.0
  var spawnY: Double { visibleTop - 80 }
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
  /// Times this voyage was continued after an ad. Unlimited.
  var revives = 0
  var entities: [Entity] = []
  var bullets: [Bullet] = []
  private var spawnTimer = 1.3
  private var pickupTimer = 4.8
  private var pirateTimer = 5.0
  private var nextID = 0
  private var lastCaptainDistance = -900.0
  var score: Int { Int(floor(distance * 2)) + bonus }
  var speed: Double { 125 + Double(min(145, (level - 1) * 17)) }
  var jumping: Bool { jumpTime > 0 }
  var jumpReady: Bool { !jumping && jumpCooldown <= 0 }
  /// 0 right after takeoff, 1 when the next jump is available.
  var jumpCharge: Double {
    1 - min(1, jumpCooldown / (Self.jumpDuration + Self.jumpRecharge))
  }
  init(random: @escaping () -> Double = { Double.random(in: 0..<1) }) { self.random = random }
  func reset() {
    status = .playing
    health = 100
    lane = 1
    x = Self.lanes[1]
    tilt = 0
    jumpTime = 0
    jumpCooldown = 0
    invincible = 0
    fireCooldown = 0
    firing = false
    distance = 0
    bonus = 0
    elapsed = 0
    scroll = 0
    level = 1
    lastSunkName = "Gunner"
    lastSunkReward = 250
    piratesSunk = 0
    heartsCollected = 0
    revives = 0
    entities = []
    bullets = []
    spawnTimer = 1.3
    pickupTimer = 4.8
    pirateTimer = 5
    nextID = 0
    lastCaptainDistance = -900
    onEvent("start")
  }
  func move(_ direction: Int) {
    if status == .playing {
      lane = max(0, min(3, lane + (direction == 0 ? 0 : direction > 0 ? 1 : -1)))
    }
  }
  @discardableResult func jump() -> Bool {
    guard status == .playing, jumpReady else { return false }
    jumpTime = Self.jumpDuration
    jumpCooldown = Self.jumpDuration + Self.jumpRecharge
    onEvent("jump")
    return true
  }
  @discardableResult func fire(target: Entity? = nil) -> Bool {
    guard status == .playing, fireCooldown <= 0 else { return false }
    if let target = target {
      guard target.kind == .pirate, !target.dead, entities.contains(where: { $0 === target }) else {
        return false
      }
    }
    return fireAt(targetX: target?.x ?? x, targetY: target?.y ?? (Self.playerY - 100))
  }
  @discardableResult func fireAt(targetX: Double, targetY: Double) -> Bool {
    guard status == .playing, fireCooldown <= 0 else { return false }
    let y = Self.playerY - 49
    let dx = targetX - x, dy = targetY - y
    let d = max(1, hypot(dx, dy))
    bullets.append(Bullet(x: x, y: y, vx: dx / d * 650, vy: dy / d * 650, enemy: false))
    fireCooldown = 0.25
    onEvent("fire")
    return true
  }
  /// Continues a finished voyage (after a rewarded ad) with score, distance and level kept.
  /// Nearby hazards and enemy shots are cleared and the boat gets a short grace period.
  @discardableResult func revive() -> Bool {
    guard status == .over else { return false }
    status = .playing
    health = Self.reviveHealth
    invincible = Self.reviveGrace
    jumpTime = 0
    jumpCooldown = 0
    fireCooldown = 0
    firing = false
    entities.removeAll { $0.kind != .heart && $0.y > Self.playerY - Self.reviveClearance }
    bullets.removeAll { $0.enemy }
    revives += 1
    onEvent("revive")
    return true
  }
  func pause() {
    if status == .playing {
      status = .paused
      firing = false
    } else if status == .paused {
      status = .playing
    }
  }
  @discardableResult func damage(_ amount: Int) -> Bool {
    // Jumping only clears rocks (see update); ships, cannonballs and mines still hit mid-air.
    guard status == .playing, invincible <= 0 else { return false }
    health = max(0, health - amount)
    invincible = 1.15
    onEvent("damage")
    if health == 0 {
      status = .over
      firing = false
      onEvent("over")
    }
    return true
  }
  /// A lane never holds more than this many rocks back to back.
  static let maxRocksInARow = 2
  /// Rocks closer than this (world units) belong to the same run. Hearts and ships between them
  /// do not break a run, because they can be collected or sunk, leaving the rocks adjacent.
  static let rockRunGap = 360.0
  /// Rocks in the run a new rock would join at the top of `lane`.
  func rockStreak(in lane: Int) -> Int {
    var edge = spawnY
    var count = 0
    for rock in entities.filter({ !$0.dead && $0.lane == lane && $0.kind == .rock })
      .sorted(by: { $0.y < $1.y })
    {
      guard rock.y - edge < Self.rockRunGap else { break }
      count += 1
      edge = rock.y
    }
    return count
  }
  @discardableResult func spawn(
    _ kind: EntityKind, lane requestedLane: Int? = nil, y requestedY: Double? = nil, pirateType: PirateType = .gunner
  ) -> Entity? {
    var lane = max(0, min(3, requestedLane ?? Int(random() * 4)))
    var y = requestedY ?? spawnY
    if kind == .rock || kind == .heart {
      guard
        let available = (0..<4).map({ (lane + $0) % 4 }).first(where: { candidate in
          !entities.contains { !$0.dead && $0.lane == candidate && $0.kind == .pirate }
            && (kind != .rock || rockStreak(in: candidate) < Self.maxRocksInARow)
        })
      else { return nil }
      lane = available
    }
    if (kind == .rock || kind == .pirate)
      && entities.contains(where: {
        !$0.dead && ($0.kind == .rock || $0.kind == .pirate) && $0.y < spawnY + 140 && $0.lane == lane
      })
    {
      return nil
    }
    let neighbors = entities.filter { !$0.dead && $0.lane == lane }
    if kind == .pirate {
      // Rammers need the lane to themselves, regardless of spawn order.
      if neighbors.contains(where: { $0.kind == .pirate && (pirateType == .rammer || $0.pirateType == .rammer) }) { return nil }
      for e in neighbors where e.kind == .rock || e.kind == .heart { y = min(y, e.y - 120) }
    }
    for e in neighbors.sorted(by: { $0.y > $1.y }) {
      let gap = kind == .pirate && e.kind == .pirate ? Self.pirateGap : 120
      if abs(e.y - y) < gap { y = e.y - gap }
    }
    // A pirate's first shot comes within a second of entering the screen.
    let entity = Entity(
      id: nextID, kind: kind, lane: lane, y: y, cooldown: kind == .pirate ? random() : 1.5 + random(), pirateType: pirateType)
    nextID += 1
    entities.append(entity)
    return entity
  }
  func choosePirate() -> PirateType {
    var pool: [PirateType] = [.gunner, .gunner, .gunner, .gunner, .scout, .scout, .scout]
    for type in PirateType.allCases where type.stats.unlock <= level && type != .gunner && type != .scout {
      if type == .captain && (distance - lastCaptainDistance < 900 || entities.contains { !$0.dead && $0.kind == .pirate && $0.pirateType == .captain }) { continue }
      pool.append(type)
      if type != .captain { pool.append(type) }
    }
    return pool[min(pool.count - 1, Int(random() * Double(pool.count)))]
  }
  private func enemyShot(_ e: Entity, angle: Double = 0, velocity: Double? = nil, damage: Int = 15, offset: Double = 0, targetX: Double? = nil) {
    let shotX = e.x + offset, shotY = e.y + 43
    let direction = atan2(Self.playerY - shotY, (targetX ?? x) - shotX) + angle
    let v = velocity ?? Double(265 + level * 8)
    bullets.append(Bullet(x: shotX, y: shotY, vx: cos(direction) * v, vy: sin(direction) * v, enemy: true, damage: damage))
    e.flash = 0.12
    onEvent("enemyFire")
  }
  private func updatePirate(_ e: Entity, _ dt: Double) {
    if e.charging { return }
    if e.burstLeft > 0 {
      e.burstTimer -= dt
      if e.burstTimer <= 0 { enemyShot(e); e.burstLeft -= 1; e.burstTimer = 0.18 }
      return
    }
    if e.warning > 0 {
      e.warning = max(0, e.warning - dt)
      if e.warning > 0 { return }
      switch e.pirateType {
      case .rammer: e.charging = true; return
      case .sniper: enemyShot(e, velocity: 480, damage: 25, targetX: e.aimX); e.cooldown = 3.4; return
      case .captain:
        if e.attackStep % 2 == 0 { enemyShot(e); e.burstLeft = 2; e.burstTimer = 0.18 }
        else { for angle in [-0.26, 0, 0.26] { enemyShot(e, angle: angle) } }
        e.attackStep += 1
        e.cooldown = e.hp <= 5 ? 1.6 : 2.7
        return
      default: break
      }
    }
    e.cooldown -= dt
    if e.cooldown > 0 { return }
    switch e.pirateType {
    case .sniper: e.aimX = x; e.warning = 1.1
    case .rammer: if e.y < Self.playerY - 220 { e.warning = 0.9 }
    case .captain: e.aimX = x; e.warning = 0.65
    case .minelayer:
      if e.y < Self.playerY - 200 {
        bullets.append(Bullet(x: e.x, y: e.y + 80, vx: 0, vy: speed, enemy: true, damage: 25, mine: true))
        e.flash = 0.12; onEvent("enemyFire")
      }
      e.cooldown = 2.8
    case .scatter:
      for angle in [-0.26, 0, 0.26] { enemyShot(e, angle: angle) }
      e.cooldown = 3.2
    case .burst: enemyShot(e); e.burstLeft = 2; e.burstTimer = 0.18; e.cooldown = 3.2
    case .ironclad:
      for offset in [-14.0, 14.0] { enemyShot(e, velocity: 190, damage: 25, offset: offset) }
      e.cooldown = 4
    case .scout: enemyShot(e); e.cooldown = 1.4
    case .gunner: enemyShot(e); e.cooldown = max(1.1, 2.6 - Double(level) * 0.1)
    }
  }
  func update(_ delta: Double) {
    guard status == .playing else { return }
    let dt = max(0, min(0.05, delta))
    elapsed += dt
    distance += dt * speed * 0.12
    scroll += dt * speed
    let nextLevel = 1 + Int(floor(distance / 300))
    if level != nextLevel {
      level = nextLevel
      onEvent("level")
    }
    let dx = Self.lanes[lane] - x
    x += dx * (1 - exp(-15 * dt))
    tilt += (max(-0.23, min(0.23, dx / 170)) - tilt) * min(1, dt * 12)
    jumpTime = max(0, jumpTime - dt)
    jumpCooldown = max(0, jumpCooldown - dt)
    invincible = max(0, invincible - dt)
    fireCooldown = max(0, fireCooldown - dt)
    if firing { fire() }
    spawnTimer -= dt
    pickupTimer -= dt
    pirateTimer -= dt
    if spawnTimer <= 0 {
      spawn(.rock)
      spawnTimer = max(0.9, 1.8 - Double(level) * 0.07) + random() * 0.5
    }
    if pickupTimer <= 0 {
      if random() < 0.58 { spawn(.heart) }
      pickupTimer = 10 + random() * 5  // a new heart chance every 10–15 s
    }
    if pirateTimer <= 0 {
      let type = choosePirate()
      if spawn(.pirate, pirateType: type) != nil && type == .captain { lastCaptainDistance = distance }
      pirateTimer = max(3, 7 - Double(level) * 0.35) + random() * 2
    }
    for e in entities {
      var nextY = e.y + speed * dt * (e.kind == .pirate ? (e.charging ? 2.8 : e.pirateType == .captain ? 0.32 : 0.44) : 1)
      if e.kind == .pirate {
        for ahead in entities where !ahead.dead && ahead.lane == e.lane && ahead.y > e.y {
          if ahead.kind == .pirate { nextY = min(nextY, ahead.y - Self.pirateGap) }
          else if e.charging { nextY = min(nextY, ahead.y - 120) }
        }
      }
      e.y = max(e.y, nextY)
      e.hit = max(0, e.hit - dt)
      e.flash = max(0, e.flash - dt)
      // Preserve the iOS viewport rule: visible ships can attack until they leave.
      if e.kind == .pirate && e.y > visibleTop && !e.dead { updatePirate(e, dt) }
      if !e.dead && abs(e.x - x) < 43 && abs(e.y - Self.playerY) < (e.kind == .pirate ? 64 : 42) {
        switch e.kind {
        case .heart:
          health = min(100, health + 10)
          heartsCollected += 1
          bonus += 50
          e.dead = true
          onEvent("heart")
        case .rock: if !jumping && damage(25) { e.dead = true }  // the boat sails over rocks
        case .pirate: if damage(e.pirateType == .rammer && e.charging ? 40 : 30) { e.dead = true }
        }
        if status == .over { return }
      }
      if e.y > visibleBottom + 100 { e.dead = true }
    }
    for b in bullets {
      if b.dead { continue }
      if b.mine { b.armTime = max(0, b.armTime - dt) }
      b.x += b.vx * dt
      b.y += b.vy * dt
      if b.enemy {
        if b.armTime <= 0 && abs(b.x - x) < 20 + b.radius && abs(b.y - Self.playerY) < 34 + b.radius {
          damage(b.damage)
          b.dead = true
          if status == .over { return }
        }
      } else {
        if let mine = bullets.first(where: { $0.mine && !$0.dead && abs($0.x - b.x) < 24 && abs($0.y - b.y) < 24 }) {
          mine.dead = true; b.dead = true; onEvent("hit"); continue
        }
        for e in entities {
          if e.kind != .pirate || e.dead || abs(e.x - b.x) > 34 || abs(e.y - b.y) > 52 { continue }
          e.hp -= 1
          e.hit = 0.13
          b.dead = true
          if e.hp <= 0 {
            e.dead = true
            piratesSunk += 1
            lastSunkName = e.pirateType.stats.name
            lastSunkReward = e.pirateType.stats.reward
            bonus += lastSunkReward
            onEvent("sink")
          } else {
            onEvent("hit")
          }
          break
        }
      }
      if b.x < -30 || b.x > 830 || b.y < visibleTop - 30 || b.y > visibleBottom + 50 {
        b.dead = true
      }
    }
    entities.removeAll { $0.dead }
    bullets.removeAll { $0.dead }
  }
}
