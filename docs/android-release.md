# Android releases (Google Play)

Android CI/CD is separate from iOS. [.github/scripts/android.sh](../.github/scripts/android.sh) runs the checks and builds
releases, called by [.github/workflows/android.yml](../.github/workflows/android.yml) and
[.gitlab/android.yml](../.gitlab/android.yml).

| Trigger | Result |
| --- | --- |
| Pull request touching Android files | Kotlin unit tests, lint, debug APK |
| Push to the release branch `codex/splash-dash-2026-11-25` that touches Android files, or **Actions → Android → Run workflow** on that branch | Unit tests, then a signed `.aab` and `.apk` in `dist/android/` (kept as a CI artifact). Uploaded to Google Play when `PLAY_PUBLISH_ENABLED` is `true` |

The version name is `versionName` in `mobile/android/app/build.gradle.kts`, unless a manual run
supplies one. Bump it there for a new user-visible version. The version code is the number of
minutes since 2026-01-01 UTC, so every build gets a higher one.

## One-time setup

1. Create the app `com.boringman.splashdash` in Play Console. The Play API cannot create apps,
   so upload the first `.aab` by hand. A release build from this pipeline works.
2. Create an upload key and enrol in Play App Signing:
   `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`.
   Keep the keystore outside the repository and back it up.
3. In Google Cloud, enable the **Google Play Android Developer API**, create a service account and a
   JSON key. In Play Console → **Users and permissions**, invite the service account and grant it
   release permissions for this app.
4. Add the CI settings below. Add `PLAY_PUBLISH_ENABLED` last, because it turns uploads on.

| Name | Kind | Value |
| --- | --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Secret | `base64 -i upload.jks \| tr -d '\n'` |
| `ANDROID_KEYSTORE_PASSWORD` | Secret | Keystore password |
| `ANDROID_KEY_ALIAS` | Secret | `upload` |
| `ANDROID_KEY_PASSWORD` | Secret | Key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Secret | Full contents of the JSON key |
| `PLAY_PUBLISH_ENABLED` | Variable | `true` |
| `PLAY_RELEASE_STATUS` | Variable | Optional. Leave unset (`draft`) until the first release is published, then `completed` |
| `PLAY_TRACK` | Variable | Optional: `internal` (default), `alpha`, `beta`, `production` |

**GitHub:** create the `play` environment (**Settings → Environments**), limit its deployment branches to
`codex/splash-dash-2026-11-25`, and add the secrets there. Add the variables to the environment or the repository.

**GitLab:** protect the `codex/splash-dash-2026-11-25` branch and add the settings as protected variables (mask the
secrets). The release job runs only on that protected branch, for pushes that change Android files.

## Running locally

```sh
RELEASE_VERSION=1.0.0 \
ANDROID_KEYSTORE_BASE64="$(base64 -i upload.jks)" \
ANDROID_KEYSTORE_PASSWORD=… ANDROID_KEY_ALIAS=upload ANDROID_KEY_PASSWORD=… \
  bash .github/scripts/android.sh release
```

Without `PLAY_PUBLISH_ENABLED=true` nothing is uploaded. `./gradlew :app:bundleRelease` without
these variables still builds an unsigned bundle.
