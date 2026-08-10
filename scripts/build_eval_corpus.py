#!/usr/bin/env python3
"""Turn a real Aura backup into a redacted retrieval-eval corpus.

The eval harness is only as good as its corpus, and the shipped one is a
synthetic scaffold that proves nothing:

    Synthetic corpora have uniform style and no natural vocabulary-overlap
    structure. Every retrieval change scores as an improvement against them,
    including changes that make retrieval worse.

So the corpus has to be real memories. But a real memory database is the most
private thing in this app, and the eval fixtures are committed to git. This
script is the bridge: it converts a backup export into `corpus.jsonl` with
identifiers redacted, timestamps made relative, and a report of what it touched.

    python scripts/build_eval_corpus.py path/to/aura-backup.json
    python scripts/build_eval_corpus.py backup.json --limit 400 --out /tmp/corpus.jsonl

Then judge the queries — `--queries` emits a template with pooled candidates,
see `docs/RETRIEVAL_EVAL.md`.

REDACTION IS ASSISTIVE, NOT A GUARANTEE. It catches the patterned things:
emails, phone numbers, cards, keys, URLs with credentials, long digit runs. It
cannot catch "my landlord is called Ferhat and he lives upstairs". **Read the
output before committing it.** The script prints a redaction count precisely so
that a suspiciously low number is visible rather than reassuring.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

# ORDER IS LOAD-BEARING, and got this wrong on its first run: `phone` matched
# the digit run inside `AZ21NABZ00000000137010001944` and emitted
# "IBAN AZ21NABZ[phone]", leaving the country and bank code in place. The most
# specific patterns have to consume their text before the greedy digit ones see
# it. Structured identifiers first, bare digit runs last.
PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("url", re.compile(r"https?://\S+")),
    ("email", re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.]+\b")),
    ("key", re.compile(r"\b(?:sk|pk|api|key|token|bearer)[-_][A-Za-z0-9_\-]{12,}\b", re.I)),
    ("iban", re.compile(r"\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b")),
    # Digit at both ends, so a trailing space is not eaten. The first version
    # ended `[ -]?` and produced "Card on file [card]expires next year" — two
    # words fused into one, which changes what the tokenizer sees and quietly
    # alters the corpus the eval is measured against.
    ("card", re.compile(r"\b\d(?:[ -]?\d){12,18}\b")),
    ("phone", re.compile(r"\+?\d[\d\s().-]{7,}\d")),
    ("id", re.compile(r"\b\d{6,}\b")),
]

DAY_MS = 86_400_000


def redact(text: str, counts: dict[str, int]) -> str:
    for name, pattern in PATTERNS:
        text, n = pattern.subn(f"[{name}]", text)
        counts[name] = counts.get(name, 0) + n
    return text


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("backup", type=pathlib.Path, help="Aura backup JSON (Settings -> Backup -> Export)")
    ap.add_argument("--out", type=pathlib.Path,
                    default=pathlib.Path("aura-core/src/test/resources/retrieval-eval/corpus.jsonl"))
    ap.add_argument("--limit", type=int, default=400,
                    help="documents to keep, most-accessed first (default 400)")
    ap.add_argument("--min-chars", type=int, default=20, help="skip memories shorter than this")
    ap.add_argument("--queries", type=pathlib.Path,
                    help="also write a judgment TEMPLATE here, one row per query you list in --seed")
    ap.add_argument("--seed", type=pathlib.Path,
                    help="newline-separated queries to build the judgment template from")
    args = ap.parse_args()

    data = json.loads(args.backup.read_text(encoding="utf-8"))
    memories = data.get("memories") or data.get("memoryBackups") or []
    if not memories:
        print(f"No memories in {args.backup}. Keys present: {sorted(data)[:12]}", file=sys.stderr)
        return 1

    # Newest timestamp as "now", so ages come out RELATIVE. An absolute epoch
    # would make the corpus age between runs and drift recency and decay until
    # a suite that passed in August fails in December for no reason.
    now = max(int(m.get("createdAt", 0)) for m in memories)

    kept = [m for m in memories if len(str(m.get("content", "")).strip()) >= args.min_chars]
    # Most-accessed first: if the corpus is truncated, keep the memories that
    # actually get recalled rather than an arbitrary slice.
    kept.sort(key=lambda m: (int(m.get("accessCount", 0)), float(m.get("decayScore", 0))), reverse=True)
    kept = kept[: args.limit]

    counts: dict[str, int] = {}
    rows = []
    for i, m in enumerate(kept):
        created = int(m.get("createdAt", now))
        accessed = int(m.get("accessedAt", created))
        rows.append({
            "id": f"m{i + 1:04d}",  # positional, so the real row id never leaves the device
            "content": redact(str(m["content"]).strip(), counts),
            "category": m.get("category", "fact"),
            # Agent-private memories become general: scope is an eval dimension
            # nobody is measuring, and keeping "agent:<uuid>" would leak an id.
            "scope": "general",
            "importance": round(float(m.get("importance", 0.5)), 3),
            "created_days_ago": max(0, (now - created) // DAY_MS),
            "accessed_days_ago": max(0, (now - accessed) // DAY_MS),
            "access_count": int(m.get("accessCount", 0)),
            "decay_score": round(float(m.get("decayScore", 1.0)), 4),
            "tags": redact(str(m.get("tags", "")), counts),
        })

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")

    total = sum(counts.values())
    print(f"{len(rows)} documents -> {args.out}")
    print(f"redactions: {total}" + (f"  ({', '.join(f'{k}={v}' for k, v in counts.items() if v)})" if total else ""))
    if total == 0:
        print(
            "  NOTE: zero redactions. That is possible, but read the file before committing —\n"
            "  the patterns catch emails, numbers and keys, not names or relationships.",
            file=sys.stderr,
        )

    if args.queries:
        if not args.seed:
            print("--queries needs --seed (a file of queries, one per line)", file=sys.stderr)
            return 1
        seeds = [q.strip() for q in args.seed.read_text(encoding="utf-8").splitlines() if q.strip()]
        write_template(args.queries, seeds, rows)
        print(f"{len(seeds)} query stubs -> {args.queries}")
    else:
        print(
            "\nNext: write queries.jsonl. Judgments are YOUR call — 0 irrelevant, 1 related,\n"
            "2 relevant, 3 ideal — and they decide what 'better retrieval' means, so nothing\n"
            "else can produce them. Include at least one expect-empty query and several\n"
            "synonym-only ones (no word in common with their answer): the synonym-only class\n"
            "is the entire Gate B measurement. See docs/RETRIEVAL_EVAL.md.",
        )
    return 0


def write_template(path: pathlib.Path, seeds: list[str], rows: list[dict]) -> None:
    """Emit a judgment template with lexically-plausible candidates pre-listed.

    Deliberately NOT the current system's ranked output. Judging only what the
    system already returns makes recall improvements structurally unmeasurable —
    a document the system never surfaces can never be marked relevant, so
    finding it later scores as no gain. These candidates are a crude word-overlap
    pool, which is a different bias from the system's and therefore a useful one.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        fh.write("// Judgment template. Fill in `judgments`: 0 irrelevant, 1 related, 2 relevant, 3 ideal.\n")
        fh.write("// Set `class` to one of: lexical, synonym-only, deictic, fresh-decoy,\n")
        fh.write("// ambiguous-term, expect-empty. The `//` lines under each query are the\n")
        fh.write("// candidate pool, for reading while you judge; the loader ignores them.\n")
        fh.write("// Add ids the pool missed — it is word-overlap only and finds no synonyms.\n")
        for i, q in enumerate(seeds):
            terms = {w for w in re.findall(r"\w+", q.lower()) if len(w) > 3}
            scored = sorted(
                ((len(terms & set(re.findall(r"\w+", r["content"].lower()))), r) for r in rows),
                key=lambda t: t[0], reverse=True,
            )
            cands = [r["id"] for score, r in scored[:12] if score > 0]
            fh.write(json.dumps({
                "qid": f"q{i + 1:02d}", "class": "lexical", "query": q,
                "judgments": {c: 0 for c in cands[:6]},
            }, ensure_ascii=False) + "\n")
            for score, r in scored[:12]:
                if score > 0:
                    fh.write(f"//   {r['id']}: {r['content'][:90]}\n")


if __name__ == "__main__":
    raise SystemExit(main())
