import AVFoundation
import QuartzCore
import SwiftUI

@main
struct WakeRunnerApp: App {
  @StateObject private var model = GameModel()
  @Environment(\.scenePhase) private var scenePhase
  var body: some Scene {
    WindowGroup {
      GameScreen(model: model)
        .preferredColorScheme(.dark)
        // The single-value form keeps iOS 15/16 support (deprecated only from iOS 17).
        .onChange(of: scenePhase) { phase in
          if phase == .active { model.activate() } else { model.deactivate() }
        }
        .onAppear { model.activate() }
    }
  }
}

// The display link retains only a proxy; the proxy holds the model weakly.
private final class FrameProxy: NSObject {
  weak var model: GameModel?
  @objc func frame(_ link: CADisplayLink) { model?.tick(link.timestamp) }
}

final class GameModel: ObservableObject {
  let game = Game()
  @Published var frame = 0
  @Published var best = UserDefaults.standard.integer(forKey: "wake-runner-best")
  @Published var sound = UserDefaults.standard.bool(forKey: "wake-runner-sound")
  @Published var notice: Notice?
  private var noticeUntil = 0.0
  private var displayLink: CADisplayLink?
  private var previous = 0.0
  private var players: [String: AVAudioPlayer] = [:]
  private let proxy = FrameProxy()
  init() {
    proxy.model = self
    game.onEvent = { [weak self] event in self?.event(event) }
    try? AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default)
  }
  func activate() {
    guard displayLink == nil else { return }
    previous = 0
    let link = CADisplayLink(target: proxy, selector: #selector(FrameProxy.frame(_:)))
    link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
    link.add(to: .main, forMode: .common)
    displayLink = link
  }
  func deactivate() {
    if game.status == .playing { game.pause() }
    game.firing = false
    saveBest()
    displayLink?.invalidate()
    displayLink = nil
    previous = 0
    frame &+= 1
    for player in players.values { player.stop() }
  }
  func tick(_ now: Double) {
    let delta = previous == 0 ? 0 : now - previous
    previous = now
    game.update(delta)
    if noticeUntil > 0 && now > noticeUntil {
      notice = nil
      noticeUntil = 0
    }
    if game.status == .playing { frame &+= 1 }
  }
  func startOrResume() {
    if game.status == .paused { game.pause() } else { game.reset() }
    notice = nil
    frame &+= 1
  }
  func pause() {
    game.pause()
    frame &+= 1
  }
  /// Continues a finished voyage after the rewarded ad was watched to the end.
  func revive() {
    if game.revive() { frame &+= 1 }
  }
  func saveBest() {
    if game.score > best {
      best = game.score
      UserDefaults.standard.set(best, forKey: "wake-runner-best")
    }
  }
  func toggleSound() {
    sound.toggle()
    UserDefaults.standard.set(sound, forKey: "wake-runner-sound")
  }
  private func event(_ event: String) {
    switch event {
    case "damage": notice = Notice("Hull hit — change lanes!", "exclamationmark.triangle.fill", .danger)
    case "jump": notice = Notice("Catching some air", "arrow.up.circle.fill", .gold)
    case "heart": notice = Notice("+10 hull · +50 points", "heart.fill", .heart)
    case "sink": notice = Notice("\(game.lastSunkName) sunk · +\(game.lastSunkReward) points", "flag.checkered", .gold)
    case "level": notice = Notice("Level \(game.level) · rougher waters", "wind", .mint)
    case "revive": notice = Notice("Second wind · \(Game.reviveHealth) hull", "heart.fill", .mint)
    case "over":
      saveBest()
      frame &+= 1
    default: break
    }
    if ["damage", "jump", "heart", "sink", "level", "revive"].contains(event) {
      noticeUntil = CACurrentMediaTime() + 1.6
    }
    if sound && ["fire", "damage", "heart", "jump", "sink"].contains(event) { playTone(event) }
    if event == "over" {
      UIAccessibility.post(
        notification: .announcement, argument: "Voyage complete. Score \(game.score).")
    }
  }
  // Short synthesized WAV effects need no bundled or network audio assets.
  private func playTone(_ event: String) {
    if players[event] == nil {
      let frequency = event == "damage" || event == "sink" ? 110.0 : event == "fire" ? 190.0 : 620.0
      let rate = 22050
      let count = rate / 10
      var data = Data()
      func string(_ s: String) { data.append(contentsOf: s.utf8) }
      func word(_ value: UInt16) {
        var v = value.littleEndian
        withUnsafeBytes(of: &v) { data.append(contentsOf: $0) }
      }
      func long(_ value: UInt32) {
        var v = value.littleEndian
        withUnsafeBytes(of: &v) { data.append(contentsOf: $0) }
      }
      string("RIFF")
      long(UInt32(36 + count * 2))
      string("WAVEfmt ")
      long(16)
      word(1)
      word(1)
      long(UInt32(rate))
      long(UInt32(rate * 2))
      word(2)
      word(16)
      string("data")
      long(UInt32(count * 2))
      for i in 0..<count {
        let envelope = pow(1 - Double(i) / Double(count), 2)
        let sample = Int16(sin(Double(i) / Double(rate) * frequency * 2 * .pi) * envelope * 5000)
        word(UInt16(bitPattern: sample))
      }
      players[event] = try? AVAudioPlayer(data: data)
      players[event]?.prepareToPlay()
    }
    players[event]?.currentTime = 0
    players[event]?.play()
  }
}

