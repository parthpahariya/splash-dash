#!/usr/bin/env bash
# iOS CI/CD: checks and TestFlight releases. Kept separate from Android.
# GitLab CI/CD script, called by .gitlab/ios.yml. Requires macOS with Xcode.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
project=mobile/ios/WakeRunner.xcodeproj
# Supplies DEVELOPMENT_TEAM (and a developer's optional Local.xcconfig overrides).
xcconfig=mobile/ios/Config/Base.xcconfig
out="$PWD/dist/ios"

die() { echo "ios: $*" >&2; exit 1; }

decode_secret() {
    printf '%s' "$1" | python3 -c \
        'import base64, sys; sys.stdout.buffer.write(base64.b64decode("".join(sys.stdin.read().split()), validate=True))' > "$2"
}

cleanup_release() {
    local status=$?
    trap - EXIT
    set +e
    if [[ "${keychain_search_changed:-}" == true ]]; then
        security list-keychains -d user -s "${original_keychains[@]}"
    fi
    [[ -z "${keychain:-}" ]] || security delete-keychain "$keychain" >/dev/null 2>&1
    if [[ -n "${installed_profile_name:-}" ]]; then
        local index=0 profile_dir
        for profile_dir in "$HOME/Library/MobileDevice/Provisioning Profiles" \
            "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"; do
            if [[ -f "$work/profile-installed-$index" ]]; then
                rm -f -- "$profile_dir/$installed_profile_name"
                if [[ -e "$work/profile-backup-$index" || -L "$work/profile-backup-$index" ]]; then
                    mv -- "$work/profile-backup-$index" "$profile_dir/$installed_profile_name"
                fi
            fi
            index=$((index + 1))
        done
    fi
    rm -rf -- "$work"
    exit "$status"
}

