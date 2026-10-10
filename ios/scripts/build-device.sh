#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$#" -ne 2 ]; then
  echo "Usage: $0 TEAM_ID DEVICE_UDID" >&2
  exit 2
fi
xcodebuild -project IPv6Lab.xcodeproj -scheme IPv6Lab -configuration Debug \
  -destination "id=$2" -derivedDataPath build-device DEVELOPMENT_TEAM="$1" \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration build
printf '\nBuilt: %s\n' "$PWD/build-device/Build/Products/Debug-iphoneos/IPv6Lab.app"
