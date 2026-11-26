import SwiftUI
import UIKit

struct GameSurface: UIViewRepresentable {
  let game: Game
  let frame: Int
  var contentInsets: UIEdgeInsets = .zero
  @Environment(\.accessibilityReduceMotion) private var reducedMotion
  func makeUIView(context: Context) -> WaterView { WaterView(game: game) }
  func updateUIView(_ view: WaterView, context: Context) {
    view.accessibilityValue =
      "Lane \(game.lane + 1) of 4. Jump \(game.jumpReady ? "ready" : "recharging")."
      + (game.kraken.map { " Kraken health \(max(0, $0.hp)) of \(Game.krakenHP)." } ?? "")
    view.contentInsets = contentInsets
    view.reducedMotion = reducedMotion
    view.setNeedsDisplay()
  }
}

final class WaterView: UIView {
  let game: Game
  var reducedMotion = false
  var contentInsets = UIEdgeInsets.zero
  private var worldScale = 1.0
  private var offsetX = 0.0
  private var offsetY = 0.0
  private var down = CGPoint.zero
  private var anchor = 0.0
  private var swiped = false
  private var verticalSwipe = false
  init(game: Game) {
    self.game = game
    super.init(frame: .zero)
    isOpaque = true
    backgroundColor = UIColor(hex: "12687A")
    isAccessibilityElement = true
    accessibilityLabel = "Four-lane waterway"
    accessibilityHint =
      "Swipe left or right to steer, swipe up to jump, or tap a pirate to aim. Custom actions also let you steer and jump."
    accessibilityCustomActions = [
      UIAccessibilityCustomAction(name: "Steer left", target: self, selector: #selector(steerLeft)),
      UIAccessibilityCustomAction(
        name: "Steer right", target: self, selector: #selector(steerRight)),
      UIAccessibilityCustomAction(
        name: "Jump", target: self, selector: #selector(jumpFromAccessibility)),
    ]
  }
  @objc private func steerLeft() -> Bool {
    guard game.status == .playing else { return false }
    game.move(-1)
    return true
  }
  @objc private func steerRight() -> Bool {
    guard game.status == .playing else { return false }
    game.move(1)
    return true
  }
  @objc private func jumpFromAccessibility() -> Bool { game.jump() }
  required init?(coder: NSCoder) { fatalError("Use init(game:)") }
  // Palette for the toy-boat theme (matches Theme in WakeRunnerApp.swift).
  private enum Ink {
    static let outline = "0B2530"
    static let seaTop = "2BB0BC"
    static let seaBottom = "12687A"
  }

  override func draw(_ rect: CGRect) {
    guard let c = UIGraphicsGetCurrentContext() else { return }
    UIColor(hex: Ink.seaBottom).setFill()
    c.fill(bounds)
    let colors = [UIColor(hex: Ink.seaTop).cgColor, UIColor(hex: Ink.seaBottom).cgColor] as CFArray
    if let gradient = CGGradient(
      colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1])
    {
      c.drawLinearGradient(
        gradient, start: .zero, end: CGPoint(x: bounds.width * 0.4, y: bounds.height), options: [])
    }
    let viewport = bounds.inset(by: contentInsets)
    guard viewport.width > 0, viewport.height > 0 else { return }
    worldScale = min(viewport.width / 620, viewport.height / 800)
    offsetX = viewport.midX - 400 * worldScale
    offsetY = viewport.midY - 400 * worldScale
    // Insets place gameplay safely; scenery continues beneath the HUD and Fire.
    // Only the physical screen edges clip drawing.
    let visibleMinX = -offsetX / worldScale
    let visibleMinY = -offsetY / worldScale
    let visibleWidth = bounds.width / worldScale
    let visibleHeight = bounds.height / worldScale
    let visibleMaxY = visibleMinY + visibleHeight
    // Spawn above and despawn below the physical screen edges, not the gameplay insets.
    game.visibleTop = visibleMinY
    game.visibleBottom = visibleMaxY
    c.saveGState()
    c.translateBy(x: offsetX, y: offsetY)
    c.scaleBy(x: worldScale, y: worldScale)
    let scroll = reducedMotion ? 0 : game.scroll
    let time = reducedMotion ? 0 : game.elapsed

    // Lane bands: alternate lanes get a faint sheen so the four lanes read instantly.
    for i in [0, 2] {
      c.setFillColor(UIColor.white.withAlphaComponent(0.045).cgColor)
      c.fill(CGRect(x: 148 + Double(i) * 126, y: visibleMinY, width: 126, height: visibleHeight))
    }

    // Wave crests: small curved strokes drifting with the current.
    let rippleCount = max(60, min(200, Int(visibleWidth * visibleHeight / 9800)))
    c.setLineCap(.round)
    for i in 0..<rippleCount {
      let x = visibleMinX + Double(i * 137).truncatingRemainder(dividingBy: visibleWidth)
      let y =
        visibleMinY - 20
        + (Double(i * 83) + scroll * 0.65)
        .truncatingRemainder(dividingBy: visibleHeight + 40)
      let w = 8 + Double(i % 3) * 3
      c.setStrokeColor(UIColor.white.withAlphaComponent(i % 4 == 0 ? 0.32 : 0.16).cgColor)
      c.setLineWidth(2.5)
      c.beginPath()
      c.move(to: CGPoint(x: x - w, y: y))
      c.addQuadCurve(to: CGPoint(x: x + w, y: y), control: CGPoint(x: x, y: y - 6))
      c.strokePath()
    }

    let shorePhase = (scroll * 0.55).truncatingRemainder(dividingBy: 480)
    let firstIsland = Int(floor((visibleMinY - shorePhase - 400) / 480))
    let lastIsland = Int(ceil((visibleMaxY - shorePhase + 160) / 480))
    for i in firstIsland...lastIsland {
      let y = Double(i * 480) + shorePhase
      island(c, 0, y, time)
      island(c, 800, y + 240, time)
    }

    // Journey's end: the treasure island (intro, sail-in and victory) and the Kraken guarding it.
    let scene = sceneLayout(visibleMinY, visibleMaxY)
    if let landBottom = scene.landBottom {
      homeLand(c, visibleMinX - 60, visibleMinX + visibleWidth + 60, visibleMinY - 60, landBottom, time)
    }
    if let islandY = scene.islandY { treasureIsland(c, 400, islandY, time) }
    if let k = game.kraken { krakenBody(c, k, time, visibleMaxY) }

    if game.status == .ready {
      rock(c, 211, 230, time)
      boat(c, 589, 170, pirate: true)
      heart(c, 211, 530, time)
    }
    for e in game.entities where !e.dead {
      switch e.kind {
      case .rock: rock(c, e.x, e.y, time)
      case .pirate:
        wake(c, e.x, e.y - 50, flip: true, time)
        pirateWarning(c, e)
        boat(c, e.x, e.y, pirate: true, hit: e.hit > 0, type: e.pirateType)
        pirateHealth(c, e)
        if e.hp == 1 {
          line(c, e.x - 14, e.y + 14, e.x + 2, e.y, Ink.outline, 4)
          line(c, e.x + 2, e.y, e.x + 14, e.y - 16, Ink.outline, 4)
        }
        if e.flash > 0 {
          oval(c, e.x, e.y + 62, 20, 22, "FFB35C", alpha: 0.7)
          oval(c, e.x, e.y + 62, 11, 13, "FFF1B8")
        }
      case .heart: heart(c, e.x, e.y, time)
      }
    }
    for b in game.bullets where !b.dead {
      let speed = max(1, hypot(b.vx, b.vy))
      let tx = b.x - b.vx / speed * 34
      let ty = b.y - b.vy / speed * 34
      if b.enemy {
        line(c, tx, ty, b.x, b.y, "FF9A4D", 6, alpha: 0.55)
        oval(c, b.x, b.y, b.radius + 2, b.radius + 2, Ink.outline)
        oval(c, b.x, b.y, 7, 7, "3A4A52")
        oval(c, b.x - 2.5, b.y - 2.5, 2.5, 2.5, "8FA1A8")
      } else {
        line(c, tx, ty, b.x, b.y, "FFE08A", 7, alpha: 0.35)
        line(c, (tx + b.x) / 2, (ty + b.y) / 2, b.x, b.y, "FFF6E0", 3)
        oval(c, b.x, b.y, 6, 7, Ink.outline)
        oval(c, b.x, b.y, 4, 5, "FFE08A")
      }
    }
    if let k = game.kraken { krakenSlams(c, k, time, visibleMaxY) }
    let jump = game.jumping ? sin((1 - game.jumpTime / Game.jumpDuration) * .pi) : 0
    if game.status != .over {
      let boatX = scene.boatX ?? game.x
      let boatY = Game.playerY + scene.boatOffset
      if !game.jumping { wake(c, boatX, boatY + 46, flip: false, time) }
      // Shadow shrinks and fades as the boat leaves the water.
      oval(
        c, boatX + 6 + jump * 10, boatY + 8 + jump * 20, 32 * (1 - jump * 0.25),
        50 * (1 - jump * 0.25), Ink.outline, alpha: 0.28 - jump * 0.12)
      c.saveGState()
      c.translateBy(x: boatX, y: boatY - jump * 28)
      c.rotate(by: reducedMotion ? 0 : game.tilt)
      c.scaleBy(x: 1 + jump * 0.16, y: 1 + jump * 0.16)
      if game.invincible == 0 || Int(game.elapsed * 12) % 2 == 0 { boat(c, 0, 0, pirate: false) }
      c.restoreGState()
    }
    if game.status == .intro {
      let p = 1 - game.introTime / Game.introDuration
      if p > Game.introLandShare + 0.15 {
        caption("Set sail!", 400, 300, 44, alpha: min(1, (p - Game.introLandShare - 0.15) / 0.1))
      }
      caption("Tap to skip", 400, visibleMaxY - 70, 15, alpha: 0.7)
    }
    c.restoreGState()
  }

  // MARK: Journey's end

  /// Leviathan palette from the Kraken concept page (concept A).
  private enum Leviathan {
    static let skin = "2F5C66", dark = "16323A", light = "5F8F96", belly = "8FB4B0"
    static let sucker = "C9DED8", spot = "5FF3E6", eye = "FFB000", rage = "FF5A3C", pupil = "1A0D00"
  }

  /// Where the treasure island and the player's boat sit during the intro, the sail-in and victory.
  /// How far above the shoreline the ✗ sits on the home land.
  private static let markDepth = 330.0

  /// Where the home land (intro), the treasure island (sail-in, victory) and the player's boat sit.
  private func sceneLayout(_ top: Double, _ bottom: Double)
    -> (landBottom: Double?, islandY: Double?, boatOffset: Double, boatX: Double?)
  {
    switch game.status {
    case .intro:
      // Land fills the screen down to a strip of ocean; then it slides up and away, fast.
      let p = 1 - game.introTime / Game.introDuration
      let q = p < Game.introLandShare ? 0 : pow((p - Game.introLandShare) / (1 - Game.introLandShare), 1.6)
      let shore = bottom - (bottom - top) * 0.2
      // The boat waits below the screen and only glides in once the fast-forward starts.
      let hiddenOffset = bottom + 120 - Game.playerY
      return (shore - q * (bottom - top + 200), nil, hiddenOffset * (1 - q), nil)
    case .won:
      return (nil, 280, -190, 400)
    default:
      guard game.outro > 0 else { return (nil, nil, 0, nil) }
      let t = max(0, min(1, 1 - game.outro / Game.outroDuration)), q = t * t * (3 - 2 * t)
      return (nil, -260 + q * 540, -q * 190, game.x + (400 - game.x) * q)
    }
  }

  /// A palm tree: ink-outlined trunk and fronds.
  private func palm(_ c: CGContext, _ x: Double, _ y: Double) {
    line(c, x - 16, y + 62, x, y, Ink.outline, 12)
    line(c, x - 16, y + 62, x, y, "9A6E48", 7)
    for i in 0...5 {
      let a = Double(i) * .pi / 3 + 0.2
      let ex = x + cos(a) * 44, ey = y + sin(a) * 28
      line(c, x, y, ex, ey, Ink.outline, 15, alpha: 0.85)
      line(c, x, y, ex, ey, "2E8A55", 10)
    }
  }

  /// The home shore at the start of a voyage: green land with an ✗ marking the treasure map,
  /// a sandy beach, and the ocean below.
  private func homeLand(_ c: CGContext, _ left: Double, _ right: Double, _ top: Double, _ shore: Double, _ t: Double) {
    guard shore > top else { return }
    let wave = sin(t * 2) * 3
    for x in stride(from: left, through: right, by: 56) {
      oval(c, x, shore + 14 + wave, 46, 20, "FFFFFF", alpha: 0.45)
      oval(c, x, shore + 4, 44, 18, "7FD8CB")
    }
    c.setFillColor(UIColor(hex: "F6DCA0").cgColor)
    c.fill(CGRect(x: left, y: top, width: right - left, height: shore - top))
    for x in stride(from: left, through: right, by: 52) { oval(c, x, shore - 2, 40, 14, "F6DCA0") }
    let grass = shore - 46
    c.setFillColor(UIColor(hex: "5FB36A").cgColor)
    c.fill(CGRect(x: left, y: top, width: right - left, height: max(0, grass - top)))
    for x in stride(from: left, through: right, by: 48) { oval(c, x, grass, 36, 16, "5FB36A") }
    for (dx, dy, rx, ry) in [(120.0, 150.0, 90.0, 50.0), (650, 120, 110, 60), (260, 520, 120, 70), (560, 460, 90, 55), (90, 700, 100, 60), (700, 760, 120, 70)] {
      oval(c, dx, grass - dy, rx, ry, "4C9A5A")
    }
    for (px, py) in [(110.0, 120.0), (700, 150), (180, 430), (640, 560), (330, 720), (90, 860)] { palm(c, px, grass - py) }
    // ✗ marks the spot.
    let xY = shore - Self.markDepth
    oval(c, 400, xY + 6, 60, 26, "8A6A3A", alpha: 0.3)
    for (dx, dy) in [(1.0, 1.0), (1.0, -1.0)] {
      line(c, 400 - 36 * dx, xY - 36 * dy, 400 + 36 * dx, xY + 36 * dy, Ink.outline, 20)
      line(c, 400 - 36 * dx, xY - 36 * dy, 400 + 36 * dx, xY + 36 * dy, "D9483B", 12)
    }
  }

  /// A sandy island with a palm and an open, glowing treasure chest.
  private func treasureIsland(_ c: CGContext, _ x: Double, _ y: Double, _ t: Double) {
    let swell = sin(t * 1.6) * 3, pulse = (sin(t * 4) + 1) / 2
    oval(c, x, y, 160 + swell, 118 + swell, "FFFFFF", alpha: 0.35)
    oval(c, x, y, 150, 108, "7FD8CB")
    oval(c, x, y, 134, 94, "F6DCA0")
    ring(c, x, y, 134, 94, Ink.outline, 3, alpha: 0.5)
    oval(c, x - 70, y + 10, 46, 34, "5FB36A")
    // Palm
    line(c, x - 70, y + 12, x - 54, y - 50, Ink.outline, 12)
    line(c, x - 70, y + 12, x - 54, y - 50, "9A6E48", 7)
    for i in 0...5 {
      let a = Double(i) * .pi / 3 + 0.2
      let ex = x - 54 + cos(a) * 44, ey = y - 50 + sin(a) * 28
      line(c, x - 54, y - 50, ex, ey, Ink.outline, 15, alpha: 0.85)
      line(c, x - 54, y - 50, ex, ey, "2E8A55", 10)
    }
    // Chest with a golden glow and sparkles
    let cx = x + 34, cy = y + 4
    oval(c, cx, cy, 64 + pulse * 8, 50 + pulse * 6, "FFD34D", alpha: 0.22 + pulse * 0.12)
    polygon(c, [cx - 34, cy - 30, cx + 34, cy - 30, cx + 30, cy - 52, cx - 30, cy - 52], "A86E42", outline: 3)
    for (dx, dy) in [(-16.0, -12.0), (0, -16), (14, -11), (-6, -6), (8, -4)] { oval(c, cx + dx, cy + dy, 8, 6, "FFD34D") }
    oval(c, cx + 2, cy - 18, 5, 5, "E0475B")
    polygon(c, [cx - 34, cy - 8, cx + 34, cy - 8, cx + 34, cy + 26, cx - 34, cy + 26], "8A5A34", outline: 3)
    line(c, cx - 34, cy + 2, cx + 34, cy + 2, "F3C75F", 4)
    line(c, cx - 34, cy + 18, cx + 34, cy + 18, "F3C75F", 4)
    polygon(c, [cx - 6, cy - 4, cx + 6, cy - 4, cx + 6, cy + 10, cx - 6, cy + 10], "F3C75F", outline: 2)
    for i in 0..<4 {
      let a = t * 0.9 + Double(i) * .pi / 2, r = 58.0, sx = cx + cos(a) * r, sy = cy - 10 + sin(a) * r * 0.6
      let s = 4 + pulse * 4
      line(c, sx - s, sy, sx + s, sy, "FFF6E0", 2.5, alpha: 0.6 + pulse * 0.4)
      line(c, sx, sy - s, sx, sy + s, "FFF6E0", 2.5, alpha: 0.6 + pulse * 0.4)
    }
  }

  /// A tapering arm along `pts`, with a pale underside and suckers.
  private func tentacle(_ c: CGContext, _ pts: [(Double, Double)], _ w0: Double, alpha: Double) {
    let n = pts.count - 1
    guard n > 0 else { return }
    for i in 0..<n {
      let s = Double(i) / Double(n)
      let w = max(1.5, w0 * pow(1 - s, 0.85))
      let (x1, y1) = pts[i], (x2, y2) = pts[i + 1]
      line(c, x1, y1, x2, y2, Leviathan.dark, w + 3, alpha: alpha)
      line(c, x1, y1, x2, y2, Leviathan.skin, w, alpha: alpha)
      line(c, x1, y1, x2, y2, Leviathan.belly, w * 0.35, alpha: alpha * 0.55)
      if i % 3 == 1 && w > 6 { oval(c, x1, y1, w * 0.18, w * 0.15, Leviathan.sucker, alpha: alpha) }
    }
  }
  /// Points along an arm that curls harder toward its tip.
  private func spine(_ x0: Double, _ y0: Double, _ length: Double, _ heading: Double, _ dir: Double, _ curl: Double, _ t: Double) -> [(Double, Double)] {
    var pts: [(Double, Double)] = [], x = x0, y = y0, h = heading
    let n = 20
    for i in 0...n {
      let s = Double(i) / Double(n)
      pts.append((x, y))
      h += dir * (0.02 + curl * pow(s, 2.4)) + 0.03 * sin(t * 1.5 + s * 5)
      x += cos(h) * length / Double(n)
      y += sin(h) * length / Double(n)
    }
    return pts
  }

  /// The Leviathan seen from above: warning lanes, submerged arms, mantle off the top of the screen, head and eyes.
  private func krakenBody(_ c: CGContext, _ k: Kraken, _ t: Double, _ bottom: Double) {
    let hx = 400.0, hy = k.y, pulse = (sin(t * 9) + 1) / 2
    c.saveGState()
    if k.dying > 0 { c.setAlpha(CGFloat(k.dying / 1.6)) }
    for l in k.warningLanes {
      let lx = Game.lanes[l]
      c.setFillColor(UIColor(hex: "14001E").withAlphaComponent(0.14 + 0.12 * pulse).cgColor)
      c.fill(CGRect(x: lx - 63, y: hy + 60, width: 126, height: max(1, bottom - hy - 60)))
      for i in 0..<6 {
        let ph = (t * 0.8 + Double(i) * 0.17).truncatingRemainder(dividingBy: 1)
        ring(c, lx + sin(Double(i) * 2.3) * 30, bottom - 60 - ph * 360, 5 + Double(i % 3) * 2, 5 + Double(i % 3) * 2, "FFFFFF", 2, alpha: 0.75 * (1 - ph))
      }
    }
    // Submerged arms fan out from the crown; feeding tentacles trail down the outer lanes.
    for (i, a) in [-1.05, -0.72, -0.38, 0.38, 0.72, 1.05].enumerated() {
      tentacle(c, spine(hx + a * 24, hy + 40, 150 + Double(i % 2) * 40, .pi / 2 + a * 0.9, a < 0 ? -1 : 1, 0.5, t), 22, alpha: 0.38)
    }
    for side in [-1.0, 1.0] {
      tentacle(c, spine(hx + side * 40, hy + 40, 320, .pi / 2 - side * 0.55, side, 0.06, t), 14, alpha: 0.38)
    }
    // Fins near the mantle tip, then the mantle rising off the top of the screen.
    let flap = sin(t * 1.8) * 6
    for side in [-1.0, 1.0] {
      polygon(c, [hx + side * 8, hy - 236, hx + side * (84 + flap), hy - 272, hx + side * 22, hy - 312], Leviathan.light, outline: 2.5)
    }
    c.beginPath()
    c.move(to: CGPoint(x: hx - 66, y: hy - 20))
    c.addQuadCurve(to: CGPoint(x: hx, y: hy - 340), control: CGPoint(x: hx - 86, y: hy - 210))
    c.addQuadCurve(to: CGPoint(x: hx + 66, y: hy - 20), control: CGPoint(x: hx + 86, y: hy - 210))
    c.closePath()
    c.setFillColor(UIColor(hex: Leviathan.skin).cgColor)
    c.setStrokeColor(UIColor(hex: Ink.outline).withAlphaComponent(0.6).cgColor)
    c.setLineWidth(3)
    c.drawPath(using: .fillStroke)
    line(c, hx, hy - 30, hx, hy - 300, Leviathan.dark, 8, alpha: 0.5)
    for side in [-1.0, 1.0] { line(c, hx + side * 26, hy - 30, hx + side * 10, hy - 280, "000000", 3, alpha: 0.14) }
    for (bx, by) in [(-34.0, -70.0), (30, -96), (-18, -140), (22, -168), (-28, -200), (14, -236)] {
      oval(c, hx + bx, hy + by, 5, 5, "C9C6B8")
      oval(c, hx + bx, hy + by, 2, 2, "3D3A33")
    }
    // Photophores pulse, and blaze before a slam.
    let glow = k.warning > 0 ? 1 : 0.35 + 0.65 * (0.5 + 0.5 * sin(t * (k.enraged ? 5 : 2.4)))
    for side in [-1.0, 1.0] {
      for i in 1..<9 {
        let f = Double(i) / 9
        oval(c, hx + side * 44 * (1 - f * 0.8), hy - 24 - f * 290, 6 * (1 - f * 0.5), 6 * (1 - f * 0.5), Leviathan.spot, alpha: glow * 0.35)
        oval(c, hx + side * 44 * (1 - f * 0.8), hy - 24 - f * 290, 3 * (1 - f * 0.5), 3 * (1 - f * 0.5), Leviathan.spot, alpha: glow)
      }
    }
    // Head with bulging side eyes.
    // A hit briefly lightens the head (no white overlay).
    oval(c, hx, hy + 6, 72, 34, k.hit > 0 ? Leviathan.light : Leviathan.skin)
    ring(c, hx, hy + 6, 72, 34, Leviathan.dark, 3, alpha: 0.8)
    for side in [-1.0, 1.0] {
      let ex = hx + side * 62, ey = hy + 2
      oval(c, ex, ey, 20, 17, Leviathan.dark)
      oval(c, ex, ey, 13, 10, k.enraged ? Leviathan.rage : Leviathan.eye)
      oval(c, ex, ey, 2.2, 7, Leviathan.pupil)
      oval(c, ex - 4, ey - 3, 2, 2, "FFFFFF", alpha: 0.8)
    }
    c.restoreGState()
  }

  /// Tentacles slamming down their lanes toward the boat.
  private func krakenSlams(_ c: CGContext, _ k: Kraken, _ t: Double, _ bottom: Double) {
    guard k.slam > 0 else { return }
    let progress = 1 - k.slam / Game.slamDuration
    let reach = 0.25 + 0.75 * pow(min(1, progress / 0.3), 3)
    for l in k.slamLanes {
      // Rooted in the arm crown under the head, curving out to the lane, then straight down it.
      let lx = Game.lanes[l]
      let rootX = 400 + max(-40, min(40, lx - 400)), rootY = k.y + 34
      let bendY = k.y + 150
      let end = bendY + (bottom - bendY) * reach
      var pts: [(Double, Double)] = []
      for i in 0...8 {
        let s = Double(i) / 8, u = 1 - s
        pts.append((u * u * rootX + 2 * u * s * lx + s * s * lx, u * u * rootY + 2 * u * s * (rootY + 30) + s * s * bendY))
      }
      for i in 1...16 {
        let s = Double(i) / 16
        pts.append((lx + sin(t * 6 - s * 6) * 8 * s, bendY + s * (end - bendY)))
      }
      tentacle(c, pts, 40, alpha: 1)
      oval(c, lx, end, 44, 16, "FFFFFF", alpha: 0.7)
    }
  }

  /// Large caption text centred on `x` with a soft shadow, used by the intro scene.
  private func caption(_ text: String, _ x: Double, _ y: Double, _ size: Double, alpha: Double = 1) {
    let shadow = NSShadow()
    shadow.shadowColor = UIColor(hex: Ink.outline).withAlphaComponent(0.8 * alpha)
    shadow.shadowOffset = CGSize(width: 0, height: 3)
    shadow.shadowBlurRadius = 4
    let attributes: [NSAttributedString.Key: Any] = [
      .font: UIFont.systemFont(ofSize: size, weight: .black),
      .foregroundColor: UIColor(hex: "FFF6E0").withAlphaComponent(alpha), .shadow: shadow,
    ]
    let width = (text as NSString).size(withAttributes: attributes).width
    (text as NSString).draw(at: CGPoint(x: x - width / 2, y: y), withAttributes: attributes)
  }

  // MARK: Primitives

  private func oval(
    _ c: CGContext, _ x: Double, _ y: Double, _ rx: Double, _ ry: Double, _ color: String,
    alpha: Double = 1
  ) {
    c.setFillColor(UIColor(hex: color).withAlphaComponent(alpha).cgColor)
    c.fillEllipse(in: CGRect(x: x - rx, y: y - ry, width: rx * 2, height: ry * 2))
  }
  private func ring(
    _ c: CGContext, _ x: Double, _ y: Double, _ rx: Double, _ ry: Double, _ color: String,
    _ width: Double, alpha: Double = 1
  ) {
    c.setStrokeColor(UIColor(hex: color).withAlphaComponent(alpha).cgColor)
    c.setLineWidth(width)
    c.strokeEllipse(in: CGRect(x: x - rx, y: y - ry, width: rx * 2, height: ry * 2))
  }
  private func line(
    _ c: CGContext, _ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double, _ color: String,
    _ width: Double, alpha: Double = 1
  ) {
    c.setStrokeColor(UIColor(hex: color).withAlphaComponent(alpha).cgColor)
    c.setLineWidth(width)
    c.setLineCap(.round)
    c.beginPath()
    c.move(to: CGPoint(x: x1, y: y1))
    c.addLine(to: CGPoint(x: x2, y: y2))
    c.strokePath()
  }
  /// Filled polygon with an optional ink outline for the chunky toy look.
  private func polygon(_ c: CGContext, _ p: [Double], _ color: String, outline: Double = 0) {
    c.beginPath()
    c.move(to: CGPoint(x: p[0], y: p[1]))
    for i in stride(from: 2, to: p.count, by: 2) { c.addLine(to: CGPoint(x: p[i], y: p[i + 1])) }
    c.closePath()
    c.setFillColor(UIColor(hex: color).cgColor)
    if outline > 0 {
      c.setStrokeColor(UIColor(hex: Ink.outline).cgColor)
      c.setLineWidth(outline)
      c.setLineJoin(.round)
      c.drawPath(using: .fillStroke)
    } else {
      c.fillPath()
    }
  }

  // MARK: Scenery and sprites

  private func island(_ c: CGContext, _ x: Double, _ y: Double, _ t: Double) {
    let swell = sin(t * 1.6 + y * 0.01) * 3
    oval(c, x, y, 124 + swell, 172 + swell, "FFFFFF", alpha: 0.35)
    oval(c, x, y, 114, 162, "7FD8CB")
    oval(c, x, y, 100, 150, "F6DCA0")
    ring(c, x, y, 100, 150, Ink.outline, 3, alpha: 0.5)
    oval(c, x, y, 82, 130, "5FB36A")
    oval(c, x - 10, y + 12, 58, 96, "4C9A5A")
    oval(c, x + 34, y + 70, 12, 9, "8C9A92")
    line(c, x, y + 22, x + 22, y - 35, Ink.outline, 12)
    line(c, x, y + 22, x + 22, y - 35, "9A6E48", 7)
    for i in 0...5 {
      let a = Double(i) * .pi / 3 + 0.2
      let ex = x + 22 + cos(a) * 46
      let ey = y - 35 + sin(a) * 30
      line(c, x + 22, y - 35, ex, ey, Ink.outline, 16, alpha: 0.85)
      line(c, x + 22, y - 35, ex, ey, "2E8A55", 11)
      line(c, x + 22, y - 35, (x + 22 + ex) / 2, (y - 35 + ey) / 2 - 2, "52B36F", 4)
    }
    oval(c, x + 22, y - 35, 7, 7, "8A5A34")
  }

  private func rock(_ c: CGContext, _ x: Double, _ y: Double, _ t: Double) {
    let foam = sin(t * 4 + x) * 2
    oval(c, x, y + 20, 48 + foam, 26 + foam / 2, "FFFFFF", alpha: 0.45)
    oval(c, x, y + 19, 42, 22, "7FD8CB")
    polygon(
      c,
      [
        x - 35, y + 17, x - 28, y - 19, x - 8, y - 33, x + 22, y - 23, x + 37, y + 10, x + 20,
        y + 29, x - 15, y + 29,
      ], "5C6C72", outline: 3.5)
    polygon(c, [x - 28, y - 19, x - 8, y - 33, x + 22, y - 23, x + 8, y - 5, x - 16, y], "8E9FA4")
    line(c, x - 12, y - 25, x + 6, y - 12, "C7D2D3", 3.5)
    oval(c, x + 16, y + 14, 5, 3, "4A585D")
  }

  /// V-shaped foam trail behind a hull. `flip` points it upward for oncoming pirates.
  private func wake(_ c: CGContext, _ x: Double, _ y: Double, flip: Bool, _ t: Double) {
    let d = flip ? -1.0 : 1.0
    let wobble = sin(t * 10) * 2
    for (spread, length, alpha) in [(26.0, 70.0, 0.45), (14.0, 44.0, 0.6)] {
      line(c, x - 14, y, x - 14 - spread + wobble, y + d * length, "FFFFFF", 5, alpha: alpha)
      line(c, x + 14, y, x + 14 + spread - wobble, y + d * length, "FFFFFF", 5, alpha: alpha)
    }
    oval(c, x, y + d * 8, 14, 9, "FFFFFF", alpha: 0.5)
  }

  private func pirateText(_ text: String, _ x: Double, _ y: Double, _ size: Double = 12) {
    let attributes: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: size), .foregroundColor: UIColor(hex: "FFF1D0")]
    let width = (text as NSString).size(withAttributes: attributes).width
    (text as NSString).draw(at: CGPoint(x: x - width / 2, y: y), withAttributes: attributes)
  }
  private func pirateWarning(_ c: CGContext, _ e: Entity) {
    guard e.warning > 0 else { return }
    if e.pirateType == .sniper {
      c.setLineDash(phase: 0, lengths: [8, 8])
      line(c, e.x, e.y + 43, e.aimX, Game.playerY, "FFB6AC", 3)
      c.setLineDash(phase: 0, lengths: [])
      c.setStrokeColor(UIColor(hex: "FFF1CC").cgColor)
      c.setLineWidth(2)
      c.strokeEllipse(in: CGRect(x: e.aimX - 26, y: Game.playerY - 26, width: 52, height: 52))
    }
  }
  private func pirateHealth(_ c: CGContext, _ e: Entity) {
    let captain = e.pirateType == .captain
    let y = e.y - (captain ? 91 : 83)
    c.setFillColor(UIColor(hex: "173E46").cgColor)
    c.fill(CGRect(x: e.x - 59, y: y - 3, width: 118, height: 34))
    pirateText(e.pirateType.stats.name.uppercased(), e.x, y, 12)
    if captain {
      line(c, e.x - 48, y + 23, e.x + 48, y + 23, "526265", 7)
      if e.hp > 0 { line(c, e.x - 48, y + 23, e.x - 48 + 96 * Double(e.hp) / Double(e.maxHp), y + 23, e.pirateType.stats.color, 7) }
      pirateText("\(e.hp)/\(e.maxHp)" + (e.hp <= 5 ? " · ENRAGED" : ""), e.x, y - 19)
    } else {
      for i in 0..<e.maxHp {
        let x = e.x - Double(e.maxHp * 10 - 3) / 2 + Double(i * 10)
        c.setFillColor(UIColor(hex: i < e.hp ? e.pirateType.stats.color : "526265").cgColor)
        c.fill(CGRect(x: x, y: y + 20, width: 7, height: 6))
      }
    }
    if e.warning > 0 {
      let label = e.pirateType == .sniper ? "AIM LOCKED" : e.attackStep % 2 == 0 ? "BURST INCOMING" : "SPREAD INCOMING"
      c.setFillColor(UIColor(hex: "442F35").cgColor)
      c.fill(CGRect(x: e.x - 63, y: e.y + 78, width: 126, height: 22))
      pirateText(label, e.x, e.y + 81, 12)
    }
  }
  private func boat(_ c: CGContext, _ x: Double, _ y: Double, pirate: Bool, hit: Bool = false, type: PirateType = .gunner) {
    c.saveGState()
    c.translateBy(x: x, y: y)
    if pirate {
      c.rotate(by: .pi)
      if type == .captain { c.scaleBy(x: 1.12, y: 1.12) }
      oval(c, -6, -6, 32, 50, Ink.outline, alpha: 0.28)
    }
    let hull = [0.0, -58, -30, -24, -32, 27, -21, 49, 21, 49, 32, 27, 30, -24]
    // Bow cannon / bowsprit sits under the hull outline.
    line(c, 0, -46, 0, -64, Ink.outline, 11)
    line(c, 0, -50, 0, -62, pirate ? "3A4A52" : "FFE08A", 5)
    polygon(c, hull, hit ? "FFF1B8" : pirate ? "4A3328" : "FFF6E0", outline: 4)
    polygon(c, [0, -46, -22, -16, -21, 34, 21, 34, 22, -16], pirate ? "8A5C3A" : "F3C98B")
    for plank in stride(from: -6.0, through: 30, by: 12) {
      line(c, -18, plank, 18, plank, pirate ? "6E4830" : "D9A86A", 1.5)
    }
    if pirate {
      // Black sail with a skull, red trim.
      polygon(c, [-26, -22, 26, -22, 22, 14, 0, 7, -22, 14], type.stats.color, outline: 3)
      line(c, -24, -20, 24, -20, "D9483B", 4)
      if type == .scatter || type == .burst {
        for x in [-19.0, 19.0] { line(c, x, -31, x, -59, "29343B", 8) }
      }
      if type == .sniper { line(c, 0, -40, 0, -77, "29343B", 8) }
      if type == .captain { polygon(c, [-12, 30, -12, 42, -5, 36, 0, 46, 5, 36, 12, 42, 12, 30], "F3C75F", outline: 1) }
      // The pirate frame is rotated 180°, so "up" on screen is +y here.
      line(c, -9, -16, 9, -10, "FFF6E0", 2.5)
      line(c, 9, -16, -9, -10, "FFF6E0", 2.5)
      oval(c, 0, -1, 8, 7.5, "FFF6E0")
      oval(c, -3, 1, 2.2, 2.2, "22292E")
      oval(c, 3, 1, 2.2, 2.2, "22292E")
    } else {
      // Coral cabin with a sky-tinted windshield.
      polygon(c, [-23, -24, 23, -24, 19, 13, 0, 7, -19, 13], "FF7A59", outline: 3)
      polygon(c, [-16, -20, 16, -20, 12, -6, -12, -6], "A8ECF2")
      line(c, -11, -17, -4, -9, "FFFFFF", 2.5, alpha: 0.8)
      line(c, -20, 24, 20, 24, "FF7A59", 4)
    }
    line(c, 0, -30, 0, 28, Ink.outline, 4)
    c.restoreGState()
  }

