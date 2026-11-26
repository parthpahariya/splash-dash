# iOS releases (TestFlight)

iOS CI/CD is separate from Android. On GitHub, [.github/workflows/ios.yml](../.github/workflows/ios.yml)
runs the checks and releases with self-contained steps. On GitLab, [.gitlab/ios.yml](../.gitlab/ios.yml)
runs [.gitlab/scripts/ios.sh](../.gitlab/scripts/ios.sh). Both need macOS with Xcode.

| Trigger | Result |
| --- | --- |
| Pull request or `codex/splash-dash-2026-11-25` push touching iOS files | Swift gameplay tests and a simulator-SDK build of the app |
| `ios-vX.Y.Z` tag, or **Actions → iOS → Run workflow** on `codex/splash-dash-2026-11-25` | Checks, then an archived, signed `.ipa` in `dist/ios/` (kept as a CI artifact). Uploaded to App Store Connect when `IOS_PUBLISH_ENABLED` is `true` |

```sh
git tag ios-v1.0.0 && git push origin ios-v1.0.0
```

The version name comes from the tag (or the manual run's input). The build number is the number of
minutes since 2026-01-01 UTC, so it increases on every build.

CI signs with an Apple Distribution certificate and an App Store provisioning profile.
The individual App Store Connect API key is used only for uploads and needs no issuer setting.

## One-time setup

1. Confirm `DEVELOPMENT_TEAM` in [mobile/ios/Config/Base.xcconfig](../mobile/ios/Config/Base.xcconfig)
   matches your signing files. The committed team is `4VJ3Z5FXLP`. Team IDs are not secret.
2. In App Store Connect, create the app with bundle ID `com.boringman.thelastsail`.
3. Export the Apple Distribution certificate **with its private key** as a password-protected
   `.p12` from Keychain Access. Download an App Store distribution provisioning profile for
   `com.boringman.thelastsail`, using the same certificate and team. Renew the profile and
   replace the secret before it expires.
4. In App Store Connect, click your username → **Edit Profile → Individual API Key** and
   generate/download the `.p8` (offered only once). The user needs permission to upload builds,
   such as the App Manager role. Skip this step when only building signed artifacts.
5. Add the CI settings below. Add `IOS_PUBLISH_ENABLED` last, because it turns uploads on.

| Name | Kind | Value |
| --- | --- | --- |
| `IOS_DIST_P12_BASE64` | Secret | `base64 -i TheLastSail_Distribution.p12 \| tr -d '\n'` |
| `IOS_DIST_P12_PASSWORD` | Secret | Password used when exporting the `.p12` |
| `IOS_PROFILE_BASE64` | Secret | `base64 -i TheLastSail_AppStore.mobileprovision \| tr -d '\n'` |
| `ASC_KEY_ID` | Secret | Individual API key ID; required only for uploads |
| `ASC_KEY_BASE64` | Secret | `base64 -i ApiKey_XXXXXXXXXX.p8 \| tr -d '\n'`; required only for uploads |
| `IOS_PUBLISH_ENABLED` | Variable | `true` |

**GitHub:** create the `app-store` environment (**Settings → Environments**), limit it to `ios-v*` tags and the `codex/splash-dash-2026-11-25` branch (for manual runs), and add
the secrets there. Add the variable to the environment or the repository.

**GitLab:** add them as protected variables scoped to `app-store` (mask the secrets), protect the
`ios-v*` tag pattern, and register a macOS runner with the `macos` tag.

The release imports the certificate into a temporary keychain and installs the profile for the
build. It checks the profile's team, bundle ID and expiry, then archives and exports using manual
signing. It restores the runner's keychain search list and removes its keychain, installed
profiles and temporary files on success or failure.

After processing, the build appears in TestFlight. Submit it for review from App Store Connect.
