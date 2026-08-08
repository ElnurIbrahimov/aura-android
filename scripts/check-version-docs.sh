#!/usr/bin/env bash
# Verify README/docs version claims match the actual build.
# Fails CI when the docs drift from versionName/versionCode again.
#
# Usage: bash scripts/check-version-docs.sh
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

BUILD_FILE="app/build.gradle.kts"
README="README.md"
# The root architecture.md is the canonical one. This pointed at
# docs/architecture.md, which was a second, drifting copy — since that file
# carried no version line, the `grep -q versionName|versionCode` guard below
# skipped it entirely and the check passed while it sat three releases stale.
# docs/architecture.md is now a pointer; the real document is here.
ARCH="architecture.md"

VERSION_NAME=$(grep -oE 'versionName = "[^"]+"' "$BUILD_FILE" | head -1 | sed 's/versionName = "//;s/"//')
VERSION_CODE=$(grep -oE 'versionCode = [0-9]+' "$BUILD_FILE" | head -1 | grep -oE '[0-9]+')

fail=0

if ! grep -q "v${VERSION_NAME}" "$README" 2>/dev/null; then
  echo "ERROR: README.md does not mention v${VERSION_NAME} (build versionName). Update the Status section."
  fail=1
fi

if ! grep -q "versionCode ${VERSION_CODE}" "$README" 2>/dev/null; then
  echo "ERROR: README.md does not mention versionCode ${VERSION_CODE} (build versionCode). Update the Status section."
  fail=1
fi

if [ -f "$ARCH" ] && grep -q "versionName\|versionCode" "$ARCH" 2>/dev/null; then
  if ! grep -qE "v?${VERSION_NAME}" "$ARCH" 2>/dev/null; then
    echo "ERROR: ${ARCH} references a version but not ${VERSION_NAME}. Update it."
    fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "Version documentation drift detected. Fix before shipping."
  exit 1
fi

echo "OK: docs match v${VERSION_NAME} (versionCode ${VERSION_CODE})."
