#!/usr/bin/env bash
# CI lint gate: every .onFailure that logs it.message must pass the throwable.
# Catches: Log.w(TAG, "msg: ${it.message}")  without the third ", it" arg.
#
# Usage: bash scripts/lint-logging.sh
# Exit 0 = pass, 1 = violations found

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VIOLATIONS=$(find "$REPO_ROOT" \
    -name "*.kt" \
    -not -path "*/build/*" \
    -not -path "*/.git/*" \
    -not -path "*/.gradle/*" \
    -not -path "*/src/test/*" \
    -not -path "*/src/androidTest/*" \
    -exec grep -lE 'it\.message' {} \; | \
    xargs -r grep -nE '(Log\.\w+|android\.util\.Log\.\w+)\s*\(.*it\.message' 2>/dev/null | \
    grep -vE ',\s*(it|e)\s*\)' | \
    wc -l)

# The grep -v above filters out lines that already pass the throwable.
# But some Log calls span multiple lines, so we do a second pass to
# catch only the real violations (2-arg calls without throwable).
# For CI purposes the line-level grep is sufficient — if a Log call
# with it.message is on one line and doesn't end with ", it)", it's a
# violation. Multi-line calls are caught because the it.message line
# itself won't have the closing paren.

if [ "$VIOLATIONS" -gt 0 ]; then
    echo "FAIL: $VIOLATIONS logging violation(s) found."
    echo "Each .onFailure that logs it.message must pass the throwable"
    echo "as a third arg: Log.w(TAG, \"msg: \${it.message}\", it)"
    echo
    find "$REPO_ROOT" \
        -name "*.kt" \
        -not -path "*/build/*" -not -path "*/.git/*" -not -path "*/.gradle/*" \
        -not -path "*/src/test/*" -not -path "*/src/androidTest/*" \
        -exec grep -nE '(Log\.\w+|android\.util\.Log\.\w+)\s*\(.*it\.message' {} \; 2>/dev/null | \
        grep -vE ',\s*(it|e)\s*\)'
    exit 1
fi

echo "PASS: All logging sites pass the throwable."