// MARK: - Design tokens
// Claymorphism-inspired toy-boat theme: chunky ink outlines, hard drop shadows,
// rounded type, warm gold for actions and rewards on a tropical sea.

struct Notice: Hashable {
  enum Tone { case danger, gold, heart, mint }
  let text: String
  let icon: String
  let tone: Tone
  init(_ text: String, _ icon: String, _ tone: Tone) {
    self.text = text
    self.icon = icon
    self.tone = tone
  }
  var tint: Color {
    switch tone {
    case .danger: Theme.danger
    case .gold: Theme.gold
    case .heart: Theme.heart
    case .mint: Theme.mint
    }
  }
}

enum Theme {
  static let ink = Color(hex: "0B2530")
  static let deep = Color(hex: "0E3440")
  static let surface = Color(hex: "17485A")
  static let surfaceTop = Color(hex: "21627A")
  static let cream = Color(hex: "FFF6E0")
  static let creamMuted = Color(hex: "C9E4E0")
  static let gold = Color(hex: "FFC94D")
  static let goldTop = Color(hex: "FFE08A")
  static let goldDeep = Color(hex: "F2A922")
  static let coral = Color(hex: "FF7A59")
  static let mint = Color(hex: "5FE3C0")
  static let heart = Color(hex: "FF8FA3")
  static let danger = Color(hex: "FF5D73")
  static let sea = Color(hex: "1E8C9A")
  static let border: CGFloat = 3
  static let depth: CGFloat = 4
}

extension Color {
  init(hex: String) { self.init(uiColor: UIColor(hex: hex)) }
}

/// Toy-like surface: gradient fill, ink outline, top highlight and a hard offset shadow.
/// The shadow is a plain shape (no blur) so the HUD stays cheap to redraw every frame.
struct Clay: ViewModifier {
  var top = Theme.surfaceTop
  var fill = Theme.surface
  var radius: CGFloat = 18
  var depth = Theme.depth
  var pressed = false
  func body(content: Content) -> some View {
    let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
    content
      .background {
        ZStack {
          shape.fill(Theme.ink).offset(y: pressed ? 1 : depth)
          shape.fill(LinearGradient(colors: [top, fill], startPoint: .top, endPoint: .bottom))
          shape.inset(by: Theme.border + 1.5)
            .trim(from: 0.55, to: 0.95)
            .stroke(.white.opacity(0.22), style: StrokeStyle(lineWidth: 2, lineCap: .round))
          shape.strokeBorder(Theme.ink, lineWidth: Theme.border)
        }
      }
      .offset(y: pressed ? depth - 1 : 0)
  }
}

extension View {
  func clay(
    top: Color = Theme.surfaceTop, fill: Color = Theme.surface, radius: CGFloat = 18,
    pressed: Bool = false
  ) -> some View {
    modifier(Clay(top: top, fill: fill, radius: radius, pressed: pressed))
  }
}

