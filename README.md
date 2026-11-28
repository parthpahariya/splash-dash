# The Last Sail

A top-down, four-lane boat arcade game for **Android** (Kotlin) and **iPhone and iPad** (Swift).
Steer between lanes, jump over rocks, and sink pirate ships with your cannon.

The apps, build instructions and gameplay details are in [`mobile/`](mobile/README.md).
Release guides: [Android](docs/android-release.md) and [iOS](docs/ios-release.md).
Both apps must keep the same UI and features; see [`AGENTS.md`](AGENTS.md).

The website at [thelastsail.boringman.in](https://thelastsail.boringman.in) (home page and
[privacy policy](https://thelastsail.boringman.in/privacy/)) is in [`web/`](web) and runs in Docker
behind nginx; see the [website deployment guide](docs/web-deployment.md).

## Quick start

- Android: `cd mobile/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug` (JDK 17).
- iPhone: `make ios-device` installs and launches the app on a connected iPhone.
- Website: `make web` starts Docker if needed, serves the site at http://127.0.0.1:8080/ and opens it; `make web-down` stops it.