release() {
    : "${IOS_DIST_P12_BASE64:?Set IOS_DIST_P12_BASE64}"
    : "${IOS_DIST_P12_PASSWORD:?Set IOS_DIST_P12_PASSWORD}"
    : "${IOS_PROFILE_BASE64:?Set IOS_PROFILE_BASE64}"
    if [[ "${IOS_PUBLISH_ENABLED:-}" == true ]]; then
        : "${ASC_KEY_ID:?Set ASC_KEY_ID}"
        : "${ASC_KEY_BASE64:?Set ASC_KEY_BASE64}"
        [[ "$ASC_KEY_ID" =~ ^[A-Za-z0-9]+$ ]] || die "ASC_KEY_ID must be alphanumeric"
    fi

    # Version name: RELEASE_VERSION, else the `ios-vX.Y.Z` tag.
    local version=${RELEASE_VERSION:-}
    [[ -z "$version" && "${RELEASE_TAG:-}" == ios-v* ]] && version=${RELEASE_TAG#ios-v}
    [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
        || die "set RELEASE_VERSION or push an ios-vX.Y.Z tag (got '$version')"
    # Minutes since 2026-01-01 UTC: always increases, on any CI provider, without stored state.
    local build_number=$(( ($(date +%s) - 1767225600) / 60 ))

    # Global so the EXIT trap can still see it after this function returns.
    umask 077
    work=$(mktemp -d)
    trap cleanup_release EXIT
    xcodebuild -project "$project" -scheme WakeRunner -configuration Release \
        -xcconfig "$xcconfig" -destination 'generic/platform=iOS' -showBuildSettings \
        > "$work/build-settings.txt"
    local team bundle_id
    team=$(awk '$1 == "DEVELOPMENT_TEAM" && !found { print $3; found=1 }' "$work/build-settings.txt")
    bundle_id=$(awk '$1 == "PRODUCT_BUNDLE_IDENTIFIER" && !found { print $3; found=1 }' "$work/build-settings.txt")
    [[ -n "$team" ]] || die "set DEVELOPMENT_TEAM in $xcconfig"
    [[ -n "$bundle_id" ]] || die "PRODUCT_BUNDLE_IDENTIFIER is missing from the build settings"

    decode_secret "$IOS_DIST_P12_BASE64" "$work/distribution.p12" \
        || die "IOS_DIST_P12_BASE64 is not valid base64"
    decode_secret "$IOS_PROFILE_BASE64" "$work/app.mobileprovision" \
        || die "IOS_PROFILE_BASE64 is not valid base64"
    unset IOS_DIST_P12_BASE64 IOS_PROFILE_BASE64
    security cms -D -i "$work/app.mobileprovision" > "$work/profile.plist"
    local profile_uuid profile_name
    profile_uuid=$(python3 - "$work" "$team" "$bundle_id" <<'PY'
import datetime
import pathlib
import plistlib
import sys
import uuid

work = pathlib.Path(sys.argv[1])
team, bundle_id = sys.argv[2:]
with (work / "profile.plist").open("rb") as source:
    profile = plistlib.load(source)
entitlements = profile.get("Entitlements", {})
if (team not in profile.get("TeamIdentifier", [])
        or not entitlements.get("application-identifier", "").endswith("." + bundle_id)):
    raise SystemExit("Provisioning profile must match DEVELOPMENT_TEAM and the app bundle ID")
if (entitlements.get("get-task-allow", False) or "ProvisionedDevices" in profile
        or profile.get("ProvisionsAllDevices", False)):
    raise SystemExit("Use an App Store distribution provisioning profile")
if profile["ExpirationDate"] <= datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None):
    raise SystemExit("Provisioning profile has expired; renew it before releasing")
profile_uuid = profile["UUID"]
uuid.UUID(profile_uuid)
with open("mobile/ios/Config/ExportOptions.plist", "rb") as source:
    options = plistlib.load(source)
options.update(teamID=team, provisioningProfiles={bundle_id: profile["Name"]})
with (work / "export.plist").open("wb") as target:
    plistlib.dump(options, target)
print(profile_uuid)
PY
    )
    profile_name=$(python3 -c 'import plistlib, sys; print(plistlib.load(open(sys.argv[1], "rb"))["Name"])' "$work/profile.plist")

    # Restore the runner's search list and delete our keychain even after a failed build.
    security list-keychains -d user > "$work/keychains.txt"
    original_keychains=()
    while IFS= read -r path; do
        original_keychains+=("$path")
    done < <(python3 -c 'import shlex, sys; print("\n".join(shlex.split(sys.stdin.read())))' < "$work/keychains.txt")
    keychain="$work/signing.keychain-db"
    local keychain_password
    keychain_password=$(openssl rand -hex 24)
    security create-keychain -p "$keychain_password" "$keychain"
    security set-keychain-settings -lut 21600 "$keychain"
    security unlock-keychain -p "$keychain_password" "$keychain"
    security import "$work/distribution.p12" -P "$IOS_DIST_P12_PASSWORD" -k "$keychain" \
        -T /usr/bin/codesign -T /usr/bin/security >/dev/null
    unset IOS_DIST_P12_PASSWORD
    rm -f -- "$work/distribution.p12"
    security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$keychain_password" "$keychain" >/dev/null
    keychain_search_changed=true
    security list-keychains -d user -s "$keychain" "${original_keychains[@]}"

    # Xcode discovers profiles by their UUID filename. Restore existing copies on exit.
    installed_profile_name="$profile_uuid.mobileprovision"
    local index=0 profile_dir
    for profile_dir in "$HOME/Library/MobileDevice/Provisioning Profiles" \
        "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"; do
        mkdir -p "$profile_dir"
        if [[ -e "$profile_dir/$installed_profile_name" || -L "$profile_dir/$installed_profile_name" ]]; then
            cp -pP "$profile_dir/$installed_profile_name" "$work/profile-backup-$index"
        fi
        touch "$work/profile-installed-$index"
        rm -f -- "$profile_dir/$installed_profile_name"
        cp "$work/app.mobileprovision" "$profile_dir/$installed_profile_name"
        index=$((index + 1))
    done

    xcodebuild -version
    xcodebuild archive -project "$project" -scheme WakeRunner -configuration Release \
        -xcconfig "$xcconfig" -destination 'generic/platform=iOS' -archivePath "$work/WakeRunner.xcarchive" \
        CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$team" CODE_SIGN_IDENTITY='Apple Distribution' \
        PROVISIONING_PROFILE_SPECIFIER="$profile_name" OTHER_CODE_SIGN_FLAGS="--keychain $keychain" \
        MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build_number"

    # Keep a signed .ipa as a CI artifact, then upload when publishing is on.
    xcodebuild -exportArchive -archivePath "$work/WakeRunner.xcarchive" \
        -exportOptionsPlist "$work/export.plist" -exportPath "$work/export"
    mkdir -p "$out"
    local ipa="$out/the-last-sail-$version-$build_number.ipa"
    cp "$work/export/WakeRunner.ipa" "$ipa"

    if [[ "${IOS_PUBLISH_ENABLED:-}" != true ]]; then
        echo "IOS_PUBLISH_ENABLED is not true; skipping the App Store Connect upload."
        return
    fi
    decode_secret "$ASC_KEY_BASE64" "$work/AuthKey_$ASC_KEY_ID.p8" \
        || die "ASC_KEY_BASE64 is not valid base64"
    unset ASC_KEY_BASE64
    # Individual keys use subject=user. altool still requires its issuer option,
    # so use the key ID as a placeholder (as fastlane does).
    API_PRIVATE_KEYS_DIR="$work" xcrun altool --upload-app -f "$ipa" -t ios \
        --api-key "$ASC_KEY_ID" --api-issuer "$ASC_KEY_ID" --api-key-subject user
    echo "Uploaded $version ($build_number) to App Store Connect; it appears in TestFlight after processing."
}

case "${1:-}" in
    check)
        # Gameplay engine tests run on macOS through Swift Package Manager; no simulator needed.
        swift test --package-path mobile/ios
        # Compile the full app for the simulator SDK without booting a simulator or signing.
        xcodebuild build -project "$project" -scheme WakeRunner -configuration Debug \
            -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
        ;;
    release) release ;;
    *)
        echo 'Usage: bash .gitlab/scripts/ios.sh {check|release}' >&2
        exit 2
        ;;
esac
