#!/usr/bin/env bash
# Fail when a plain user-visible string is hardcoded in a Compose `Text(...)`.
#
# Usage: bash scripts/check-hardcoded-strings.sh
#
# 145 of these were moved into strings.xml on 2026-08-16. That work is only
# worth anything if it stays done: a translation is all-or-nothing from the
# user's side, and a screen that is half translated reads worse than one that is
# honestly English. Without a gate the count climbs back one PR at a time, the
# way it reached 205 in the first place.
#
# Literals containing a backslash are skipped too, and that is not laziness about
# escaping: the three in this codebase are a glyph, an emoji, and a JSON example
# used as a text-field placeholder. None is prose, none has a translation, and
# moving them into strings.xml would add three entries a translator must skip.
#
# WHAT THIS DOES NOT COVER, deliberately. Interpolated strings —
# `Text("Domain: ${p.domain}")` — are still allowed, because converting them
# needs a judgment this script cannot make: the argument order, whether the
# value is a count that needs `<plurals>` rather than `%1$s` (`"$wordCount
# words"` currently renders "1 words"), and whether a developer-facing screen
# showing raw enum values is worth translating at all. 57 remain, listed by
# `--list`. They are the reason localisation is not yet unblocked, and pretending
# otherwise by silencing them here would be worse than counting them.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

SRC="app/src/main/kotlin"

# `Text("` also matches `ClipData.newPlainText("`, which is a clipboard label in
# a non-composable lambda and not user-visible text. Anchor on a boundary before
# `Text(` so only the composable is matched — the first version of this scan did
# not, and rewrote three clipboard labels into `stringResource` calls that could
# not compile.
PATTERN='(^|[^A-Za-z])Text\("[^"$\\]*"'

if [ "${1:-}" = "--list" ]; then
  echo "Interpolated Text(...) strings still to externalise:"
  grep -rnoE '(^|[^A-Za-z])Text\("[^"]*\$[^"]*"' --include="*.kt" "$SRC" \
    | sed "s|$SRC/com/aura/||" | sort
  exit 0
fi

hits=$(grep -rnE "$PATTERN" --include="*.kt" "$SRC" | grep -v '""' || true)

if [ -n "$hits" ]; then
  count=$(echo "$hits" | wc -l | tr -d ' ')
  echo "ERROR: ${count} hardcoded user-visible string(s) in Compose Text(...):"
  echo "$hits" | sed "s|$SRC/com/aura/|  |"
  echo
  echo "Move them to app/src/main/res/values/strings.xml and use"
  echo "stringResource(R.string.<name>). A half-externalised UI cannot be"
  echo "translated, which is the whole point of having moved the other 145."
  exit 1
fi

remaining=$(grep -rcoE '(^|[^A-Za-z])Text\("[^"]*\$[^"]*"' --include="*.kt" "$SRC" \
  | awk -F: '{s+=$2} END {print s+0}')
echo "OK: no plain hardcoded strings in Compose Text(...). ${remaining} interpolated one(s) remain — see --list."
