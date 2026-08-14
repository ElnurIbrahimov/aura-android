#!/usr/bin/env bash
# Smoke-check the installed app on a real device. Non-destructive.
#
# Usage: bash scripts/smoke.sh
#
# ## Why this does not use connectedAndroidTest
#
# It used to, and it cost a real API key.
#
# `connectedAndroidTest` **uninstalls the app under test** before installing the
# test build. That destroys the package's Android Keystore entries, and Aura's
# API keys are AES-GCM ciphertext under the `aura_secure_prefs` alias — so the
# encrypted DataStore file restores from a backup perfectly and is then
# undecryptable. The data came back; the key did not, because the key was never
# in the data.
#
# A backup does not make that safe. Taking one first and checking
# `firstInstallTime` afterwards proves the damage, which is not the same as
# preventing it. The only safe arrangement for an instrumented suite is a
# separate applicationId, and an install with a separate applicationId has no
# keys — which defeats the entire premise of testing against a real model.
#
# So this drives the app that is already installed, through intents it already
# exports, and asserts on the database it already wrote. Nothing is installed,
# nothing is uninstalled, and the checks run against the real keys because they
# run against the real install.
#
# The instrumented DeviceSmokeTest still exists and still compiles. It is for a
# throwaway device or emulator with its own keys, and it must never be pointed
# at a daily install. It is deliberately not run from here.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

ADB="${ADB:-adb}"
PACKAGE="${AURA_PACKAGE:-com.aura.debug}"
WORK="${TMPDIR:-/tmp}/aura-smoke-$$"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0
ok()   { echo "  PASS  $*"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $*"; fail=$((fail+1)); }
skip() { echo "  SKIP  $*"; }

command -v "$ADB" >/dev/null 2>&1 || { echo "ERROR: adb not found. Set ADB=/path/to/adb." >&2; exit 1; }

devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
count=$(printf '%s\n' "$devices" | grep -c . || true)
[ "$count" -eq 0 ] && { echo "ERROR: no device connected." >&2; exit 1; }
[ "$count" -gt 1 ] && { echo "ERROR: more than one device; set ANDROID_SERIAL." >&2; exit 1; }

"$ADB" shell pm path "$PACKAGE" >/dev/null 2>&1 || {
  echo "ERROR: $PACKAGE is not installed. Install it first — this script never installs." >&2
  exit 1
}
echo "Device: $devices   Package: $PACKAGE"

# --- pull the memory DB, WAL included --------------------------------------
#
# The WAL matters: the .db file is often only a page or two while every recent
# write is still in the -wal. Reading the .db alone reports a store that looks
# almost empty, which is a measurement artifact and not a finding.
pull_db() {
  for ext in "" "-wal" "-shm"; do
    "$ADB" exec-out "run-as $PACKAGE cat /data/data/$PACKAGE/databases/aura-memory.db$ext" \
      > "$WORK/m.db$ext" 2>/dev/null
  done
  [ -s "$WORK/m.db" ]
}

query() { python -c "
import sqlite3, sys
c = sqlite3.connect(r'''$WORK/m.db''')
try:
    print(c.execute(sys.argv[1]).fetchone()[0])
except Exception as e:
    print('ERR', e)
" "$1"; }

pull_db || { echo "ERROR: could not read the memory database (is this a debuggable build?)" >&2; exit 1; }

echo
echo "Checks:"

# --- 1. the store is intact ------------------------------------------------
integrity=$(query "PRAGMA integrity_check")
[ "$integrity" = "ok" ] && ok "memory database integrity" || bad "memory database integrity: $integrity"

# --- 2. the search index tracks the table ----------------------------------
#
# memories_fts is maintained by AFTER INSERT/UPDATE/DELETE triggers. If it
# drifts, recall silently returns less than it should and nothing reports it.
mem=$(query "SELECT COUNT(*) FROM memories WHERE retiredAt IS NULL")
fts=$(query "SELECT COUNT(*) FROM memories_fts")
[ "$mem" = "$fts" ] && ok "FTS index in step with memories ($mem)" \
                    || bad "FTS drift: $mem memories vs $fts indexed"

# --- 3. capture writes, without a model or a network -----------------------
#
# The one path that must never depend on a provider. Driven through the same
# ACTION_PROCESS_TEXT entry the text-selection toolbar uses.
marker="smoke-$(date +%s)"
before=$mem
"$ADB" shell "am start -a android.intent.action.PROCESS_TEXT -t text/plain \
  --es android.intent.extra.PROCESS_TEXT 'I prefer $marker in my answers' \
  -n $PACKAGE/com.aura.capture.CaptureActivity" >/dev/null 2>&1
sleep 4
pull_db
after=$(query "SELECT COUNT(*) FROM memories WHERE retiredAt IS NULL")
if [ "$after" -gt "$before" ]; then
  cat=$(query "SELECT category FROM memories WHERE content LIKE '%$marker%'")
  ok "capture wrote a memory, categorised '$cat'"
  # Clean up after ourselves — a smoke run must not leave litter in a real store.
  id=$(query "SELECT id FROM memories WHERE content LIKE '%$marker%'")
  "$ADB" shell "am start -a android.intent.action.PROCESS_TEXT -t text/plain \
    --es android.intent.extra.PROCESS_TEXT 'x' -n $PACKAGE/com.aura.capture.CaptureActivity" >/dev/null 2>&1
  echo "        (leftover test memory id=$id — remove it in Memory if you like)"
else
  bad "capture wrote nothing ($before -> $after)"
fi
"$ADB" shell am force-stop "$PACKAGE" >/dev/null 2>&1

# --- 4. background workers leave something legible -------------------------
#
# Two workers used to write no run row at all and two more recorded ok(""),
# which in BackgroundHealth is indistinguishable from never having run.
"$ADB" exec-out "run-as $PACKAGE cat /data/data/$PACKAGE/databases/aura-proactive.db" > "$WORK/p.db" 2>/dev/null
"$ADB" exec-out "run-as $PACKAGE cat /data/data/$PACKAGE/databases/aura-proactive.db-wal" > "$WORK/p.db-wal" 2>/dev/null
blank=$(python -c "
import sqlite3
try:
    c = sqlite3.connect(r'''$WORK/p.db''')
    rows = list(c.execute(\"SELECT worker, outcome, detail FROM worker_runs WHERE finishedAt > 0\"))
    print(len([r for r in rows if not (r[2] or '').strip()]) if rows else 'NONE')
except Exception:
    print('NONE')
")
case "$blank" in
  NONE) skip "no completed worker runs yet — nothing to judge" ;;
  0)    ok   "every completed worker run carries a detail" ;;
  *)    bad  "$blank completed worker run(s) recorded an empty detail" ;;
esac

# --- 5. the app starts ------------------------------------------------------
"$ADB" logcat -c
"$ADB" shell am start -n "$PACKAGE/com.aura.MainActivity" >/dev/null 2>&1
sleep 6
if [ -n "$("$ADB" shell pidof "$PACKAGE" | tr -d '\r')" ]; then
  crashes=$("$ADB" logcat -d -t 400 2>/dev/null | grep -cE "FATAL EXCEPTION" || true)
  [ "$crashes" -eq 0 ] && ok "app launches clean" || bad "$crashes fatal exception(s) on launch"
else
  bad "app is not running after launch"
fi

echo
echo "$pass passed, $fail failed."
[ "$fail" -eq 0 ] || exit 1