  private func heart(_ c: CGContext, _ x: Double, _ y: Double, _ t: Double) {
    let pulse = (sin(t * 5) + 1) / 2
    let bob = sin(t * 3) * 3
    let y = y + bob
    oval(c, x, y, 34 + pulse * 6, 34 + pulse * 6, "FFFFFF", alpha: 0.18 + pulse * 0.12)
    oval(c, x + 3, y + 5, 27, 27, Ink.outline, alpha: 0.3)
    oval(c, x, y, 28, 28, Ink.outline)
    oval(c, x, y, 24.5, 24.5, "FF5D73")
    oval(c, x - 8, y - 9, 9, 6, "FFFFFF", alpha: 0.25)
    oval(c, x - 7, y - 4, 8, 8, "FFF6E0")
    oval(c, x + 7, y - 4, 8, 8, "FFF6E0")
    polygon(c, [x - 14.6, y - 1, x + 14.6, y - 1, x, y + 15], "FFF6E0")
  }
  override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
    if game.status == .intro {
      game.skipIntro()
      return
    }
    guard let p = touches.first?.location(in: self) else { return }
    down = p
    anchor = p.x
    swiped = false
    verticalSwipe = false
  }
  override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
    guard let p = touches.first?.location(in: self) else { return }
    let dx = p.x - down.x
    let dy = p.y - down.y
    if !swiped {
      guard max(abs(dx), abs(dy)) > 28 else { return }
      swiped = true
      verticalSwipe = abs(dy) > abs(dx)
      if verticalSwipe && dy < 0 && game.jump() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
      }
    }
    if !verticalSwipe && abs(p.x - anchor) > 28 {
      let lane = game.lane
      game.move(p.x > anchor ? 1 : -1)
      if game.lane != lane { UISelectionFeedbackGenerator().selectionChanged() }
      anchor = p.x
    }
  }

  override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
    guard let p = touches.first?.location(in: self), !swiped, hypot(p.x - down.x, p.y - down.y) < 20
    else { return }
    let x = (p.x - offsetX) / worldScale
    let y = (p.y - offsetY) / worldScale
    if let k = game.kraken, k.surfaced, y < k.y + 150 {
      game.fire()  // Tapping the Kraken fires straight at it.
      return
    }
    if let target = game.entities.filter({
      $0.kind == .pirate && !$0.dead && abs($0.x - x) < 65 && abs($0.y - y) < 75
    })
    .min(by: { hypot($0.x - x, $0.y - y) < hypot($1.x - x, $1.y - y) }) {
      game.fire(target: target)
    }
  }
}

extension UIColor {
  convenience init(hex: String) {
    let n = UInt32(hex, radix: 16) ?? 0
    self.init(
      red: CGFloat((n >> 16) & 255) / 255, green: CGFloat((n >> 8) & 255) / 255,
      blue: CGFloat(n & 255) / 255, alpha: 1)
  }
}
