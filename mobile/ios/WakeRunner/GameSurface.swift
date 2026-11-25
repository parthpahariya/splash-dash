import SwiftUI
import UIKit

struct GameSurface: UIViewRepresentable {
  let game: Game
  let frame: Int
  var contentInsets: UIEdgeInsets = .zero
  @Environment(\.accessibilityReduceMotion) private var reducedMotion
  func makeUIView(context: Context) -> WaterView { WaterView(game: game) }
  func updateUIView(_ view: WaterView, context: Context) {
    view.accessibilityValue = "Lane \(game.lane + 1) of 4. Jump charges: \(game.jumps)."
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
    backgroundColor = UIColor(hex: "277F89")
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
  override func draw(_ rect: CGRect) {
    guard let c = UIGraphicsGetCurrentContext() else { return }
    UIColor(hex: "277F89").setFill()
    c.fill(bounds)
    let colors = [UIColor(hex: "318E94").cgColor, UIColor(hex: "1F6F7C").cgColor] as CFArray
    if let gradient = CGGradient(
      colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1])
    {
      c.drawLinearGradient(
        gradient, start: .zero, end: CGPoint(x: bounds.width, y: bounds.height), options: [])
    }
    let viewport = bounds.inset(by: contentInsets)
    guard viewport.width > 0, viewport.height > 0 else { return }
    worldScale = min(viewport.width / 620, viewport.height / 800)
    offsetX = viewport.midX - 400 * worldScale
    offsetY = viewport.midY - 400 * worldScale
    c.saveGState()
    c.clip(to: viewport)
    c.saveGState()
    c.translateBy(x: offsetX, y: offsetY)
    c.scaleBy(x: worldScale, y: worldScale)
    let scroll = reducedMotion ? 0 : game.scroll
    for i in 0...75 {
      let x = Double(i * 137 % 800)
      let y = (Double(i * 83) + scroll * 0.65).truncatingRemainder(dividingBy: 840) - 20
      line(c, x - 9, y, x + 9, y + 1, "65B9B5", 2)
    }
    for i in -1...2 {
      let y = Double(i * 480) + (scroll * 0.55).truncatingRemainder(dividingBy: 480)
      island(c, 0, y)
      island(c, 800, y + 240)
    }
    c.setLineDash(phase: 0, lengths: [7, 14])
    for i in 0...4 { line(c, Double(148 + i * 126), 0, Double(148 + i * 126), 800, "529E9F", 1.5) }
    c.setLineDash(phase: 0, lengths: [])
    if game.status == .ready {
      rock(c, 211, 230)
      boat(c, 589, 170, pirate: true)
      pickup(c, 463, 440, heart: false)
      pickup(c, 211, 530, heart: true)
    }
    for e in game.entities where !e.dead {
      switch e.kind {
      case .rock: rock(c, e.x, e.y)
      case .pirate:
        boat(c, e.x, e.y, pirate: true, hit: e.hit > 0)
        if e.hp == 1 { line(c, e.x - 15, e.y + 15, e.x + 15, e.y - 15, "F8EED6", 3) }
        if e.flash > 0 { oval(c, e.x, e.y + 60, 16, 19, "FFE2A2") }
      case .heart: pickup(c, e.x, e.y, heart: true)
      case .jump: pickup(c, e.x, e.y, heart: false)
      }
    }
    for b in game.bullets where !b.dead {
      let speed = max(1, hypot(b.vx, b.vy))
      line(
        c, b.x - b.vx / speed * 30, b.y - b.vy / speed * 30, b.x, b.y,
        b.enemy ? "EEBC87" : "FFF0C6", 4)
      oval(c, b.x, b.y, b.enemy ? 8 : 4, b.enemy ? 8 : 6, b.enemy ? "29343B" : "FFF0C6")
    }
    let jump = game.jumping ? sin((1 - game.jumpTime / Game.jumpDuration) * .pi) : 0
    oval(c, game.x, Game.playerY + 15, 34, 26, "246A72")
    if game.status != .over {
      c.saveGState()
      c.translateBy(x: game.x, y: Game.playerY - jump * 28)
      c.rotate(by: reducedMotion ? 0 : game.tilt)
      c.scaleBy(x: 1 + jump * 0.16, y: 1 + jump * 0.16)
      if game.invincible == 0 || Int(game.elapsed * 12) % 2 == 0 { boat(c, 0, 0, pirate: false) }
      c.restoreGState()
    }
    c.restoreGState()
    c.restoreGState()
  }
  private func oval(
    _ c: CGContext, _ x: Double, _ y: Double, _ rx: Double, _ ry: Double, _ color: String
  ) {
    c.setFillColor(UIColor(hex: color).cgColor)
    c.fillEllipse(in: CGRect(x: x - rx, y: y - ry, width: rx * 2, height: ry * 2))
  }
  private func line(
    _ c: CGContext, _ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double, _ color: String,
    _ width: Double
  ) {
    c.setStrokeColor(UIColor(hex: color).cgColor)
    c.setLineWidth(width)
    c.setLineCap(.round)
    c.beginPath()
    c.move(to: CGPoint(x: x1, y: y1))
    c.addLine(to: CGPoint(x: x2, y: y2))
    c.strokePath()
  }
  private func polygon(_ c: CGContext, _ p: [Double], _ color: String) {
    c.beginPath()
    c.move(to: CGPoint(x: p[0], y: p[1]))
    for i in stride(from: 2, to: p.count, by: 2) { c.addLine(to: CGPoint(x: p[i], y: p[i + 1])) }
    c.closePath()
    c.setFillColor(UIColor(hex: color).cgColor)
    c.fillPath()
  }
  private func island(_ c: CGContext, _ x: Double, _ y: Double) {
    oval(c, x, y, 112, 160, "9BD4BA")
    oval(c, x, y, 99, 150, "E5D39B")
    oval(c, x, y, 82, 130, "789764")
    line(c, x, y + 20, x + 22, y - 35, "8A6848", 8)
    for i in 0...5 {
      let a = Double(i) * .pi / 3
      line(c, x + 22, y - 35, x + 22 + cos(a) * 44, y - 35 + sin(a) * 28, "426F52", 12)
    }
  }
  private func rock(_ c: CGContext, _ x: Double, _ y: Double) {
    oval(c, x, y + 19, 43, 23, "81C6BA")
    polygon(
      c,
      [
        x - 35, y + 17, x - 28, y - 19, x - 8, y - 33, x + 22, y - 23, x + 37, y + 10, x + 20,
        y + 29, x - 15, y + 29,
      ], "6B7A75")
    polygon(c, [x - 28, y - 19, x - 8, y - 33, x + 22, y - 23, x + 8, y - 5, x - 16, y], "9AA399")
    line(c, x - 10, y - 24, x + 6, y - 8, "B6BBB0", 3)
  }
  private func boat(_ c: CGContext, _ x: Double, _ y: Double, pirate: Bool, hit: Bool = false) {
    c.saveGState()
    c.translateBy(x: x, y: y)
    if pirate { c.rotate(by: .pi) }
    polygon(
      c, [0, -57, -29, -24, -31, 27, -20, 48, 20, 48, 31, 27, 29, -24],
      hit ? "FFE2A2" : pirate ? "604F40" : "EDB77D")
    polygon(c, [0, -45, -21, -16, -20, 33, 20, 33, 21, -16], pirate ? "96724E" : "F8E9C4")
    line(c, 0, -30, 0, 25, "584D40", 4)
    polygon(c, [-24, -24, 24, -24, 20, 12, 0, 6, -20, 12], pirate ? "B85C52" : "E77D60")
    if pirate {
      oval(c, 0, -8, 7, 7, "F8EED6")
      oval(c, -3, -9, 2, 2, "604F40")
      oval(c, 3, -9, 2, 2, "604F40")
    } else {
      polygon(c, [-16, -20, 17, -20, 12, -5, -12, -5], "FFF4D8")
    }
    line(c, 0, -45, 0, -61, "283E46", 9)
    c.restoreGState()
  }
  private func pickup(_ c: CGContext, _ x: Double, _ y: Double, heart: Bool) {
    oval(c, x, y, 27, 27, heart ? "885469" : "305E78")
    if heart {
      oval(c, x - 7, y - 5, 8, 8, "FFB7C4")
      oval(c, x + 7, y - 5, 8, 8, "FFB7C4")
      polygon(c, [x - 15, y - 3, x + 15, y - 3, x, y + 16], "FFB7C4")
    } else {
      polygon(
        c, [x, y - 16, x - 13, y, x - 5, y, x - 5, y + 15, x + 5, y + 15, x + 5, y, x + 13, y],
        "B5EDFF")
    }
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