struct ClayButtonStyle: ButtonStyle {
  enum Kind { case primary, secondary }
  var kind = Kind.secondary
  var radius: CGFloat = 18
  @Environment(\.isEnabled) private var enabled
  func makeBody(configuration: Configuration) -> some View {
    let primary = kind == .primary
    configuration.label
      .foregroundStyle(primary ? Theme.ink : Theme.cream)
      .clay(
        top: primary ? Theme.goldTop : Theme.surfaceTop,
        fill: primary ? Theme.gold : Theme.surface, radius: radius,
        pressed: configuration.isPressed
      )
      .opacity(enabled ? 1 : 0.45)
      .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
      .contentShape(RoundedRectangle(cornerRadius: radius))
  }
}

/// Scores always show at least six digits (000637) so the HUD keeps a steady width.
func scoreDigits(_ score: Int) -> String { String(format: "%06d", max(0, score)) }

// MARK: - iOS 15 compatibility
// The app supports iOS 15+. Newer APIs and SF Symbols are used when available, with the
// closest iOS 15 equivalent otherwise.

/// SF Symbols added in iOS 16, mapped to iOS 13-era stand-ins.
private let symbolFallbacks = [
  "flag.checkered": "flag.fill", "sailboat.fill": "play.fill",
  "trophy.fill": "star.fill", "water.waves": "map.fill",
]
func symbol(_ name: String) -> String {
  UIImage(systemName: name) != nil ? name : symbolFallbacks[name] ?? name
}

extension View {
  /// SF Rounded everywhere on iOS 16.1+; the system font on older versions.
  @ViewBuilder func roundedDesign() -> some View {
    if #available(iOS 16.1, *) { fontDesign(.rounded) } else { self }
  }
  /// Stops the per-frame score from cross-fading between values.
  @ViewBuilder func steadyDigits() -> some View {
    if #available(iOS 16, *) { contentTransition(.identity) } else { self }
  }
  /// Centers short menu content and only bounces when it overflows.
  @ViewBuilder func centeredScroll() -> some View {
    if #available(iOS 17, *) {
      defaultScrollAnchor(.center).scrollBounceBehavior(.basedOnSize)
    } else if #available(iOS 16.4, *) {
      scrollBounceBehavior(.basedOnSize)
    } else {
      self
    }
  }
}

/// NavigationStack on iOS 16+, a stack-style NavigationView on iOS 15.
struct CompatNavigation<Content: View>: View {
  @ViewBuilder let content: () -> Content
  var body: some View {
    if #available(iOS 16, *) {
      NavigationStack(root: content)
    } else {
      NavigationView(content: content).navigationViewStyle(.stack)
    }
  }
}

// MARK: - Screen

