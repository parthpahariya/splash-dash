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
        .onChange(of: scenePhase) { _, phase in
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
  @Published var notice = ""
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
      notice = ""
      noticeUntil = 0
    }
    if game.status == .playing { frame &+= 1 }
  }
  func startOrResume() {
    if game.status == .paused { game.pause() } else { game.reset() }
    notice = ""
    frame &+= 1
  }
  func pause() {
    game.pause()
    frame &+= 1
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
    case "damage": notice = "Hull hit — change lanes!"
    case "jump": notice = "Catching some air"
    case "heart": notice = "+25 hull · +50 points"
    case "pickupJump": notice = "Jump boost +1"
    case "sink": notice = "Pirate sunk · +250 points"
    case "level": notice = "Level \(game.level) · rougher waters"
    case "over":
      saveBest()
      frame &+= 1
    default: break
    }
    if ["damage", "jump", "heart", "pickupJump", "sink", "level"].contains(event) {
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

private enum Palette {
  static let background = Color(uiColor: UIColor(hex: "102C34"))
  static let sea = Color(uiColor: UIColor(hex: "277F89"))
  static let surface = Color(uiColor: UIColor(hex: "193E48"))
  static let cream = Color(uiColor: UIColor(hex: "F8EED6"))
  static let gold = Color(uiColor: UIColor(hex: "F2C982"))
  static let mint = Color(uiColor: UIColor(hex: "8CE3CD"))
}

struct GameScreen: View {
  @ObservedObject var model: GameModel
  @State private var help = false
  @Environment(\.dynamicTypeSize) private var typeSize

  var body: some View {
    GeometryReader { geometry in
      let landscape = geometry.size.width > geometry.size.height
      ZStack {
        GameSurface(
          game: model.game, frame: model.frame,
          contentInsets: UIEdgeInsets(
            top: geometry.safeAreaInsets.top + (typeSize >= .xxLarge ? 120 : 100),
            left: geometry.safeAreaInsets.leading,
            bottom: geometry.safeAreaInsets.bottom + (landscape ? 16 : 112),
            right: geometry.safeAreaInsets.trailing + (landscape ? 100 : 0))
        )
        .ignoresSafeArea()
        .accessibilityHidden(model.game.status != .playing)
        if model.game.status == .playing {
          VStack(spacing: 0) {
            hud
            if !model.notice.isEmpty {
              Text(model.notice).font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.center).padding(.horizontal, 16).padding(.vertical, 8)
                .background(Palette.background.opacity(0.94), in: Capsule())
                .padding(.top, 12).allowsHitTesting(false)
            }
            Spacer(minLength: 0)
            controls
          }
          .padding(.horizontal, landscape ? 24 : 16)
          .padding(.top, 8).padding(.bottom, 12)
          // Keep the real-time HUD compact; menus retain the full Dynamic Type range.
          .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        } else {
          menu
        }
      }
    }
    .background(Palette.sea.ignoresSafeArea())
    .foregroundStyle(Palette.cream)
    .statusBarHidden(true)
    .sheet(isPresented: $help) { handbook }
  }

  private var hud: some View {
    HStack(alignment: .top, spacing: 12) {
      VStack(alignment: .leading, spacing: 4) {
        Text("SCORE").font(.caption2.weight(.bold)).tracking(2)
        Text(model.game.score.formatted()).font(.system(.title2, design: .rounded, weight: .heavy))
          .monospacedDigit().contentTransition(.identity)
        HStack(spacing: 6) {
          Image(systemName: "heart.fill").font(.caption).accessibilityHidden(true)
          ProgressView(value: Double(model.game.health), total: 100)
            .tint(model.game.health <= 25 ? .pink : Palette.mint).frame(width: 76)
            .accessibilityHidden(true)
          Text("\(model.game.health)%").font(.caption.weight(.bold)).monospacedDigit()
        }.foregroundStyle(Palette.mint)
      }
      .accessibilityElement(children: .ignore)
      .accessibilityLabel("Score \(model.game.score), hull \(model.game.health) percent")
      Spacer(minLength: 8)
      Text("LEVEL \(model.game.level)").font(.caption.weight(.heavy)).tracking(1)
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Palette.background.opacity(0.9), in: Capsule())
      Button(action: model.pause) {
        Image(systemName: "pause.fill").font(.title3.weight(.bold)).frame(width: 48, height: 48)
      }.buttonStyle(GameControlStyle()).accessibilityLabel("Pause")
    }
    .padding(12)
    .background(Palette.background.opacity(0.92), in: RoundedRectangle(cornerRadius: 20))
  }

  private var controls: some View {
    HoldFireButton(game: model.game).frame(width: 76, height: 76)
      .frame(maxWidth: .infinity, alignment: .trailing)
      .accessibilityIdentifier("game-controls")
  }

  private var menu: some View {
    ZStack {
      Palette.background.opacity(0.9).ignoresSafeArea()
      ScrollView {
        VStack(spacing: 20) {
          Text(
            model.game.status == .ready
              ? "THE OPEN WATER IS CALLING"
              : model.game.status == .paused ? "TAKE A BREATHER" : "VOYAGE COMPLETE"
          )
          .font(.caption.weight(.bold)).tracking(2).foregroundStyle(Palette.gold)
          Text(
            model.game.status == .ready
              ? "WAKE\nRUNNER" : model.game.status == .paused ? "PAUSED" : "WHAT A\nVOYAGE."
          )
          .font(.system(.largeTitle, design: .rounded, weight: .heavy))
          .multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
          if model.game.status == .over {
            Text(model.game.score.formatted()).font(
              .system(.largeTitle, design: .rounded, weight: .heavy)
            ).foregroundStyle(Palette.gold)
            Text("\(Int(model.game.distance)) m sailed · \(model.game.piratesSunk) pirates sunk")
              .font(.subheadline)
          } else if model.game.status == .ready {
            Text("Dodge rocks. Sink pirates.\nMake every voyage count.").multilineTextAlignment(
              .center)
          }
          Text("PERSONAL BEST  \(model.best.formatted())").font(
            .subheadline.monospacedDigit().weight(.semibold)
          ).foregroundStyle(Palette.gold)
          Button(action: model.startOrResume) {
            Label(
              model.game.status == .paused
                ? "Back to the water" : model.game.status == .over ? "One more voyage" : "Set sail",
              systemImage: "play.fill"
            )
            .font(.headline).frame(maxWidth: .infinity, minHeight: 56)
          }.buttonStyle(MenuButtonStyle())
          Group {
            if typeSize.isAccessibilitySize {
              VStack(spacing: 12) {
                soundOption
                helpOption
              }
            } else {
              HStack(spacing: 12) {
                soundOption
                helpOption
              }
            }
          }.buttonStyle(MenuButtonStyle(primary: false))
          if model.game.status == .ready {
            Text("Swipe to steer · hold Fire to shoot\nTap a pirate to aim · swipe up to jump")
              .font(.footnote).multilineTextAlignment(.center)
          }
        }.padding(24).frame(maxWidth: 440).frame(maxWidth: .infinity)
      }.defaultScrollAnchor(.center)
    }.accessibilityIdentifier("game-menu")
  }

  private var soundOption: some View {
    Button {
      model.toggleSound()
    } label: {
      Label(
        model.sound ? "Sound on" : "Sound off",
        systemImage: model.sound ? "speaker.wave.2" : "speaker.slash"
      )
      .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, minHeight: 48)
    }.accessibilityLabel(model.sound ? "Turn sound off" : "Turn sound on")
      .accessibilityValue(model.sound ? "On" : "Off")
  }
  private var helpOption: some View {
    Button {
      help = true
    } label: {
      Label("How to play", systemImage: "questionmark.circle")
        .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, minHeight: 48)
    }
  }

  private var handbook: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 20) {
          Text("Captain’s handbook").font(.largeTitle.bold())
          Text(
            "Swipe left or right across the water to steer. Swipe up to jump. VoiceOver actions on the waterway also let you steer and jump."
          )
          Text(
            "Hold Fire to shoot ahead. Tap a pirate to aim across lanes. Two hits sink it for 250 points."
          )
          Text(
            "Jump clears rocks, ships and cannonballs for 1.15 seconds. Each upward swipe uses one jump. Blue pickups add a jump, up to five."
          )
          Text(
            "Hearts repair 25 hull. Rocks cost 25 hull, cannonballs 15, and pirate collisions 30.")
          Text(
            "Every 300 meters the water gets faster. Pause is at the top right; sound and help are in the pause menu."
          )
        }.padding(24)
      }.background(Palette.background).foregroundStyle(Palette.cream)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { help = false } } }
    }
  }
}

