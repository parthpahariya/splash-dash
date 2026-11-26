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
      c.saveGState()
      if scene.landAlpha < 1 { c.setAlpha(CGFloat(scene.landAlpha)) }
      homeLand(c, visibleMinX - 60, visibleMinX + visibleWidth + 60, visibleMinY - 60, landBottom, game.elapsed)
      c.restoreGState()
    }
    if scene.pan > 0.02 && scene.pan < 0.98 && !reducedMotion {
      // Fast-forward streaks rushing past on the water.
      let strength = sin(scene.pan * .pi)
      for i in 0..<16 {
        let x = visibleMinX + Double(i * 211 % 997) / 997 * visibleWidth
        let y = visibleMinY + (Double(i * 173) + game.scroll * 1.6).truncatingRemainder(dividingBy: visibleHeight + 160) - 80
        line(c, x, y, x, y + 70 + Double(i % 3) * 30, "FFFFFF", 3, alpha: 0.28 * strength)
      }
    }
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
      // A moored boat (after winning) leaves no wake.
      if !game.jumping && game.status != .won { wake(c, boatX, boatY + 46, flip: false, time) }
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
  /// Overshoots slightly past 1 and settles: used for pops (the ✗, "Set sail!").
  static func easeOutBack(_ v: Double) -> Double {
    let t = max(0, min(1, v)), c1 = 1.70158, c3 = c1 + 1
    return 1 + c3 * pow(t - 1, 3) + c1 * pow(t - 1, 2)
  }

  private func sceneLayout(_ top: Double, _ bottom: Double)
    -> (landBottom: Double?, boatOffset: Double, boatX: Double?, pan: Double, landAlpha: Double)
  {
    switch game.status {
    case .intro:
      // Land fills the screen down to a strip of ocean; then the view rushes up and out to sea.
      let p = 1 - game.introTime / Game.introDuration
      let s = p < Game.introLandShare ? 0 : (p - Game.introLandShare) / (1 - Game.introLandShare)
      // Ease in, rush, ease out: the pan starts gently, speeds through the middle and lands softly.
      let pan = s < 0.5 ? 4 * s * s * s : 1 - pow(-2 * s + 2, 3) / 2
      let shore = bottom - (bottom - top) * 0.2
      // The boat waits below the screen and only glides in once the fast-forward starts.
      let hiddenOffset = bottom + 120 - Game.playerY
      if reducedMotion {
        // No camera motion: the land fades away and the boat is simply there.
        return (shore, s > 0 ? 0 : hiddenOffset, nil, 0, 1 - min(1, s * 4))
      }
      return (shore - pan * (bottom - top + 200), hiddenOffset * (1 - pan), nil, s > 0 ? s : 0, 1)
    case .won:
      // Moored at the jetty of the same land the voyage started from.
      let shore = Self.homeShore(top, bottom)
      return (shore, Self.dockY(shore) - Game.playerY, Self.dockX, 0, 1)
    default:
      guard game.outro > 0 else { return (nil, 0, nil, 0, 1) }
      // Journey's end: the home land slides into view and the boat sails up to its jetty.
      let t = max(0, min(1, 1 - game.outro / Game.outroDuration)), q = t * t * (3 - 2 * t)
      let shore = Self.homeShore(top, bottom), start = top - 160
      let land = reducedMotion ? shore : start + (shore - start) * q
      let boatY = Game.playerY + (Self.dockY(shore) - Game.playerY) * q
      return (land, boatY - Game.playerY, game.x + (Self.dockX - game.x) * q, 0, reducedMotion ? q : 1)
    }
  }

  /// Where the home shore rests at the end of the voyage, and where the boat moors beside the jetty.
  private static func homeShore(_ top: Double, _ bottom: Double) -> Double { top + (bottom - top) * 0.55 }
  private static let dockX = 500.0
  private static func dockY(_ shore: Double) -> Double { shore + 120 }

  /// A palm tree with a hard drop shadow: ink-outlined trunk and fronds.
  private func palm(_ c: CGContext, _ x: Double, _ y: Double) {
    oval(c, x + 12, y + 66, 34, 11, Ink.outline, alpha: 0.18)
    line(c, x - 16, y + 62, x, y, Ink.outline, 12)
    line(c, x - 16, y + 62, x, y, "9A6E48", 7)
    for i in 0...5 {
      let a = Double(i) * .pi / 3 + 0.2
      let ex = x + cos(a) * 44, ey = y + sin(a) * 28
      line(c, x, y, ex, ey, Ink.outline, 15, alpha: 0.85)
      line(c, x, y, ex, ey, "2E8A55", 10)
    }
  }
  /// A round bush with a shadow, ink rim and highlight.
  private func bush(_ c: CGContext, _ x: Double, _ y: Double, _ r: Double) {
    oval(c, x + 6, y + r * 0.7, r, r * 0.4, Ink.outline, alpha: 0.18)
    oval(c, x, y, r, r * 0.8, "3F8A4F")
    ring(c, x, y, r, r * 0.8, Ink.outline, 3, alpha: 0.6)
    oval(c, x - r * 0.3, y - r * 0.25, r * 0.4, r * 0.25, "6FC27A")
  }
  /// Fills the area between two wavy horizontal edges.
  private func band(_ c: CGContext, _ left: Double, _ right: Double, _ top: (Double) -> Double, _ bottom: (Double) -> Double, _ color: String, alpha: Double = 1) {
    let path = CGMutablePath()
    path.move(to: CGPoint(x: left, y: top(left)))
    for x in stride(from: left, through: right, by: 10) { path.addLine(to: CGPoint(x: x, y: top(x))) }
    for x in stride(from: right, through: left, by: -10) { path.addLine(to: CGPoint(x: x, y: bottom(x))) }
    path.closeSubpath()
    c.addPath(path)
    c.setFillColor(UIColor(hex: color).withAlphaComponent(alpha).cgColor)
    c.fillPath()
  }
  /// Strokes a wavy horizontal edge.
  private func edge(_ c: CGContext, _ left: Double, _ right: Double, _ y: (Double) -> Double, _ color: String, _ width: Double, alpha: Double = 1) {
    let path = CGMutablePath()
    path.move(to: CGPoint(x: left, y: y(left)))
    for x in stride(from: left, through: right, by: 10) { path.addLine(to: CGPoint(x: x, y: y(x))) }
    c.addPath(path)
    c.setStrokeColor(UIColor(hex: color).withAlphaComponent(alpha).cgColor)
    c.setLineWidth(width)
    c.setLineJoin(.round)
    c.strokePath()
  }

  /// The home shore at the start of a voyage, drawn in the game's toy style: an outlined
  /// coastline, a jetty, a dotted treasure-map trail and the ✗ over buried treasure.
  private func homeLand(_ c: CGContext, _ left: Double, _ right: Double, _ top: Double, _ shore: Double, _ t: Double) {
    guard shore > top else { return }
    let motion = reducedMotion ? 0 : t
    let sandEdge: (Double) -> Double = { shore + sin($0 * 0.035) * 6 }
    let grassEdge: (Double) -> Double = { shore - 50 + sin($0 * 0.028 + 1.3) * 9 }
    // Wet shallows and a foam line that laps at the beach.
    band(c, left, right, sandEdge, { shore + 30 + sin($0 * 0.05 + motion * 2) * 4 }, "7FD8CB")
    edge(c, left, right, { shore + 30 + sin($0 * 0.05 + motion * 2) * 4 }, "FFFFFF", 7, alpha: 0.55)
    // Sand, then grass, each with an ink outline like the game's islands and rocks.
    band(c, left, right, { _ in top }, sandEdge, "F6DCA0")
    edge(c, left, right, sandEdge, Ink.outline, 3, alpha: 0.5)
    band(c, left, right, { _ in top }, grassEdge, "5FB36A")
    edge(c, left, right, grassEdge, Ink.outline, 3, alpha: 0.4)
    // A wooden jetty where the voyage begins.
    let jx = 560.0
    polygon(c, [jx - 22, shore - 18, jx + 22, shore - 18, jx + 22, shore + 96, jx - 22, shore + 96], "B07B4C", outline: 3)
    for py in stride(from: shore - 4, through: shore + 86, by: 16) { line(c, jx - 20, py, jx + 20, py, "8A5A34", 2) }
    for (px, py) in [(jx - 22, shore + 40), (jx + 22, shore + 40), (jx - 22, shore + 96), (jx + 22, shore + 96)] {
      oval(c, px, py, 6, 6, "6E4830")
      ring(c, px, py, 6, 6, Ink.outline, 2)
    }
    // Grass detail: darker meadows, tufts and a few flowers.
    for (dx, dy, rx, ry) in [(120.0, 170.0, 90.0, 46.0), (650, 140, 110, 54), (260, 560, 120, 64), (600, 480, 90, 50), (90, 740, 100, 56), (700, 800, 120, 64)] {
      oval(c, dx, shore - dy, rx, ry, "4C9A5A")
    }
    for i in 0..<46 {
      let tx = left + Double(i * 157 % 941) / 941 * (right - left)
      let ty = shore - 70 - Double(i * 89 % 997) / 997 * 900
      line(c, tx - 4, ty, tx - 6, ty - 9, "3E8A4C", 2.5)
      line(c, tx, ty, tx, ty - 11, "3E8A4C", 2.5)
      line(c, tx + 4, ty, tx + 6, ty - 9, "3E8A4C", 2.5)
      if i % 5 == 0 { oval(c, tx + 10, ty - 6, 3.5, 3.5, i % 10 == 0 ? "FFF6E0" : "FFC94D") }
    }
    for (bx, by, r) in [(230.0, 250.0, 26.0), (660, 330, 30), (110, 470, 24), (520, 650, 28)] { bush(c, bx, shore - by, r) }
    for (px, py) in [(110.0, 130.0), (700, 170), (180, 430), (650, 580), (330, 760), (90, 900)] { palm(c, px, shore - py) }
    // A dotted treasure-map trail from the jetty up to the ✗.
    let mx = 400.0, my = shore - Self.markDepth
    for i in 0...16 {
      let u = Double(i) / 16, v = 1 - u
      let px = v * v * jx + 2 * v * u * 560 + u * u * (mx + 50)
      let py = v * v * (shore - 30) + 2 * v * u * (my + 40) + u * u * (my + 34)
      oval(c, px, py, 4.5, 4.5, "D9483B", alpha: 0.85)
    }
    buriedTreasure(c, mx, my, t)
  }

  /// ✗ marks the spot: two pieces of sun-bleached driftwood crossed on a sand mound in a sandy
  /// clearing, with the corner of a buried chest peeking out. No gold on show: it stays buried.
  private func buriedTreasure(_ c: CGContext, _ x: Double, _ y: Double, _ t: Double) {
    // The ✗ pops in with a small overshoot as the scene appears.
    let p = 1 - game.introTime / Game.introDuration
    let pop = game.status != .intro || reducedMotion ? 1 : Self.easeOutBack(min(1, p / 0.12))
    // Sandy clearing with ripples.
    oval(c, x + 4, y + 8, 124, 80, Ink.outline, alpha: 0.12)
    oval(c, x, y, 120, 78, "F6DCA0")
    ring(c, x, y, 120, 78, Ink.outline, 3, alpha: 0.5)
    for (dx, dy) in [(-92.0, -36.0), (60, -54), (-84, 42), (70, 48)] {
      let path = CGMutablePath()
      path.move(to: CGPoint(x: x + dx, y: y + dy))
      path.addQuadCurve(to: CGPoint(x: x + dx + 24, y: y + dy), control: CGPoint(x: x + dx + 12, y: y + dy - 8))
      c.addPath(path)
      c.setStrokeColor(UIColor(hex: "E5C384").cgColor)
      c.setLineWidth(3)
      c.setLineCap(.round)
      c.strokePath()
    }
    // The sand mound and the buried chest's corner.
    oval(c, x + 5, y + 10, 76, 46, Ink.outline, alpha: 0.18)
    oval(c, x, y, 72, 44, "E9C98A")
    ring(c, x, y, 72, 44, Ink.outline, 3)
    oval(c, x - 12, y - 12, 40, 18, "F7E2B0")
    polygon(c, [x + 20, y + 14, x + 56, y + 6, x + 60, y + 32, x + 24, y + 38], "8A5A34", outline: 3)
    line(c, x + 22, y + 24, x + 58, y + 16, "F3C75F", 4)
    // Two driftwood branches crossed into the ✗.
    c.saveGState()
    c.translateBy(x: x, y: y)
    c.scaleBy(x: pop, y: pop)
    func branch(_ pts: [Double], _ wood: String) {
      let path = CGMutablePath()
      path.move(to: CGPoint(x: pts[0], y: pts[1]))
      path.addCurve(to: CGPoint(x: pts[6], y: pts[7]), control1: CGPoint(x: pts[2], y: pts[3]), control2: CGPoint(x: pts[4], y: pts[5]))
      path.addCurve(to: CGPoint(x: pts[12], y: pts[13]), control1: CGPoint(x: pts[8], y: pts[9]), control2: CGPoint(x: pts[10], y: pts[11]))
      c.setLineCap(.round)
      c.addPath(path); c.setStrokeColor(UIColor(hex: Ink.outline).cgColor); c.setLineWidth(18); c.strokePath()
      c.addPath(path); c.setStrokeColor(UIColor(hex: wood).cgColor); c.setLineWidth(11); c.strokePath()
    }
    branch([-56, -26, -36, -22, -16, -8, 0, 4, 16, 16, 34, 26, 50, 30], "B8A48A")
    branch([54, -28, 36, -20, 18, -8, 2, 6, -14, 20, -30, 30, -46, 36], "CDBA9E")
    for (x1, y1, x2, y2) in [(30.0, -14.0, 40.0, -26.0), (-30, 14, -36, 26)] {
      line(c, x1, y1, x2, y2, Ink.outline, 6)
      line(c, x1, y1, x2, y2, "CDBA9E", 3)
    }
    oval(c, -30, -12, 2.5, 2.5, "7E6B55")
    oval(c, 20, 10, 2.5, 2.5, "7E6B55")
    c.restoreGState()
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