struct GameScreen: View {
  @ObservedObject var model: GameModel
  @State private var help = false
  @State private var watchingAd = false
  @State private var firePressed = false
  @Environment(\.dynamicTypeSize) private var typeSize
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  var body: some View {
    GeometryReader { geometry in
      let landscape = geometry.size.width > geometry.size.height
      ZStack {
        GameSurface(
          game: model.game, frame: model.frame,
          contentInsets: UIEdgeInsets(
            top: geometry.safeAreaInsets.top + (typeSize >= .xxLarge ? 84 : 72),
            left: geometry.safeAreaInsets.leading,
            bottom: geometry.safeAreaInsets.bottom + (landscape ? 16 : 116),
            right: geometry.safeAreaInsets.trailing + (landscape ? 112 : 0))
        )
        .ignoresSafeArea()
        .accessibilityHidden(model.game.status != .playing)
        if model.game.status == .playing {
          VStack(spacing: 0) {
            hud(landscape: landscape)
            if let notice = model.notice {
              toast(notice)
                .padding(.top, 14)
                .transition(
                  reduceMotion
                    ? .opacity : .move(edge: .top).combined(with: .opacity).combined(with: .scale(scale: 0.9)))
                .id(notice)
            }
            Spacer(minLength: 0)
            controls
          }
          .padding(.horizontal, landscape ? 24 : 16)
          .padding(.top, 8).padding(.bottom, 12)
          .animation(reduceMotion ? nil : .spring(response: 0.32, dampingFraction: 0.75), value: model.notice)
          // Keep the real-time HUD compact; menus retain the full Dynamic Type range.
          .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
          .transition(.opacity)
        } else {
          menu
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.96)))
        }
      }
      .animation(reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.38, dampingFraction: 0.82), value: model.game.status)
    }
    .background(Theme.sea.ignoresSafeArea())
    .foregroundStyle(Theme.cream)
    .roundedDesign()
    .statusBarHidden(true)
    .sheet(isPresented: $help) { handbook }
    .fullScreenCover(isPresented: $watchingAd) {
      // Swap PlaceholderAdView for a real rewarded ad (and PlaceholderRewardedAds on Android).
      PlaceholderAdView(
        onReward: {
          watchingAd = false
          model.revive()
        },
        onClose: { watchingAd = false })
    }
  }

  // MARK: HUD

  private func hud(landscape: Bool) -> some View {
    HStack(alignment: .center, spacing: 10) {
      Button(action: model.pause) {
        Image(systemName: "pause.fill").font(.system(size: 18, weight: .black))
          .frame(width: 48, height: 48)
      }
      .buttonStyle(ClayButtonStyle(radius: 24))
      .accessibilityLabel("Pause")
      hullCard.frame(maxWidth: landscape ? 280 : .infinity)
      if landscape { Spacer(minLength: 0) }
      scoreCard
    }
  }

  private var hullColor: Color {
    model.game.health <= 25 ? Theme.danger : model.game.health <= 50 ? Theme.gold : Theme.mint
  }

  private var hullCard: some View {
    let health = Double(model.game.health) / 100
    return VStack(alignment: .leading, spacing: 5) {
      HStack(spacing: 5) {
        Image(systemName: model.game.health <= 25 ? "heart.slash.fill" : "heart.fill")
          .foregroundStyle(hullColor)
        Text("\(model.game.health)%").monospacedDigit()
        Spacer(minLength: 4)
      }
      .font(.footnote.weight(.heavy))
      Capsule().fill(Theme.ink.opacity(0.75))
        .frame(height: 10)
        .overlay(alignment: .leading) {
          GeometryReader { g in
            Capsule()
              .fill(LinearGradient(colors: [hullColor.opacity(0.8), hullColor], startPoint: .leading, endPoint: .trailing))
              .frame(width: max(health > 0 ? 10 : 0, g.size.width * health))
              .animation(reduceMotion ? nil : .spring(response: 0.35), value: model.game.health)
          }
          .padding(2)
        }
        .overlay {
          // Each rock costs one quarter; the ticks make that readable at a glance.
          GeometryReader { g in
            ForEach(1..<4, id: \.self) { i in
              Rectangle().fill(Theme.ink.opacity(0.7)).frame(width: 2)
                .offset(x: g.size.width * CGFloat(i) / 4 - 1)
            }
          }
        }
    }
    .padding(.horizontal, 12).padding(.vertical, 8)
    .frame(minHeight: 48)
    .clay()
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Hull \(model.game.health) percent")
  }

  private var scoreCard: some View {
    VStack(alignment: .trailing, spacing: 0) {
      Text("LEVEL \(model.game.level)")
        .font(.caption2.weight(.black)).tracking(1.2)
        .foregroundStyle(Theme.gold)
      Text(scoreDigits(model.game.score))
        .font(.title3.weight(.black)).monospacedDigit()
        .fixedSize().steadyDigits()
    }
    .padding(.horizontal, 12).padding(.vertical, 6)
    .frame(minWidth: 84, minHeight: 48, alignment: .trailing)
    .clay()
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Score \(model.game.score), level \(model.game.level)")
  }

  private func toast(_ notice: Notice) -> some View {
    HStack(spacing: 8) {
      Image(systemName: symbol(notice.icon)).foregroundStyle(notice.tint).accessibilityHidden(true)
      Text(notice.text)
    }
    .font(.subheadline.weight(.heavy))
    .multilineTextAlignment(.center)
    .padding(.horizontal, 16).padding(.vertical, 10)
    .clay(top: Theme.surface, fill: Theme.deep, radius: 22)
    .allowsHitTesting(false)
  }

  private var controls: some View {
    ZStack {
      fireFace
      HoldFireButton(game: model.game) { pressed in firePressed = pressed }
    }
    .frame(width: 84, height: 84)
    .frame(maxWidth: .infinity, alignment: .trailing)
    .accessibilityIdentifier("game-controls")
  }

  private var fireFace: some View {
    VStack(spacing: 1) {
      Image(systemName: "scope").font(.system(size: 26, weight: .black))
      Text("FIRE").font(.system(size: 12, weight: .black, design: .rounded)).tracking(1.5)
    }
    .foregroundStyle(Theme.ink)
    .frame(width: 84, height: 84)
    .clay(top: Theme.goldTop, fill: Theme.goldDeep, radius: 42, pressed: firePressed)
    .animation(.easeOut(duration: 0.08), value: firePressed)
    .allowsHitTesting(false)
    .accessibilityHidden(true)
  }

  // MARK: Menu

  private var status: GameStatus { model.game.status }
  private var newBest: Bool { status == .over && model.game.score > 0 && model.game.score >= model.best }

  private var menu: some View {
    ZStack {
      LinearGradient(
        colors: [Theme.ink.opacity(0.35), Theme.ink.opacity(0.8)], startPoint: .top,
        endPoint: .bottom
      ).ignoresSafeArea()
      ScrollView {
        VStack(spacing: 18) {
          Text(
            status == .ready
              ? "THE OPEN WATER IS CALLING"
              : status == .paused ? "TAKE A BREATHER" : "VOYAGE COMPLETE"
          )
          .font(.caption.weight(.black)).tracking(1.6).foregroundStyle(Theme.gold)
          .padding(.horizontal, 12).padding(.vertical, 6)
          .background(Theme.ink.opacity(0.85), in: Capsule())
          title
          if status == .over { results }
          bestChip
          if canContinue { continueButton }
          Button(action: model.startOrResume) {
            Label(
              status == .paused
                ? "Back to the water" : status == .over ? "One more voyage" : "Set sail",
              systemImage: symbol(
                status == .paused ? "play.fill" : status == .over ? "arrow.clockwise" : "sailboat.fill")
            )
            .font(.title3.weight(.black))
            .frame(maxWidth: .infinity, minHeight: 60)
          }
          // After a game over the ad continue is the main action; a fresh voyage is secondary.
          .buttonStyle(ClayButtonStyle(kind: canContinue ? .secondary : .primary, radius: 22))
          Group {
            if typeSize.isAccessibilitySize {
              VStack(spacing: 12) { soundOption; helpOption }
            } else {
              HStack(spacing: 12) { soundOption; helpOption }
            }
          }
          .buttonStyle(ClayButtonStyle(radius: 16))
          if status == .ready { tips }
        }
        .padding(20)
        .clay(top: Theme.surface, fill: Theme.deep, radius: 32)
        .padding(.horizontal, 16).padding(.vertical, 24)
        .frame(maxWidth: 460).frame(maxWidth: .infinity)
      }
      .centeredScroll()
    }
    .accessibilityIdentifier("game-menu")
  }

  private var title: some View {
    let text = status == .ready ? "WAKE\nRUNNER" : status == .paused ? "PAUSED" : "WHAT A\nVOYAGE!"
    return ZStack {
      Text(text).foregroundStyle(Theme.ink).offset(y: 4).accessibilityHidden(true)
      Text(text).foregroundStyle(Theme.cream)
    }
    .font(.system(.largeTitle, design: .rounded).weight(.black))
    .scaleEffect(typeSize.isAccessibilitySize ? 1 : 1.25)
    .padding(.vertical, typeSize.isAccessibilitySize ? 0 : 6)
    .multilineTextAlignment(.center)
    .lineSpacing(-4)
    .accessibilityElement(children: .combine)
    .accessibilityAddTraits(.isHeader)
  }

  private var results: some View {
    VStack(spacing: 14) {
      VStack(spacing: 2) {
        if newBest {
          Label {
            Text("NEW BEST").tracking(1.2)
          } icon: {
            Image(systemName: "crown.fill")
          }
            .font(.caption.weight(.black)).foregroundStyle(Theme.ink)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(Theme.mint, in: Capsule())
        }
        Text(scoreDigits(model.game.score))
          .font(.system(size: 52, weight: .black, design: .rounded)).monospacedDigit()
          .foregroundStyle(Theme.gold)
          .minimumScaleFactor(0.6).lineLimit(1)
      }
      .accessibilityElement(children: .combine)
      HStack(spacing: 10) {
        stat("water.waves", "\(Int(model.game.distance)) m", "sailed")
        stat("flag.checkered", "\(model.game.piratesSunk)", "sunk")
        stat("heart.fill", "\(model.game.heartsCollected)", "hearts")
      }
    }
  }

  private func stat(_ icon: String, _ value: String, _ label: String) -> some View {
    VStack(spacing: 2) {
      Image(systemName: symbol(icon)).font(.subheadline.weight(.bold)).foregroundStyle(Theme.mint)
        .accessibilityHidden(true)
      Text(value).font(.headline.weight(.black)).monospacedDigit()
        .lineLimit(1).minimumScaleFactor(0.7)
      Text(label).font(.caption.weight(.bold)).foregroundStyle(Theme.creamMuted)
    }
    .frame(maxWidth: .infinity).padding(.vertical, 10)
    .clay(radius: 16)
    .accessibilityElement(children: .combine)
  }

  /// The ad continue is offered only while an ad can actually be shown.
  private var canContinue: Bool { PlaceholderAdView.isAvailable && model.game.canRevive }

  /// "Watch ad to continue": plays a rewarded ad, then revives this voyage (up to 5 times).
  private var continueButton: some View {
    Button {
      watchingAd = true
    } label: {
      VStack(spacing: 3) {
        Label("Watch ad to continue", systemImage: "play.rectangle.fill")
          .font(.title3.weight(.black))
        Text("Back in with \(Game.reviveHealth)% hull · \(model.game.revivesLeft) left")
          .font(.caption.weight(.heavy)).opacity(0.75)
      }
      .frame(maxWidth: .infinity, minHeight: 64)
      .padding(.vertical, 4)
    }
    .buttonStyle(ClayButtonStyle(kind: .primary, radius: 22))
    .accessibilityLabel("Watch ad to continue")
    .accessibilityHint("Continues this voyage with \(Game.reviveHealth) percent hull. \(model.game.revivesLeft) continues left.")
  }

  private var bestChip: some View {
    Label {
      Text("Personal best  \(scoreDigits(model.best))").monospacedDigit()
    } icon: {
      Image(systemName: symbol("trophy.fill")).foregroundStyle(Theme.gold)
    }
    .font(.subheadline.weight(.heavy))
    .foregroundStyle(Theme.creamMuted)
  }

  private var tips: some View {
    let items = [
      ("hand.draw.fill", "Swipe to steer"), ("arrow.up.circle.fill", "Swipe up to jump"),
      ("scope", "Hold Fire to shoot"), ("hand.tap.fill", "Tap a pirate to aim"),
    ]
    return LazyVGrid(
      columns: typeSize.isAccessibilitySize
        ? [GridItem(.flexible())] : [GridItem(.flexible(), spacing: 8), GridItem(.flexible())],
      alignment: .leading, spacing: 8
    ) {
      ForEach(items, id: \.1) { icon, text in
        HStack(spacing: 8) {
          Image(systemName: icon).foregroundStyle(Theme.gold).frame(width: 20)
            .accessibilityHidden(true)
          Text(text).fixedSize(horizontal: false, vertical: true)
        }
        .font(.footnote.weight(.bold))
        .foregroundStyle(Theme.creamMuted)
        .frame(maxWidth: .infinity, alignment: .leading)
      }
    }
    .padding(12)
    .background(Theme.ink.opacity(0.45), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
  }

  private var soundOption: some View {
    Button {
      model.toggleSound()
    } label: {
      Label(
        model.sound ? "Sound on" : "Sound off",
        systemImage: model.sound ? "speaker.wave.2.fill" : "speaker.slash.fill"
      )
      .font(.subheadline.weight(.heavy))
      .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, minHeight: 50)
    }
    .accessibilityLabel(model.sound ? "Turn sound off" : "Turn sound on")
    .accessibilityValue(model.sound ? "On" : "Off")
  }

  private var helpOption: some View {
    Button {
      help = true
    } label: {
      Label("How to play", systemImage: "book.fill")
        .font(.subheadline.weight(.heavy))
        .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, minHeight: 50)
    }
  }

  // MARK: Handbook

  private var handbook: some View {
    let rules: [(String, Color, String, String)] = [
      ("hand.draw.fill", Theme.mint, "Steer",
       "Swipe left or right across the water to change lanes. VoiceOver actions on the waterway also let you steer and jump."),
      ("scope", Theme.gold, "Fire",
       "Hold Fire to shoot ahead. Tap a pirate or mine to aim across lanes. Each hit removes one health pip; tougher ships earn more points."),
      ("arrow.up.circle.fill", Theme.gold, "Jump",
       "Swipe up any time to jump over rocks for 1.15 seconds. You can't change lanes in the air, and ships, cannonballs and mines still hit you. Jumps never run out: after landing, you can jump again two seconds later."),
      ("heart.fill", Theme.heart, "Hull",
       "Hearts repair 10 hull. Rocks cost 25 hull; cannonballs cost 15, or 25 for heavy and sniper shots. Mines cost 25, ships 30, and charging Rammers 40."),
      ("flag.fill", Theme.gold, "Pirate fleet",
       "Scout 1 hull · Gunner 2 · Burst Raider 3 · Scattershot 3 · Sharpshooter 2 · Rammer 4 · Minelayer 3 · Ironclad 6 · Captain 10. New types arrive as levels increase."),
      ("exclamationmark.triangle.fill", Theme.danger, "Read the attack",
       "Dodge aimed shots and three-shot bursts, find gaps in spreads, and use long reloads to attack. A Sharpshooter marks its aim before firing; move after the lock. Rammers warn before charging. Shoot mines to clear a path. Captains alternate bursts and spreads and attack faster at half health."),
      ("wind", Theme.mint, "Levels",
       "Every 300 meters the water gets faster. Pause is at the top left; sound and help are in the pause menu."),
    ]
    return CompatNavigation {
      ScrollView {
        VStack(alignment: .leading, spacing: 14) {
          Text("Captain’s handbook").font(.largeTitle.weight(.black))
            .accessibilityAddTraits(.isHeader)
          ForEach(rules, id: \.2) { icon, tint, heading, body in
            HStack(alignment: .top, spacing: 14) {
              Image(systemName: icon).font(.title3.weight(.bold)).foregroundStyle(tint)
                .frame(width: 44, height: 44)
                .background(Theme.ink.opacity(0.6), in: Circle())
                .accessibilityHidden(true)
              VStack(alignment: .leading, spacing: 4) {
                Text(heading).font(.headline.weight(.black))
                Text(body).font(.body).foregroundStyle(Theme.creamMuted)
                  .fixedSize(horizontal: false, vertical: true)
              }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .clay(radius: 20)
            .accessibilityElement(children: .combine)
          }
        }
        .padding(20).frame(maxWidth: 560).frame(maxWidth: .infinity)
      }
      .background(Theme.deep.ignoresSafeArea())
      .foregroundStyle(Theme.cream)
      .roundedDesign()
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button("Done") { help = false }.font(.body.weight(.heavy)).tint(Theme.gold)
        }
      }
    }
  }
}