private struct GameControlStyle: ButtonStyle {
  @Environment(\.isEnabled) private var enabled
  func makeBody(configuration: Configuration) -> some View {
    configuration.label.foregroundStyle(Palette.cream)
      .background(Palette.surface.opacity(0.96), in: Circle())
      .overlay(
        Circle().strokeBorder(
          Palette.cream.opacity(configuration.isPressed ? 0.85 : 0.3), lineWidth: 2)
      )
      .shadow(color: .black.opacity(0.2), radius: 5, y: 3)
      .opacity(enabled ? configuration.isPressed ? 0.7 : 1 : 0.4)
  }
}

private struct MenuButtonStyle: ButtonStyle {
  var primary = true
  func makeBody(configuration: Configuration) -> some View {
    configuration.label.padding(.horizontal, 12)
      .foregroundStyle(primary ? Palette.background : Palette.cream)
      .background(primary ? Palette.gold : Palette.surface, in: RoundedRectangle(cornerRadius: 16))
      .opacity(configuration.isPressed ? 0.7 : 1)
  }
}

// UIControl tracks a real press, so holding Fire can coexist with steering/jump.
private struct HoldFireButton: UIViewRepresentable {
  let game: Game
  @Environment(\.isEnabled) private var enabled
  func makeUIView(context: Context) -> CannonButton { CannonButton(game: game) }
  func updateUIView(_ view: CannonButton, context: Context) {
    view.isEnabled = enabled
    if !enabled { game.firing = false }
  }
}
private final class CannonButton: UIButton {
  let game: Game
  init(game: Game) {
    self.game = game
    super.init(frame: .zero)
    var config = UIButton.Configuration.filled()
    config.title = "FIRE"
    config.image = UIImage(systemName: "scope")
    config.imagePlacement = .top
    config.imagePadding = 3
    config.baseBackgroundColor = UIColor(hex: "F2C982")
    config.baseForegroundColor = UIColor(hex: "102C34")
    config.cornerStyle = .capsule
    config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { attributes in
      var attributes = attributes
      attributes.font = UIFont.systemFont(ofSize: 12, weight: .heavy)
      return attributes
    }
    configuration = config
    titleLabel?.font = UIFont.systemFont(ofSize: 12, weight: .heavy)
    titleLabel?.adjustsFontForContentSizeCategory = true
    accessibilityLabel = "Fire cannon"
    accessibilityHint = "Hold for continuous fire."
    addTarget(self, action: #selector(beginFire), for: .touchDown)
    addTarget(
      self, action: #selector(endFire),
      for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])
  }
  required init?(coder: NSCoder) { fatalError("Use init(game:)") }
  @objc private func beginFire() {
    UIImpactFeedbackGenerator(style: .light).impactOccurred()
    game.firing = true
    game.fire()
  }
  @objc private func endFire() { game.firing = false }
  override func accessibilityActivate() -> Bool {
    game.fire()
    return true
  }
}
