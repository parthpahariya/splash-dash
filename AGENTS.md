# Agent instructions

## Android and iOS must stay identical

Wake Runner ships as two native apps: Android (`mobile/android`, Kotlin, Views and Canvas) and
iOS (`mobile/ios`, Swift, SwiftUI and Core Graphics). They must always have **the same final UI
and the same features**. A player switching phones should not notice a difference.

Every change to one app must be made to the other in the same change, including:

- **Gameplay rules:** `Game.kt` ↔ `Game.swift`. Keep constants, timers, spawning, damage, scoring,
  jumps and pirate behaviour identical.
- **Game artwork:** `GameView.kt` ↔ `GameSurface.swift`. Keep the same shapes, colours, layering
  and animation, in the same 800-unit world coordinates.
- **Screens and controls:** `MainActivity.kt` ↔ `WakeRunnerApp.swift`. This covers the HUD
  (pause, hull card, score card, toasts, Fire button), the start, pause and game-over menus, and
  the handbook. Keep the same layout, wording, icons and states.
- **Theme:** `Clay.kt` ↔ `Theme`, `Clay` and `ClayButtonStyle` in `WakeRunnerApp.swift`. Keep the
  same colour tokens, border, shadow depth and corner radii.
- **Icons:** `ControlIcon.kt` draws stand-ins for the SF Symbols that iOS uses. If an iOS
  symbol is added or changed, add or update the matching Android icon.
- **Copy:** toasts, menu text, the handbook rules and accessibility labels must match. The only
  platform wording that differs is "VoiceOver" on iOS and "screen reader" on Android.
- **Tests:** `GameTest.kt` ↔ `GameTests.swift`. A rule test added to one platform gets the
  equivalent test on the other.

Platform differences are allowed only when a platform cannot do something, such as a system font
or a native haptic API. Keep these as close as the platform allows, and write a code comment
explaining the difference.

Before you finish a change, compare both apps side by side. Take screenshots of the start menu,
gameplay, pause, game over and handbook on each platform, and check that they match.

## Building and checking

- Android: `cd mobile/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
  It needs JDK 17 (CI uses Temurin 17) and `ANDROID_HOME` set to the Android SDK.
- iOS: after iOS changes, install on the physical iPhone with `make ios-device`.
- Commit or push only when you are asked to.
