#!/usr/bin/env bash
# Android CI/CD: checks and Google Play releases. Kept separate from iOS.
# GitLab CI/CD script, called by .gitlab/android.yml.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
python=${CI_PYTHON:-python3}
project=mobile/android
package=com.boringman.thelastsail
out="$PWD/dist/android"

die() { echo "android: $*" >&2; exit 1; }

# A value holds base64 (GitHub secret) or a path to a file holding the raw contents.
decode_secret() {
    local name=$1 dest=$2 value=${!1}
    if [[ -f "$value" ]]; then
        cp -- "$value" "$dest"
    else
        printf '%s' "$value" | "$python" -c \
            'import base64, sys; sys.stdout.buffer.write(base64.b64decode(sys.stdin.read()))' \
            > "$dest" || die "$name is not valid base64"
    fi
    chmod 600 "$dest"
}

release() {
    : "${ANDROID_KEYSTORE_BASE64:?Set ANDROID_KEYSTORE_BASE64}"
    : "${ANDROID_KEYSTORE_PASSWORD:?Set ANDROID_KEYSTORE_PASSWORD}"
    : "${ANDROID_KEY_ALIAS:?Set ANDROID_KEY_ALIAS}"
    : "${ANDROID_KEY_PASSWORD:?Set ANDROID_KEY_PASSWORD}"

    # Version name: RELEASE_VERSION when given, else versionName in build.gradle.kts.
    local version=${RELEASE_VERSION:-}
    if [[ -n "$version" ]]; then
        [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "RELEASE_VERSION must be X.Y.Z (got '$version')"
        export ANDROID_VERSION_NAME=$version
    else
        version=$(sed -n 's/.*?: "\([0-9][0-9.]*\)".*/\1/p' "$project/app/build.gradle.kts" | head -1)
    fi
    # Minutes since 2026-01-01 UTC: always increases without stored state.
    export ANDROID_VERSION_CODE=${ANDROID_VERSION_CODE:-$(( ($(date +%s) - 1767225600) / 60 ))}

    # Global so the EXIT trap can still see it after this function returns.
    work=$(mktemp -d)
    trap 'rm -rf -- "$work"' EXIT
    decode_secret ANDROID_KEYSTORE_BASE64 "$work/upload.jks"
    unset ANDROID_KEYSTORE_BASE64
    export ANDROID_KEYSTORE_PATH="$work/upload.jks"

    # One Gradle run tests and packages, sharing one daemon and dependency resolution.
    (cd "$project" && ./gradlew --no-daemon :app:testDebugUnitTest :app:bundleRelease :app:assembleRelease)
    mkdir -p "$out"
    local name="the-last-sail-$version-$ANDROID_VERSION_CODE"
    cp "$project/app/build/outputs/bundle/release/app-release.aab" "$out/$name.aab"
    cp "$project/app/build/outputs/apk/release/app-release.apk" "$out/$name.apk"
    echo "Built $version ($ANDROID_VERSION_CODE) into $out"

    if [[ "${PLAY_PUBLISH_ENABLED:-}" != true ]]; then
        echo "PLAY_PUBLISH_ENABLED is not true; skipping the Google Play upload."
        return
    fi
    : "${PLAY_SERVICE_ACCOUNT_JSON:?Set PLAY_SERVICE_ACCOUNT_JSON}"
    if [[ -f "$PLAY_SERVICE_ACCOUNT_JSON" ]]; then
        cp -- "$PLAY_SERVICE_ACCOUNT_JSON" "$work/play.json"
    else
        printf '%s' "$PLAY_SERVICE_ACCOUNT_JSON" > "$work/play.json"
    fi
    chmod 600 "$work/play.json"
    "$python" -m venv "$work/venv"
    "$work/venv/bin/pip" install --quiet 'google-auth[requests]>=2.40,<3'
    "$work/venv/bin/python" .gitlab/scripts/play_upload.py \
        --credentials "$work/play.json" \
        --package "$package" \
        --bundle "$out/$name.aab" \
        --track "${PLAY_TRACK:-internal}" \
        --status "${PLAY_RELEASE_STATUS:-draft}" \
        --release-name "$version ($ANDROID_VERSION_CODE)"
}

case "${1:-}" in
    check)
        # Requires a JDK and the Android SDK.
        cd "$project"
        ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
        ;;
    release) release ;;
    *)
        echo 'Usage: bash .gitlab/scripts/android.sh {check|release}' >&2
        exit 2
        ;;
esac
