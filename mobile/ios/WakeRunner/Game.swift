import Foundation

// Pure Swift simulation. Coordinates and gameplay rules mirror src/engine.js.
enum GameStatus { case ready, playing, paused, over }
enum EntityKind { case rock, pirate, heart }
final class Entity {
  let id: Int
  let kind: EntityKind
  let lane: Int
  var x: Double { Game.lanes[lane] }
  var y: Double
  var cooldown: Double
  var hp = 2
  var dead = false
  var hit = 0.0
  var flash = 0.0
  init(id: Int, kind: EntityKind, lane: Int, y: Double, cooldown: Double) {
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
  var dead = false
  init(x: Double, y: Double, vx: Double, vy: Double, enemy: Bool) {
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
  let random: () -> Double
  var onEvent: (String) -> Void = { _ in }
  var status = GameStatus.ready
  var health = 100
  var lane = 1
  var x = lanes[1]
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
  var entities: [Entity] = []
  var bullets: [Bullet] = []
  private var spawnTimer = 1.3
  private var pickupTimer = 4.8
  private var pirateTimer = 5.0
  private var nextID = 0
  var score: Int { Int(floor(distance * 2)) + bonus }
  var speed: Double { 125 + Double(min(145, (level - 1) * 17)) }
  var jumping: Bool { jumpTime > 0 }
  init(random: @escaping () -> Double = { Double.random(in: 0..<1) }) { self.random = random }
  func reset() {
    status = .playing
    health = 100
    lane = 1
    x = Self.lanes[1]
    tilt = 0
    jumps = 2
    jumpTime = 0
    invincible = 0
    fireCooldown = 0
    firing = false
    distance = 0
    bonus = 0
    elapsed = 0
    scroll = 0
    level = 1
    piratesSunk = 0
    heartsCollected = 0
    entities = []
    bullets = []
    spawnTimer = 1.3
    pickupTimer = 4.8
    pirateTimer = 5
    nextID = 0
    onEvent("start")
  }
  func move(_ direction: Int) {
    if status == .playing {
      lane = max(0, min(3, lane + (direction == 0 ? 0 : direction > 0 ? 1 : -1)))
    }
  }
  @discardableResult func jump() -> Bool {
    guard status == .playing, !jumping, jumps > 0 else { return false }
    jumps -= 1
    jumpTime = Self.jumpDuration
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
    let y = Self.playerY - 49
    var vx = 0.0
    var vy = -650.0
    if let target = target {
      let dx = target.x - x
      let dy = target.y - y
      let d = hypot(dx, dy)
      if d > 0 {
        vx = dx / d * 650
        vy = dy / d * 650
      }
    }
    bullets.append(Bullet(x: x, y: y, vx: vx, vy: vy, enemy: false))
    fireCooldown = 0.25
    onEvent("fire")
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
    guard status == .playing, invincible <= 0, !jumping else { return false }
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
  @discardableResult func spawn(
    _ kind: EntityKind, lane requestedLane: Int? = nil, y requestedY: Double = -80
  ) -> Entity? {
    var lane = max(0, min(3, requestedLane ?? Int(random() * 4)))
    var y = requestedY
    if kind == .rock || kind == .heart {
      guard
        let available = (0..<4).map({ (lane + $0) % 4 }).first(where: { candidate in
          !entities.contains { !$0.dead && $0.lane == candidate && $0.kind == .pirate }
        })
      else { return nil }
      lane = available
    }
    if (kind == .rock || kind == .pirate)
      && entities.contains(where: {
        !$0.dead && ($0.kind == .rock || $0.kind == .pirate) && $0.y < 60 && $0.lane == lane
      })
    {
      return nil
    }
    let neighbors = entities.filter { !$0.dead && $0.lane == lane }
    if kind == .pirate {
      for e in neighbors where e.kind == .rock || e.kind == .heart { y = min(y, e.y - 120) }
    }
    for e in neighbors.sorted(by: { $0.y > $1.y }) where abs(e.y - y) < 120 { y = e.y - 120 }
    let entity = Entity(id: nextID, kind: kind, lane: lane, y: y, cooldown: 1.5 + random())
    nextID += 1
    entities.append(entity)
    return entity
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
      pickupTimer = 4 + random() * 3
    }
    if pirateTimer <= 0 {
      spawn(.pirate)
      pirateTimer = max(3, 7 - Double(level) * 0.35) + random() * 2
    }
    for e in entities {
      e.y += speed * dt * (e.kind == .pirate ? 0.44 : 1)
      e.hit = max(0, e.hit - dt)
      e.flash = max(0, e.flash - dt)
      if e.kind == .pirate && e.y > 45 && e.y < Self.playerY - 90 && !e.dead {
        e.cooldown -= dt
        if e.cooldown <= 0 {
          let shotDX = x - e.x
          let dy = Self.playerY - e.y - 43
          let d = hypot(shotDX, dy)
          let velocity = Double(265 + level * 8)
          bullets.append(
            Bullet(
              x: e.x, y: e.y + 43, vx: shotDX / d * velocity, vy: dy / d * velocity, enemy: true))
          e.flash = 0.12
          e.cooldown = max(1.1, 2.6 - Double(level) * 0.1)
          onEvent("enemyFire")
        }
      }
      if !e.dead && abs(e.x - x) < 43 && abs(e.y - Self.playerY) < (e.kind == .pirate ? 64 : 42) {
        switch e.kind {
        case .heart:
          health = min(100, health + 25)
          heartsCollected += 1
          bonus += 50
          e.dead = true
          onEvent("heart")
        default: if !jumping && damage(e.kind == .rock ? 25 : 30) { e.dead = true }
        }
        if status == .over { return }
      }
      if e.y > 900 { e.dead = true }
    }
    for b in bullets {
      b.x += b.vx * dt
      b.y += b.vy * dt
      if b.enemy {
        if !jumping && abs(b.x - x) < 28 && abs(b.y - Self.playerY) < 42 {
          damage(15)
          b.dead = true
          if status == .over { return }
        }
      } else {
        for e in entities {
          if e.kind != .pirate || e.dead || abs(e.x - b.x) > 34 || abs(e.y - b.y) > 52 { continue }
          e.hp -= 1
          e.hit = 0.13
          b.dead = true
          if e.hp <= 0 {
            e.dead = true
            piratesSunk += 1
            bonus += 250
            onEvent("sink")
          } else {
            onEvent("hit")
          }
          break
        }
      }
      if b.x < -30 || b.x > 830 || b.y < -30 || b.y > 850 { b.dead = true }
    }
    entities.removeAll { $0.dead }
    bullets.removeAll { $0.dead }
  }
}
