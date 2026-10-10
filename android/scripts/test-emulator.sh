#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?Usage: ./scripts/test-emulator.sh emulator-5580}"
case "$serial" in emulator-*) ;; *) echo 'Use a dedicated emulator; this script changes VPN consent and app configuration.' >&2; exit 1 ;; esac
adb_bin="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platform-tools/adb"
"$adb_bin" -s "$serial" get-state
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
mkdir -p .test-device
python3 ../tools/fixtures/stream-echo.py > .test-device/fixture.log 2>&1 &
fixture_pid=$!
trap 'kill "$fixture_pid" 2>/dev/null || true' EXIT
sleep 1
kill -0 "$fixture_pid"
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb_bin" -s "$serial" shell appops set dev.ipv6lab ACTIVATE_VPN allow
"$adb_bin" -s "$serial" shell pm grant dev.ipv6lab android.permission.POST_NOTIFICATIONS
"$adb_bin" -s "$serial" shell am instrument -w dev.ipv6lab.test/androidx.test.runner.AndroidJUnitRunner | tee .test-device/instrumentation.txt
grep -q '^OK (' .test-device/instrumentation.txt
