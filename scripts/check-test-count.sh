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

# Two defects this script used to have, both of which let a wrong number pass:
#
# - It summed the `tests` attribute and stopped there. JUnit counts a skipped
#   test inside `tests`, so an @Ignore left the headline number identical while
#   the gate behind it stopped running — the docs would still say "N unit tests,
#   0 failures" over a suite that had quietly stopped checking something. The
#   count now subtracts `skipped`, and a non-zero `skipped` fails outright: an
#   ignored test is a gate switched off without being deleted, and this repo has
#   spent a lot of effort not doing that.
# - It looked only under `testDebugUnitTest`. A run of any other variant left
#   nothing this script could see, so it either reported a stale debug number or
#   errored as if no tests had run; and running two variants would have doubled
#   the total by summing them together. It now reads every `test*UnitTest`
#   directory, totals each variant on its own, and requires the variants that
#   are present to agree on how many tests exist. A left-over result directory
#   from a variant you are no longer running is therefore a failure rather than
#   a silent contribution — delete `*/build/test-results` and re-run.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

# Docs that claim the count in the present tense.
DOCS=("README.md" "architecture.md")

VARIANTS=$(find app aura-core \
  -path '*/build/test-results/test*UnitTest' \
  -type d 2>/dev/null \
  | sed -E 's|.*/test-results/||' \
  | sort -u)

# Fail loudly when there is nothing to read. A gate that prints OK after
# scanning zero files cannot fail for the reason it is named after — see
# ENGINEERING_HISTORY §2.6, where four source-scanning tests carried exactly
# this defect and reported no violations over an empty file list.
if [ -z "$VARIANTS" ]; then
  echo "ERROR: no JUnit XML found under */build/test-results/test*UnitTest/."
  echo "       This check reads the results of a real run; it cannot infer them."
  echo "       Run: ./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest"
  exit 1
fi

variant_files() {
  find app aura-core -path "*/build/test-results/$1/TEST-*.xml" -type f 2>/dev/null
}

# Sum one attribute across every <testsuite> opening tag. Restricting to the
# opening tag first keeps stdout/stderr captured inside <system-out> from
# contributing stray matches.
sum_attr() {
  local files="$1" attr="$2"
  echo "$files" \
    | tr '\n' '\0' \
    | xargs -0 grep -ho '<testsuite [^>]*' 2>/dev/null \
    | grep -oE "${attr}=\"[0-9]+\"" \
    | grep -oE '[0-9]+' \
    | awk '{ s += $1 } END { print s + 0 }'
}

fail=0
ACTUAL=""
FIRST_VARIANT=""
SUITES=0
SKIPPED=0
FAILURES=0
ERRORS=0

for variant in $VARIANTS; do
  files=$(variant_files "$variant")
  if [ -z "$files" ]; then continue; fi

  raw=$(sum_attr "$files" tests)
  skipped=$(sum_attr "$files" skipped)
  ran=$((raw - skipped))
  suites=$(echo "$files" | wc -l | tr -d ' ')

  if [ "$raw" -eq 0 ]; then
    echo "ERROR: ${variant}: found $suites result file(s) but parsed 0 tests. The XML format changed."
    exit 1
  fi

  SKIPPED=$((SKIPPED + skipped))
  FAILURES=$((FAILURES + $(sum_attr "$files" failures)))
  ERRORS=$((ERRORS + $(sum_attr "$files" errors)))

  if [ -z "$ACTUAL" ]; then
    ACTUAL=$ran
    FIRST_VARIANT=$variant
    SUITES=$suites
  elif [ "$ran" -ne "$ACTUAL" ]; then
    echo "ERROR: ${variant} ran ${ran} tests but ${FIRST_VARIANT} ran ${ACTUAL}."
    echo "       Two build variants disagree on how many tests exist. One of them"
    echo "       is compiling a different source set than you think it is, or one"
    echo "       set of results is stale — delete */build/test-results and re-run."
    fail=1
  fi
done

if [ -z "$ACTUAL" ]; then
  echo "ERROR: found variant directories but no TEST-*.xml inside any of them."
  exit 1
fi

if [ "$SKIPPED" -ne 0 ]; then
  echo "ERROR: ${SKIPPED} test(s) were skipped."
  echo "       A skipped test is a gate that was switched off without being deleted,"
  echo "       and it keeps inflating the count this file exists to keep honest."
  echo "       Delete it, or fix what it was guarding — do not @Ignore it."
  fail=1
fi

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
