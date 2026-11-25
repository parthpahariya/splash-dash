# Mobile CI/CD

GitHub Actions and GitLab CI call the same scripts in this folder. Their YAML files only configure
runners, caches, triggers, artifacts and release concurrency. Android and iOS are independent
pipelines.

```text
.github/workflows/android.yml   # GitHub: Android checks and Google Play releases
.github/workflows/ios.yml       # GitHub: iOS checks and TestFlight releases
.gitlab/.gitlab-ci.yml          # GitLab entry point; includes the two files below
.gitlab/android.yml             # GitLab: Android checks and Google Play releases
.gitlab/ios.yml                 # GitLab: iOS checks and TestFlight releases (macOS runner)
.github/scripts/android.sh      # Android check/release commands
.github/scripts/ios.sh          # iOS check/release commands
.github/scripts/play_upload.py  # Google Play Developer API upload
```

GitHub discovers workflows in `.github/workflows/`. On GitLab, set **Settings → CI/CD → General
pipelines → CI/CD configuration file** to `.gitlab/.gitlab-ci.yml`.

| Command | Prerequisites and behavior |
| --- | --- |
| `bash .github/scripts/android.sh check` | JDK 17 and the Android SDK; runs the Kotlin unit tests and lint, builds a debug APK |
| `bash .github/scripts/android.sh release` | Upload keystore secrets; builds a signed `.aab`/`.apk` into `dist/android/` and uploads to Google Play when `PLAY_PUBLISH_ENABLED=true`. See [Android releases](../../docs/android-release.md) |
| `bash .github/scripts/ios.sh check` | macOS with Xcode; runs the Swift gameplay tests and compiles the app for the simulator SDK (no simulator is booted) |
| `bash .github/scripts/ios.sh release` | macOS, Xcode, distribution certificate and provisioning profile; archives a signed `.ipa` into `dist/ios/` and uploads it with an individual App Store Connect key when `IOS_PUBLISH_ENABLED=true`. See [iOS releases](../../docs/ios-release.md) |

Run them from any directory in the checkout. Build numbers are minutes since 2026-01-01 UTC, so
they always increase on either CI provider without stored state.
