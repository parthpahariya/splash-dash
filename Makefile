.DEFAULT_GOAL := help

IOS_DIR = mobile/ios
IOS_BUNDLE_ID ?= com.parthpahariya.splashdash
IOS_DERIVED_DATA ?= $(IOS_DIR)/build/DerivedData
IOS_CONFIG ?= $(IOS_DIR)/Config/Local.xcconfig
IOS_APP = $(IOS_DERIVED_DATA)/Build/Products/Debug-iphoneos/WakeRunner.app
# Use the first available paired physical iPhone. Override with IOS_DEVICE=<UDID>.
ifeq ($(origin IOS_DEVICE), undefined)
IOS_DEVICE := $(shell xcrun devicectl list devices 2>/dev/null | awk '/physical/ && /(^|[[:space:]])available([[:space:]]|$$)/ { for (i = 1; i <= NF; i++) if ($$i ~ /^[0-9A-F]+-[0-9A-F]+$$/ && length($$i) == 25) { print $$i; exit } }')
endif

.PHONY: help ios-devices ios-device

help:
	@printf '%s\n' 'make ios-devices  List paired devices and their identifiers.' 'make ios-device   Build, install, and launch Wake Runner on a physical iPhone.'

ios-devices:
	xcrun devicectl list devices

# Build a signed debug app, install it on the connected iPhone, and relaunch it.
ios-device:
	@test -n "$(IOS_DEVICE)" || { echo 'No paired iPhone found. Connect and unlock it, or pass IOS_DEVICE=<UDID>.' >&2; exit 1; }
	@test -f "$(IOS_CONFIG)" || { echo 'Copy $(IOS_DIR)/Config/Local.xcconfig.example to $(IOS_CONFIG) and set DEVELOPMENT_TEAM.' >&2; exit 1; }
	xcodebuild -project "$(IOS_DIR)/WakeRunner.xcodeproj" -scheme WakeRunner -configuration Debug \
		-destination 'id=$(IOS_DEVICE)' -derivedDataPath "$(IOS_DERIVED_DATA)" \
		-xcconfig "$(IOS_CONFIG)" -allowProvisioningUpdates -quiet \
		PRODUCT_BUNDLE_IDENTIFIER="$(IOS_BUNDLE_ID)" build
	xcrun devicectl device install app --device "$(IOS_DEVICE)" "$(IOS_APP)"
	xcrun devicectl device process launch --terminate-existing --device "$(IOS_DEVICE)" "$(IOS_BUNDLE_ID)"
