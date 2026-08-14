#!/usr/bin/env bash
# Run the device smoke suite against a real phone and a real model.
#
# Usage: bash scripts/smoke.sh
#
# This is NOT a CI gate and cannot be made into one. .github/workflows/ci.yml
# compiles instrumented tests and never executes them — there is no emulator
# step and no connectedAndroidTest anywhere. Adding an emulator job would not
# help either: an emulator has no API keys, and the keys are the entire premise
# of this suite. The honest substitute for a gate is a command you can run, that
# refuses to run against the wrong thing, and that takes a backup first.
#
# What it asserts is in app/src/androidTest/kotlin/com/aura/smoke/DeviceSmokeTest.kt:
# five outcomes a user would notice, below the UI. Thirteen defects were found
# in this codebase in a single session, every one of which passed 3,065 unit
# tests, because those are tests of pure logic and so are 36 of the 38
# instrumented ones.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

ADB="${ADB:-adb}"
PACKAGE="com.aura.debug"
BACKUP_DIR="${AURA_BACKUP_DIR:-/d/aura-backups}"

fail() { echo "ERROR: $*" >&2; exit 1; }

# --- 1. A device, and exactly one ------------------------------------------
command -v "$ADB" >/dev/null 2>&1 || fail "adb not found. Set ADB=/path/to/adb."

devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
count=$(printf '%s\n' "$devices" | grep -c . || true)
[ "$count" -eq 0 ] && fail "No device connected."
[ "$count" -gt 1 ] && fail "More than one device connected; set ANDROID_SERIAL and re-run."
echo "Device: $devices"

# --- 2. Back up before anything reinstalls anything -------------------------
#
# connectedAndroidTest installs the app under test. With a matching signing key
# that is an update and the data survives, but "should" is not a backup — a
# signature mismatch replaces the package and wipes it, which has already
# happened once in this project.
if "$ADB" shell pm path "$PACKAGE" >/dev/null 2>&1; then
  mkdir -p "$BACKUP_DIR"
  stamp=$(date +%Y%m%d-%H%M%S)
  out="$BACKUP_DIR/aura-smoke-pre-$stamp.tar"
  if "$ADB" exec-out "run-as $PACKAGE tar c -C /data/data/$PACKAGE ." > "$out" 2>/dev/null && [ -s "$out" ]; then
    echo "Backup: $out ($(wc -c < "$out") bytes)"
  else
    rm -f "$out"
    fail "Could not back up $PACKAGE. Refusing to run a suite that reinstalls it.
       (run-as needs a debuggable build; a release install cannot be backed up this way.)"
  fi
  before=$("$ADB" shell dumpsys package "$PACKAGE" | grep -m1 firstInstallTime | tr -d ' \r')
else
  echo "No existing $PACKAGE install — nothing to back up."
  before=""
fi

# --- 3. Run it --------------------------------------------------------------
echo
echo "Running DeviceSmokeTest — real graph, real keys, real network."
echo "A turn is a real model round-trip; allow a couple of minutes."
echo
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aura.smoke.DeviceSmokeTest
status=$?

# --- 4. Prove the install was an update, not a replacement ------------------
if [ -n "$before" ]; then
  after=$("$ADB" shell dumpsys package "$PACKAGE" | grep -m1 firstInstallTime | tr -d ' \r')
  if [ "$before" != "$after" ]; then
    echo
    echo "WARNING: firstInstallTime changed ($before -> $after)."
    echo "         The package was REPLACED, not updated, so its data was wiped."
    echo "         Restore from the backup above before doing anything else."
    exit 1
  fi
  echo "firstInstallTime unchanged — the install was an update and the data survived."
fi

echo
if [ $status -eq 0 ]; then
  echo "PASS. Report: app/build/reports/androidTests/connected/index.html"
else
  echo "FAIL. Report: app/build/reports/androidTests/connected/index.html"
  echo "A skip is not a pass: a skipped check names the thing it needed"
  echo "(a configured model, an enabled accessibility service) in its message."
fi
exit $status
