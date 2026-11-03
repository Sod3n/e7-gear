#!/bin/sh
# Unit rules and the screen audit on an emulator (never a phone: it replaces the box). Lint runs with every release build.
# Usage: E7_FIXTURES=<folder with gear.txt> tools/audit.sh     -> screenshots and report in app/build/audit/
# A design round checks only what it touched: AUDIT_ONLY='improve-heroes*' AUDIT_SETUPS='353-big-en,353-ru' (comma lists, * = prefix)
# AUDIT_UNIT=0 skips the unit tests when they already ran this round
set -e
cd "$(dirname "$0")/.."
: "${E7_FIXTURES:?set E7_FIXTURES to a folder holding gear.txt}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
ANDROID_SERIAL=$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice/{print $1; exit}')
[ -n "$ANDROID_SERIAL" ] || { echo "start an emulator first"; exit 1; }
export ANDROID_SERIAL E7_FIXTURES
rm -rf app/build/audit
"$ADB" shell rm -rf /sdcard/Android/data/e7.gearscan/files/audit
# exports from earlier installs pile up (Android numbers a name only up to 31 times)
"$ADB" shell rm -rf /sdcard/Download/E7GearScan
# saved heroes, requests and tiers from the last run would change what this run finds
"$ADB" shell pm clear e7.gearscan >/dev/null 2>&1 || true
[ "${AUDIT_UNIT:-1}" = 0 ] || ./gradlew -q testDebugUnitTest
status=0
set -- -Pandroid.testInstrumentationRunnerArguments.class=e7.gearscan.ScreenAudit
[ -n "$AUDIT_ONLY" ] && set -- "$@" "-Pandroid.testInstrumentationRunnerArguments.only=$AUDIT_ONLY"
[ -n "$AUDIT_SETUPS" ] && set -- "$@" "-Pandroid.testInstrumentationRunnerArguments.setups=$AUDIT_SETUPS"
./gradlew -q connectedDebugAndroidTest "$@" \
	-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || status=1
"$ADB" pull /sdcard/Android/data/e7.gearscan/files/audit app/build/audit >/dev/null 2>&1 || true
{ cat app/build/audit/report.txt && echo; } 2>/dev/null || echo "no report (the audit did not run)"
exit $status
