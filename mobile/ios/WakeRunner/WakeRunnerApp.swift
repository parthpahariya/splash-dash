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
  @Published var notice = "Swipe to steer · tap a pirate to aim"
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
      notice = "Swipe to steer · tap a pirate to aim"
      noticeUntil = 0
    }
    if game.status == .playing { frame &+= 1 }
  }
  func startOrResume() {
    if game.status == .paused { game.pause() } else { game.reset() }
    notice = "Swipe to steer · tap a pirate to aim"
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
    noticeUntil = CACurrentMediaTime() + 1.6
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
  static let surface = Color(uiColor: UIColor(hex: "284A52"))
  static let cream = Color(uiColor: UIColor(hex: "F8EED6"))
  static let gold = Color(uiColor: UIColor(hex: "F2C982"))
}

struct GameScreen: View {
  @ObservedObject var model: GameModel
  @State private var help = false
  @Environment(\.dynamicTypeSize) private var typeSize
  @ScaledMetric(relativeTo: .body) private var fireHeight = 52.0
  var body: some View {
    GeometryReader { geometry in
      let landscape = geometry.size.width > geometry.size.height
      if typeSize.isAccessibilitySize {
        ScrollView {
          VStack(spacing: 16) {
            header
            stats
            water.frame(height: max(360, geometry.size.height * 0.7))
            controls
          }.padding(16)
        }
      } else {
        VStack(spacing: 8) {
          header
          if landscape {
            HStack(spacing: 16) {
              water
              ScrollView {
                VStack(spacing: 12) {
                  stats
                  controls
                }
              }.frame(width: min(260, geometry.size.width * 0.38))
            }
          } else {
            stats
            water
            ScrollView { controls }
              .frame(
                height: min(typeSize.isAccessibilitySize ? 280 : 200, geometry.size.height * 0.34))
          }
        }
        .padding(16)
        .frame(maxWidth: 1100)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
      }
    }
    .background(Palette.background)
    .foregroundStyle(Palette.cream)
    .sheet(isPresented: $help) { handbook }
  }
  private var header: some View {
    Group {
      if typeSize.isAccessibilitySize {
        VStack(alignment: .leading, spacing: 8) {
          Text("WAKE RUNNER").font(.headline).accessibilityAddTraits(.isHeader)
            .fixedSize(horizontal: false, vertical: true)
          headerActions
        }.frame(maxWidth: .infinity, alignment: .leading)
      } else {
        HStack(spacing: 8) {
          Text("WAKE RUNNER").font(.headline).tracking(1).accessibilityAddTraits(.isHeader)
            .frame(maxWidth: .infinity, alignment: .leading)
          headerActions
        }
      }
    }.buttonStyle(.plain)
  }
  private var headerActions: some View {
    HStack(spacing: 8) {
      Button {
        model.toggleSound()
      } label: {
        Image(systemName: model.sound ? "speaker.wave.2" : "speaker.slash").frame(
          minWidth: 44, minHeight: 44)
      }.accessibilityLabel(model.sound ? "Turn sound off" : "Turn sound on")
        .accessibilityValue(model.sound ? "On" : "Off")
      Button {
        if model.game.status == .playing { model.pause() }
        help = true
      } label: {
        Image(systemName: "questionmark.circle").frame(minWidth: 44, minHeight: 44)
      }
      .accessibilityLabel("How to play")
    }
  }
  private var stats: some View {
    VStack(alignment: .leading, spacing: 4) {
      if typeSize.isAccessibilitySize {
        Text("SCORE \(model.game.score.formatted())").fontWeight(.bold)
        Text("BEST \(model.best.formatted())")
        Text("Hull \(model.game.health)%").foregroundStyle(Palette.gold)
        Text("Jumps \(model.game.jumps) · Level \(model.game.level)").foregroundStyle(Palette.gold)
      } else {
        HStack {
          Text("SCORE \(model.game.score.formatted())").fontWeight(.bold)
          Spacer(minLength: 8)
          Text("BEST \(model.best.formatted())")
        }
        Text(
          "Hull \(model.game.health)%   ·   Jumps \(model.game.jumps)   ·   Level \(model.game.level)"
        )
        .foregroundStyle(Palette.gold)
      }
    }
    .font(.subheadline.monospacedDigit())
    .fixedSize(horizontal: false, vertical: true)
    .frame(maxWidth: .infinity, alignment: .leading)
    .accessibilityElement(children: .combine)
  }
  private var water: some View {
    ZStack {
      GameSurface(game: model.game, frame: model.frame)
        .accessibilityHidden(model.game.status != .playing)
      if model.game.status != .playing {
        Palette.background.opacity(0.9)
        ScrollView {
          VStack(spacing: 16) {
            Text(overlayTitle).font(.largeTitle.bold()).multilineTextAlignment(.center)
              .accessibilityAddTraits(.isHeader)
            Text(overlayCopy).font(.body).multilineTextAlignment(.center)
            Button(action: model.startOrResume) {
              Text(
                model.game.status == .paused
                  ? "Back to the water"
                  : model.game.status == .over ? "One more voyage" : "Set sail"
              )
              .fontWeight(.bold).frame(maxWidth: .infinity, minHeight: 52)
            }.buttonStyle(SeaButton(primary: true))
          }.padding(24).frame(maxWidth: 420).frame(maxWidth: .infinity)
        }.defaultScrollAnchor(.center)
      }
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .clipShape(RoundedRectangle(cornerRadius: 20))
  }
  private var overlayTitle: String {
    switch model.game.status {
    case .ready: return "A little boat.\nA big adventure."
    case .paused: return "Take a breather."
    case .over: return "What a voyage."
    default: return ""
    }
  }
  private var overlayCopy: String {
    switch model.game.status {
    case .ready:
      return
        "Dodge rocks. Sink pirates.\nCollect hearts and jump over danger.\n\nStart with 100 hull and two jumps."
    case .paused: return "Your voyage is right where you left it."
    case .over:
      return
        "Score \(model.game.score.formatted())\n\(Int(model.game.distance)) m sailed · \(model.game.piratesSunk) pirates sunk\nBest \(model.best.formatted())"
    default: return ""
    }
  }
  private var controls: some View {
    VStack(spacing: 8) {
      Text(model.notice).font(.footnote).multilineTextAlignment(.center).foregroundStyle(
        Palette.gold)
      HStack(spacing: 8) {
        control("Left", icon: "arrow.left") { model.game.move(-1) }.accessibilityLabel("Steer left")
        control("Right", icon: "arrow.right") { model.game.move(1) }.accessibilityLabel(
          "Steer right")
      }.disabled(model.game.status != .playing)
      HStack(spacing: 8) {
        HoldFireButton(game: model.game).frame(maxWidth: .infinity).frame(height: fireHeight)
          .disabled(model.game.status != .playing).opacity(model.game.status == .playing ? 1 : 0.45)
        control("Jump (\(model.game.jumps))", icon: "arrow.up") { model.game.jump() }
          .disabled(model.game.status != .playing || model.game.jumps == 0 || model.game.jumping)
      }
      Button(action: model.pause) {
        Label(
          model.game.status == .paused ? "Resume" : "Pause",
          systemImage: model.game.status == .paused ? "play" : "pause"
        )
        .frame(maxWidth: .infinity, minHeight: 44)
      }.buttonStyle(SeaButton()).disabled(model.game.status == .ready || model.game.status == .over)
    }
  }
  private func control(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      Label(title, systemImage: icon).frame(maxWidth: .infinity, minHeight: 48)
    }.buttonStyle(SeaButton())
  }
  private var handbook: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 20) {
          Text("Captain’s handbook").font(.largeTitle.bold())
          Text("Use Left and Right or swipe across the water to steer.")
          Text(
            "Hold Fire to shoot ahead. Tap a pirate to aim across lanes. Two hits sink it for 250 points."
          )
          Text(
            "Jump clears rocks, ships and cannonballs for 1.15 seconds. Blue pickups add a jump, up to five."
          )
          Text(
            "Hearts repair 25 hull. Rocks cost 25 hull, cannonballs 15, and pirate collisions 30.")
          Text(
            "Every 300 meters the water gets faster. Sound starts off. Your best score is saved on this device."
          )
        }.padding(24)
      }.background(Palette.background).foregroundStyle(Palette.cream)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { help = false } } }
    }
  }
}

private struct SeaButton: ButtonStyle {
  var primary = false
  @Environment(\.isEnabled) private var enabled
  func makeBody(configuration: Configuration) -> some View {
    configuration.label.padding(.horizontal, 12)
      .foregroundStyle(primary ? Palette.background : Palette.cream)
      .background(primary ? Palette.gold : Palette.surface, in: RoundedRectangle(cornerRadius: 12))
      .opacity(enabled ? configuration.isPressed ? 0.7 : 1 : 0.45)
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
    config.title = "Fire"
    config.baseBackgroundColor = UIColor(hex: "F2C982")
    config.baseForegroundColor = UIColor(hex: "102C34")
    config.cornerStyle = .medium
    configuration = config
    titleLabel?.font = UIFont.preferredFont(forTextStyle: .headline)
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
    game.firing = true
    game.fire()
  }
  @objc private func endFire() { game.firing = false }
  override func accessibilityActivate() -> Bool {
    game.fire()
    return true
  }
}
