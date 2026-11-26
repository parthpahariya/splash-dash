# The Last Sail

A top-down, four-lane boat arcade game for **Android** (Kotlin) and **iPhone and iPad** (Swift).
Steer between lanes, jump over rocks, and sink pirate ships with your cannon.

The apps, build instructions and gameplay details are in [`mobile/`](mobile/README.md).
Release guides: [Android](docs/android-release.md) and [iOS](docs/ios-release.md).
Both apps must keep the same UI and features; see [`AGENTS.md`](AGENTS.md).

## Quick start

- Android: `cd mobile/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug` (JDK 17).
- iPhone: `make ios-device` installs and launches the app on a connected iPhone.
