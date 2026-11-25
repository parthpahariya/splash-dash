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

    // Lane dividers scroll with the water to sell speed.
    c.setLineDash(phase: CGFloat(-scroll.truncatingRemainder(dividingBy: 36)), lengths: [14, 22])
    for i in 1...3 {
      let x = Double(148 + i * 126)
      line(c, x, visibleMinY, x, visibleMaxY, "FFFFFF", 3, alpha: 0.18)
    }
    c.setLineDash(phase: 0, lengths: [])

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
        boat(c, e.x, e.y, pirate: true, hit: e.hit > 0)
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
        oval(c, b.x, b.y, 10, 10, Ink.outline)
        oval(c, b.x, b.y, 7, 7, "3A4A52")
        oval(c, b.x - 2.5, b.y - 2.5, 2.5, 2.5, "8FA1A8")
      } else {
        line(c, tx, ty, b.x, b.y, "FFE08A", 7, alpha: 0.35)
        line(c, (tx + b.x) / 2, (ty + b.y) / 2, b.x, b.y, "FFF6E0", 3)
        oval(c, b.x, b.y, 6, 7, Ink.outline)
        oval(c, b.x, b.y, 4, 5, "FFE08A")
      }
    }
    let jump = game.jumping ? sin((1 - game.jumpTime / Game.jumpDuration) * .pi) : 0
    if game.status != .over {
      if !game.jumping { wake(c, game.x, Game.playerY + 46, flip: false, time) }
      // Shadow shrinks and fades as the boat leaves the water.
      oval(
        c, game.x + 6 + jump * 10, Game.playerY + 8 + jump * 20, 32 * (1 - jump * 0.25),
        50 * (1 - jump * 0.25), Ink.outline, alpha: 0.28 - jump * 0.12)
      c.saveGState()
      c.translateBy(x: game.x, y: Game.playerY - jump * 28)
      c.rotate(by: reducedMotion ? 0 : game.tilt)
      c.scaleBy(x: 1 + jump * 0.16, y: 1 + jump * 0.16)
      if game.invincible == 0 || Int(game.elapsed * 12) % 2 == 0 { boat(c, 0, 0, pirate: false) }
      c.restoreGState()
    }
    c.restoreGState()
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

  private func boat(_ c: CGContext, _ x: Double, _ y: Double, pirate: Bool, hit: Bool = false) {
    c.saveGState()
    c.translateBy(x: x, y: y)
    if pirate {
      c.rotate(by: .pi)
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
      polygon(c, [-26, -22, 26, -22, 22, 14, 0, 7, -22, 14], "22292E", outline: 3)
      line(c, -24, -20, 24, -20, "D9483B", 4)
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