/// Stand-in rewarded ad: a full-screen countdown. `onReward` runs only when it was watched to
/// the end; closing early calls `onClose` alone. Matches PlaceholderRewardedAds on Android.
struct PlaceholderAdView: View {
  /// App Review rejects placeholder content (Guideline 2.1), so the stand-in only runs in debug
  /// builds. Release builds hide "Watch ad to continue" until a real ad network replaces this.
  #if DEBUG
    static let isAvailable = true
  #else
    static let isAvailable = false
  #endif
  var seconds = 5
  let onReward: () -> Void
  let onClose: () -> Void
  @State private var elapsed = 0
  private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
  private var remaining: Int { max(0, seconds - elapsed) }

  var body: some View {
    VStack(spacing: 0) {
      HStack {
        Text("AD").font(.caption.weight(.black)).tracking(1.4).foregroundStyle(Theme.ink)
          .padding(.horizontal, 10).padding(.vertical, 4)
          .background(Theme.gold, in: Capsule())
        Spacer()
        Button(action: onClose) {
          Image(systemName: "xmark").font(.system(size: 16, weight: .black))
            .frame(width: 44, height: 44)
        }
        .buttonStyle(ClayButtonStyle(radius: 22))
        .accessibilityLabel("Close ad without reward")
      }
      Spacer()
      ZStack {
        Circle().stroke(Theme.surface, lineWidth: 10)
        Circle()
          .trim(from: 0, to: CGFloat(elapsed) / CGFloat(seconds))
          .stroke(Theme.gold, style: StrokeStyle(lineWidth: 10, lineCap: .round))
          .rotationEffect(.degrees(-90))
          .animation(.linear(duration: 1), value: elapsed)
        if remaining > 0 {
          Text("\(remaining)").font(.system(size: 48, weight: .black)).monospacedDigit()
        } else {
          Image(systemName: "checkmark").font(.system(size: 48, weight: .black)).foregroundStyle(Theme.gold)
        }
      }
      .frame(width: 140, height: 140)
      .accessibilityElement(children: .ignore)
      .accessibilityLabel(remaining > 0 ? "\(remaining) seconds left" : "Reward ready")
      Text("Placeholder ad").font(.title2.weight(.black)).padding(.top, 20)
      Text("A real rewarded ad will play here.")
        .font(.subheadline.weight(.bold)).foregroundStyle(Theme.creamMuted).padding(.top, 8)
      Spacer()
      Button(action: onReward) {
        Label(remaining > 0 ? "Reward in \(remaining)s" : "Continue voyage", systemImage: "play.fill")
          .font(.title3.weight(.black))
          .frame(maxWidth: .infinity, minHeight: 60)
      }
      .buttonStyle(ClayButtonStyle(kind: .primary, radius: 22))
      .disabled(remaining > 0)
    }
    .padding(20)
    .background(Theme.ink.ignoresSafeArea())
    .foregroundStyle(Theme.cream)
    .multilineTextAlignment(.center)
    .roundedDesign()
    .onReceive(timer) { _ in
      if elapsed < seconds {
        elapsed += 1
        if remaining == 0 { UIAccessibility.post(notification: .announcement, argument: "Reward ready") }
      }
    }
  }
}

