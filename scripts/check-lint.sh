#!/usr/bin/env bash
#
# Android Lint, on both modules, the way CI runs it.
#
# This script exists because of a specific repeated failure. Local verification was two
# unit suites and four gate scripts; CI runs all of that *and* lint. Lint was therefore the
# one check that could only ever fail after a push, and it did — most recently on a
# MissingPermission error in AndroidAgentTaskNotifier that would have failed every run,
# while the emulator jobs beside it were failing for unrelated infrastructure reasons. Two
# causes wearing one symptom, which is what made "CI is flaky" the wrong conclusion.
#
# Lint is slow (~3 minutes cold) and that is the whole reason it kept getting skipped. It
# belongs in the named set anyway: a check that is only run when remembered is a check that
# reports at push time.
#
# Usage:  bash scripts/check-lint.sh
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

GRADLE="./gradlew"
[ -x "$GRADLE" ] || GRADLE="gradle"

echo "Running Android Lint on :aura-core and :app (this takes a few minutes)..."
output=$("$GRADLE" :aura-core:lintDebug :app:lintDebug --console=plain 2>&1)
status=$?

if [ "$status" -eq 0 ]; then
  # Warnings are not failures here — CI's threshold is errors — but the count is worth
  # seeing, because a warning today is how an error arrives later.
  summary=$(printf '%s\n' "$output" | grep -oE "Lint found [0-9]+ error" | tail -1)
  hint=$(printf '%s\n' "$output" | grep -oE "[0-9]+ warnings?" | tail -1)
  echo "PASS: lint found no errors${hint:+ (${hint})}."
  exit 0
fi

echo
echo "Lint failed. The errors, in the order lint reported them:"
echo
printf '%s\n' "$output" | grep -E "Error:|error:" | grep -vE "^\s*$" | head -20
echo
echo "Full output:"
echo "  ./gradlew :aura-core:lintDebug :app:lintDebug"
echo "Reports:"
echo "  aura-core/build/reports/lint-results-debug.html"
echo "  app/build/reports/lint-results-debug.html"
exit 1
