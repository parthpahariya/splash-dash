# Wake Runner for Android and iOS

Two independent native apps port the browser game's four-lane simulation:

| Platform | Language and UI | Minimum version | Project |
| --- | --- | --- | --- |
| Android | Kotlin, Android Views and Canvas | Android 8.0 / API 26 | `android/` |
| iPhone and iPad | Swift, SwiftUI and Core Graphics | iOS / iPadOS 17 | `ios/WakeRunner.xcodeproj` |

Both apps run entirely offline. They include swipe steering and upward-swipe jumps, held cannon fire, tap-to-target pirate shots, heart pickups, increasing difficulty, pause/resume, optional sound (off initially), and a best score stored on the device. The app pauses when backgrounded and waits for an explicit resume. Portrait and landscape are supported; screen-reader custom actions provide steering and jumping without swipes.

The game uses a full-screen ocean with a compact, toy-like (claymorphism) HUD row: a Pause button at the top left, a hull card in the middle showing health in quarter segments plus the remaining jump charges, and a score card with the level at the top right. Fire is the only visible gameplay action button, at the bottom right. Swipe left or right to steer and swipe up to jump. Each voyage starts with two jumps; collectible jump boosts are not available. Gestures lock to one axis; an upward swipe uses at most one jump charge and never fires at a pirate on release. VoiceOver and TalkBack actions on the waterway provide steering and jumping without visible buttons. Gameplay positions respect Fire, the camera housing, and the home indicator. Ocean ripples, shores, and lane guides render across the entire screen beneath the HUD and Fire, with no rectangular cutoff at the gameplay insets. Sound, help, and personal bests live in the start/pause/results menu; event messages appear briefly during play. Native press and gesture feedback includes haptics, subject to device settings. iOS caps HUD text at XXXL to keep gameplay usable, while menus and help follow the full Dynamic Type range.

## Android

Open `android/` in Android Studio. Install Android SDK Platform 35 and Build Tools 35.0.0, select **JDK 17** as the Gradle JDK, then run the `app` configuration on an emulator or connected device. The checked-in Gradle wrapper supplies Gradle 8.11.1.

From a terminal with JDK 17 on `JAVA_HOME` and the SDK on `ANDROID_HOME` (or `sdk.dir` in `android/local.properties`):

```sh
cd mobile/android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

With the app installed and the emulator/device unlocked, run `python3 mobile/tools/android-smoke.py` from the repository root for an Android interaction smoke test. Use a dedicated test device; it starts a fresh run, temporarily changes orientation, and restores the original orientation settings afterward.

The debug APK can be installed directly on an Android phone after allowing installs from the source used to open it. It is signed with the local debug key. For store distribution, create your own release signing configuration in Android Studio and use **Generate Signed App Bundle / APK**. No signing keys are committed.

## iOS

Open `ios/WakeRunner.xcodeproj` in Xcode 15 or later. Choose the **WakeRunner** scheme and an iPhone or iPad simulator, then press Run. There are no third-party packages to install.

To run on a physical device, select your Apple development team under **Signing & Capabilities** for WakeRunner, connect the device, and press Run. Change the bundle ID if your team needs a unique identifier. A simulator build is not an installable iPhone IPA; device installs and TestFlight/App Store distribution require Apple signing and provisioning.

Alternatively, deploy to a physical iPhone from the repository root using the same Makefile workflow as `meal-manager` (requires Xcode 15 or later):

```sh
cp mobile/ios/Config/Local.xcconfig.example mobile/ios/Config/Local.xcconfig
# Set DEVELOPMENT_TEAM in Local.xcconfig to your Apple Developer team ID.
make ios-devices
make ios-device
```

Sign in to your Apple account in Xcode, connect and unlock the iPhone, and enable Developer Mode on the phone. `make ios-device` selects the first available paired physical iPhone, builds a signed debug app, installs it, and relaunches it. It does not start a simulator. Your signing config stays local and is ignored by Git.

Use `make ios-device IOS_DEVICE=<UDID>` to select a particular phone. You can also override `IOS_BUNDLE_ID` (if your team needs a unique app ID), `IOS_DERIVED_DATA`, or `IOS_CONFIG`. For example:

```sh
make ios-device IOS_DEVICE=<UDID> IOS_BUNDLE_ID=com.example.wakerunner
```

Build for a simulator without a signing account:

```sh
xcodebuild -project mobile/ios/WakeRunner.xcodeproj \
  -scheme WakeRunner -configuration Debug -sdk iphonesimulator \
  -derivedDataPath mobile/ios/build CODE_SIGNING_ALLOWED=NO build
```

Run the pure Swift gameplay tests on macOS:

```sh
swift test --package-path mobile/ios
```

Run the iOS interaction tests (replace the destination with an installed simulator from `xcrun simctl list devices available`):

```sh
xcodebuild -project mobile/ios/WakeRunner.xcodeproj \
  -scheme WakeRunner -destination 'platform=iOS Simulator,name=iPhone 18 Pro' \
  -derivedDataPath mobile/ios/build CODE_SIGNING_ALLOWED=NO test
```

For distribution, configure your signing team, choose a device destination, and use **Product → Archive**. The artwork is native vector drawing; the app icon is included and can be regenerated from the repository root with `swift mobile/tools/generate-ios-icon.swift`.

## Gameplay and architecture

`android/app/src/main/java/com/boringman/splashdash/Game.kt` and `ios/WakeRunner/Game.swift` contain platform-independent simulation code. Both use the browser engine's 800-unit coordinate system, collision thresholds, timings, damage, scoring, spawn spacing, and speed curve. Random spawning can differ between platforms. Platform renderers supply artwork, touch input, and native UI; Android uses Choreographer and iOS uses CADisplayLink for frame timing. Cosmetic effects and audio differ from the browser version.

Each native engine has 13 tests covering steering, damage grace and jump protection, heart healing without jump replenishment, automatic heart spawning, two-hit sinking, targeted fire, aimed enemy shots, airborne cannonball avoidance, pausing, lethal-frame ordering, levels/reset, spawn spacing, and a seeded ten-minute run checking object separation. iOS also has interaction tests for starting, steering, jumping, held fire, pausing, backgrounding, help, and landscape controls. These do not replace playtesting on physical devices or establish that the real-time visual game is fully playable with a screen reader.

## CI/CD

GitHub Actions (`.github/workflows/`) and GitLab CI (`.gitlab/`) run separate Android and iOS
pipelines. Pull requests run checks only when their platform's files change. Pushes to
`codex/splash-dash-2026-11-25` that touch Android build a signed Google Play release; `ios-vX.Y.Z` tags build a signed
TestFlight release. Store uploads stay off until `PLAY_PUBLISH_ENABLED` / `IOS_PUBLISH_ENABLED`
are `true`. Setup: [Android releases](../docs/android-release.md), [iOS releases](../docs/ios-release.md).
