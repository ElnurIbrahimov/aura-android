#!/usr/bin/env bash
# Fail when the number of unreachable declarations rises.
#
# Usage: bash scripts/check-dead-code.sh [--list]
#
# This repo's most expensive recurring defect is code that is built, tested,
# documented and reachable by nothing — the live-voice stack (1,476 lines, ~60
# tests, no caller), the Library row on Home, `Turn.pinned`, the whole of
# `ui/viewmodel/CouncilViewModel.kt`. The 2026-08-22 sweep removed 121 such
# declarations. Without a gate the count regrows one PR at a time, which is how
# it reached 121.
#
# Two kinds are counted, because they fail differently:
#
#   UNUSED     — referenced nowhere at all. Dead weight, and a lie in the file
#                listing: the reader assumes something calls it.
#   TEST-ONLY  — referenced only by tests. Worse than dead, because it is dead
#                code carrying a green checkmark. §4 of ENGINEERING_HISTORY
#                records test count becoming the quality metric while screens
#                went untested; this is the mechanism.
#
# WHAT THIS CANNOT SEE, stated rather than papered over:
#
#   - Callers in generated code. Room implements `@Dao` methods and calls
#     `@TypeConverter`s; Hilt calls `@Provides`/`@Binds`. `provide*`/`bind*` are
#     skipped by name, and the two `EvolutionTypeConverters` methods are the
#     standing UNUSED baseline for exactly this reason.
#   - Functions whose name collides with an ordinary identifier. `feed` was
#     missed by the first run of this scan for that reason and turned out to be
#     a whole second implementation of streaming TTS with no caller. The count
#     is a LOWER BOUND, and a scan that claimed otherwise would be the §2.6
#     defect: a gate that cannot fail for the reason it is named after.
#   - Reflection, manifest entries, and `@Composable` previews.
#
# So this gate holds a ratchet, not a proof. A number that can only go down.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

# The baseline is the post-sweep count, and every entry behind it is justified
# in ENGINEERING_HISTORY §3. Lowering it is always welcome; raising it needs an
# argument in the commit message, not a bump.
BASELINE_UNUSED=2
BASELINE_TEST_ONLY=8

# Probed by running it, not by `command -v`. On Windows, `python3` resolves to a
# Microsoft Store stub that exists, is on PATH, and fails to launch — so
# existence is not the question worth asking. CI is ubuntu and answers python3.
PY=""
for candidate in python3 python; do
  if "$candidate" -c "" >/dev/null 2>&1; then PY="$candidate"; break; fi
done
if [ -z "$PY" ]; then
  echo "FAIL: no working python on PATH; this gate needs one."
  exit 1
fi

OUT=$("$PY" - "${1:-}" <<'PYEOF'
import collections, os, re, sys

ROOTS_MAIN = ['app/src/main', 'aura-core/src/main']
ROOTS_TEST = ['app/src/test', 'app/src/androidTest', 'app/src/debug',
              'aura-core/src/test', 'aura-core/src/androidTest']

def kt(roots):
    out = []
    for r in roots:
        for dirpath, _, names in os.walk(r):
            if 'build' in dirpath.split(os.sep):
                continue
            out += [os.path.join(dirpath, n) for n in names if n.endswith('.kt')]
    return out

main_files = kt(ROOTS_MAIN)
if not main_files:
    print('FATAL: no main sources found — the scan resolved the wrong paths.')
    raise SystemExit(2)

token = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
text = {f: open(f, encoding='utf-8', errors='replace').read() for f in main_files}
main_tokens = {f: collections.Counter(token.findall(t)) for f, t in text.items()}

test_tokens = collections.Counter()
for f in kt(ROOTS_TEST):
    test_tokens.update(token.findall(open(f, encoding='utf-8', errors='replace').read()))

decl = re.compile(
    r'^(?P<indent>[ \t]*)(?!.*\bprivate\b)(?!.*\boverride\b)'
    r'(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:public\s+|internal\s+|open\s+|suspend\s+|inline\s+|abstract\s+)*'
    r'fun\s+(?:<[^>]*>\s*)?(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(', re.M)

declared = collections.defaultdict(list)
where = {}
for f, t in text.items():
    for m in decl.finditer(t):
        name = m.group('name')
        declared[name].append(f)
        where.setdefault(name, (f, t[:m.start()].count('\n') + 1))

generated_caller = re.compile(r'^(provide|bind|inject)')
unused, test_only = [], []
for name, files in declared.items():
    if generated_caller.match(name):
        continue
    external = sum(c.get(name, 0) for f, c in main_tokens.items() if f not in files)
    internal = sum(main_tokens[f].get(name, 0) for f in files)
    if external > 0 or internal > len(files):
        continue
    (test_only if test_tokens.get(name, 0) else unused).append((name, where[name]))

if sys.argv[1:] and sys.argv[1] == '--list':
    for label, rows in (('UNUSED', unused), ('TEST-ONLY', test_only)):
        for name, (f, line) in sorted(rows, key=lambda r: r[1]):
            print(f'  {label:<10} {name}  {f}:{line}')
print(f'COUNT {len(unused)} {len(test_only)}')
PYEOF
)

status=$?
if [ $status -ne 0 ]; then
  echo "$OUT"
  echo "FAIL: the scan could not run."
  exit 1
fi

LIST=$(echo "$OUT" | grep -v '^COUNT ')
read -r _ UNUSED TEST_ONLY <<<"$(echo "$OUT" | grep '^COUNT ')"

if [ -z "${UNUSED:-}" ] || [ -z "${TEST_ONLY:-}" ]; then
  echo "FAIL: the scan produced no count. A gate that reports OK over an empty result is not a gate."
  exit 1
fi

if [ -n "$LIST" ]; then echo "$LIST"; fi

if [ "$UNUSED" -gt "$BASELINE_UNUSED" ] || [ "$TEST_ONLY" -gt "$BASELINE_TEST_ONLY" ]; then
  echo
  echo "FAIL: unreachable declarations rose."
  echo "  never referenced: $UNUSED (baseline $BASELINE_UNUSED)"
  echo "  tests only:       $TEST_ONLY (baseline $BASELINE_TEST_ONLY)"
  echo
  echo "Either wire it to something a user can reach, or delete it. If it is a"
  echo "false positive — generated caller, name collision — say so in the commit"
  echo "message and raise the baseline in this file deliberately."
  exit 1
fi

if [ "$UNUSED" -lt "$BASELINE_UNUSED" ] || [ "$TEST_ONLY" -lt "$BASELINE_TEST_ONLY" ]; then
  echo
  echo "OK, and lower than the baseline — please lower it in this file:"
  echo "  BASELINE_UNUSED=$UNUSED  BASELINE_TEST_ONLY=$TEST_ONLY"
  exit 0
fi

echo
echo "OK: $UNUSED never referenced, $TEST_ONLY referenced only by tests — both at baseline."
