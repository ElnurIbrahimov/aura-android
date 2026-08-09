#!/usr/bin/env bash
# Verify the unit-test count claimed in the docs against the run that just
# happened, and that the run was green.
#
# Usage: bash scripts/check-test-count.sh
#        (run AFTER :aura-core:testDebugUnitTest and :app:testDebugUnitTest)
#
# Why this is a separate script from check-version-docs.sh: that one runs in
# the cheap `gates` CI job, which has no JDK, no SDK and no Gradle. It cannot
# know how many tests there are. This one reads the JUnit XML, so it has to run
# in `build-test`, after the tests.
#
# README.md and architecture.md state the count in the present tense. They said
# 2,152 while the suite was at 2,225 — the same drift class check-version-docs.sh
# exists to stop, in the numbers it does not look at.
#
# ENGINEERING_HISTORY.md is deliberately NOT checked. Its count sits under
# "Baseline at the 2026-08-08 check", which is a dated record of one run and is
# supposed to keep saying what it said.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

# Docs that claim the count in the present tense.
DOCS=("README.md" "architecture.md")

RESULT_FILES=$(find app aura-core \
  -path '*/build/test-results/testDebugUnitTest/TEST-*.xml' \
  -type f 2>/dev/null)

# Fail loudly when there is nothing to read. A gate that prints OK after
# scanning zero files cannot fail for the reason it is named after — see
# ENGINEERING_HISTORY §2.6, where four source-scanning tests carried exactly
# this defect and reported no violations over an empty file list.
if [ -z "$RESULT_FILES" ]; then
  echo "ERROR: no JUnit XML found under */build/test-results/testDebugUnitTest/."
  echo "       This check reads the results of a real run; it cannot infer them."
  echo "       Run: ./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest"
  exit 1
fi

# Sum one attribute across every <testsuite> opening tag. Restricting to the
# opening tag first keeps stdout/stderr captured inside <system-out> from
# contributing stray matches.
sum_attr() {
  local attr="$1"
  echo "$RESULT_FILES" \
    | tr '\n' '\0' \
    | xargs -0 grep -ho '<testsuite [^>]*' 2>/dev/null \
    | grep -oE "${attr}=\"[0-9]+\"" \
    | grep -oE '[0-9]+' \
    | awk '{ s += $1 } END { print s + 0 }'
}

ACTUAL=$(sum_attr tests)
FAILURES=$(sum_attr failures)
ERRORS=$(sum_attr errors)
SUITES=$(echo "$RESULT_FILES" | wc -l | tr -d ' ')

if [ "$ACTUAL" -eq 0 ]; then
  echo "ERROR: found $SUITES result file(s) but parsed 0 tests. The XML format changed."
  exit 1
fi

fail=0

if [ "$FAILURES" -ne 0 ] || [ "$ERRORS" -ne 0 ]; then
  echo "ERROR: the suite is not green — ${FAILURES} failure(s), ${ERRORS} error(s)."
  echo "       The docs claim '0 failures'. Fix the tests or stop claiming it."
  fail=1
fi

for doc in "${DOCS[@]}"; do
  if [ ! -f "$doc" ]; then
    echo "ERROR: ${doc} is listed in this check but does not exist."
    fail=1
    continue
  fi
  # "2,225 unit tests" / "2225 unit tests" -> 2225
  claimed=$(grep -oE '[0-9][0-9,]* unit tests' "$doc" | head -1 | grep -oE '[0-9][0-9,]*' | tr -d ',')
  if [ -z "$claimed" ]; then
    # Not "nothing to check" — the phrase is what this gate is anchored to. If
    # the count is genuinely being dropped from a doc, drop it from DOCS above
    # in the same commit, so the removal is a decision rather than a silence.
    echo "ERROR: ${doc} states no 'N unit tests' count, but is listed in this check."
    echo "       Either restore the count or remove ${doc} from DOCS in $0."
    fail=1
    continue
  fi
  if [ "$claimed" -ne "$ACTUAL" ]; then
    echo "ERROR: ${doc} claims ${claimed} unit tests; the suite has ${ACTUAL}."
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "Test-count documentation drift detected. Fix before shipping."
  exit 1
fi

echo "OK: ${ACTUAL} unit tests across ${SUITES} suites, 0 failures — docs match."
