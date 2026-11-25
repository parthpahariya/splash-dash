# Mobile CI/CD

GitHub Actions call these scripts; the workflow YAML only configures runners, caches, triggers,
artifacts and release concurrency. Android and iOS are independent pipelines.

```text
.github/workflows/android.yml  # Android checks and Google Play releases
.github/workflows/ios.yml      # iOS checks and TestFlight releases
ci/android.sh                  # Android check/release commands
ci/ios.sh                      # iOS check/release commands
ci/play_upload.py              # Google Play Developer API upload
```

| Command | Prerequisites and behavior |
| --- | --- |
| `bash ci/android.sh check` | JDK 17 and the Android SDK; runs the Kotlin unit tests and lint, builds a debug APK |
| `bash ci/android.sh release` | Upload keystore secrets; builds a signed `.aab`/`.apk` into `dist/android/` and uploads to Google Play when `PLAY_PUBLISH_ENABLED=true`. See [Android releases](../docs/android-release.md) |
| `bash ci/ios.sh check` | macOS with Xcode; runs the Swift gameplay tests and compiles the app for the simulator SDK (no simulator is booted) |
| `bash ci/ios.sh release` | macOS, Xcode, distribution certificate and provisioning profile; archives a signed `.ipa` into `dist/ios/` and uploads it with an individual App Store Connect key when `IOS_PUBLISH_ENABLED=true`. See [iOS releases](../docs/ios-release.md) |

Run them from any directory in the checkout. Build numbers are minutes since 2026-01-01 UTC, so
they always increase without stored state.