// UIControl tracks a real press, so holding Fire can coexist with steering/jump.
// The control itself is transparent; SwiftUI draws the clay face beneath it.
private struct HoldFireButton: UIViewRepresentable {
  let game: Game
  let onPress: (Bool) -> Void
  @Environment(\.isEnabled) private var enabled
  func makeUIView(context: Context) -> CannonButton { CannonButton(game: game) }
  func updateUIView(_ view: CannonButton, context: Context) {
    view.onPress = onPress
    view.isEnabled = enabled
    if !enabled { game.firing = false }
  }
}

private final class CannonButton: UIButton {
  let game: Game
  var onPress: (Bool) -> Void = { _ in }
  init(game: Game) {
    self.game = game
    super.init(frame: .zero)
    backgroundColor = .clear
    accessibilityLabel = "Fire cannon"
    accessibilityHint = "Hold for continuous fire."
    addTarget(self, action: #selector(beginFire), for: .touchDown)
    addTarget(
      self, action: #selector(endFire),
      for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])
  }
  required init?(coder: NSCoder) { fatalError("Use init(game:)") }
  override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
    // Circular hit area matching the visible button, padded slightly for thumbs.
    hypot(point.x - bounds.midX, point.y - bounds.midY) <= bounds.width / 2 + 8
  }
  @objc private func beginFire() {
    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    onPress(true)
    game.firing = true
    game.fire()
  }
  @objc private func endFire() {
    onPress(false)
    game.firing = false
  }
  override func accessibilityActivate() -> Bool {
    game.fire()
    return true
  }
}
