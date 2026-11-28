.DEFAULT_GOAL := help

IOS_DIR = mobile/ios
IOS_BUNDLE_ID ?= com.boringman.thelastsail
IOS_DERIVED_DATA ?= $(IOS_DIR)/build/DerivedData
IOS_CONFIG ?= $(IOS_DIR)/Config/Local.xcconfig
IOS_APP = $(IOS_DERIVED_DATA)/Build/Products/Debug-iphoneos/TheLastSail.app
# Use the first available paired physical iPhone. Override with IOS_DEVICE=<UDID>.
ifeq ($(origin IOS_DEVICE), undefined)
IOS_DEVICE := $(shell xcrun devicectl list devices 2>/dev/null | awk '/physical/ && /(^|[[:space:]])available([[:space:]]|$$)/ { for (i = 1; i <= NF; i++) if ($$i ~ /^[0-9A-F]+-[0-9A-F]+$$/ && length($$i) == 25) { print $$i; exit } }')
endif

DOCKER ?= docker
COMPOSE = $(DOCKER) compose -f compose.yaml
COLIMA_PROFILE ?= default
WEB_URL = http://127.0.0.1:$${WEB_PORT:-8080}/

.PHONY: help ios-devices ios-device docker-ready web web-up web-down web-check deploy deploy-status

help:
	@printf '%s\n' \
		'make ios-devices    List paired devices and their identifiers.' \
		'make ios-device     Build, install, and launch The Last Sail on a physical iPhone.' \
		'make web            Start Docker if needed, serve the website and open it in the browser.' \
		'make web-up         Serve the website in Docker at http://127.0.0.1:8080.' \
		'make web-down       Stop the local website container.' \
		'make web-check      Build the website image and check its pages and nginx configs.' \
		'make deploy         (VM only) Build and start the production website container.'

# Start Colima when the Docker daemon is not reachable (local Mac development).
docker-ready:
	@$(DOCKER) info >/dev/null 2>&1 || { \
		command -v colima >/dev/null || { echo 'Docker is not running. Start Docker or install Colima.' >&2; exit 1; }; \
		echo 'Starting Colima ($(COLIMA_PROFILE))...'; colima start $(COLIMA_PROFILE); }

web: web-up
	@if command -v open >/dev/null; then open "$(WEB_URL)"; fi

web-up: docker-ready
	$(COMPOSE) up -d --build --wait
	@echo "Website: $(WEB_URL)"

web-down:
	$(COMPOSE) down

web-check: docker-ready
	bash ci/check-web.sh

# Production: run on the VM by deploy/bootstrap-vm.sh. Host nginx proxies to 127.0.0.1:4174.
deploy deploy-status: COMPOSE += --project-name the-last-sail -f compose.production.yaml

deploy:
	$(COMPOSE) config --quiet
	$(COMPOSE) build
	@$(COMPOSE) up -d --no-build --wait --wait-timeout 120 || { \
		$(COMPOSE) ps; echo 'Deployment failed. Inspect container health on the VM.' >&2; exit 1; }

deploy-status:
	$(COMPOSE) ps

ios-devices:
	xcrun devicectl list devices

# Build a signed debug app, install it on the connected iPhone, and relaunch it.
ios-device:
	@test -n "$(IOS_DEVICE)" || { echo 'No paired iPhone found. Connect and unlock it, or pass IOS_DEVICE=<UDID>.' >&2; exit 1; }
	@test -f "$(IOS_CONFIG)" || { echo 'Copy $(IOS_DIR)/Config/Local.xcconfig.example to $(IOS_CONFIG) and set DEVELOPMENT_TEAM.' >&2; exit 1; }
	xcodebuild -project "$(IOS_DIR)/TheLastSail.xcodeproj" -scheme TheLastSail -configuration Debug \
		-destination 'id=$(IOS_DEVICE)' -derivedDataPath "$(IOS_DERIVED_DATA)" \
		-xcconfig "$(IOS_CONFIG)" -allowProvisioningUpdates -quiet \
		PRODUCT_BUNDLE_IDENTIFIER="$(IOS_BUNDLE_ID)" build
	xcrun devicectl device install app --device "$(IOS_DEVICE)" "$(IOS_APP)"
	xcrun devicectl device process launch --terminate-existing --device "$(IOS_DEVICE)" "$(IOS_BUNDLE_ID)"
