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

HARVEST_HEADER = [
    "// Harvested from real use by scripts/build_eval_corpus.py.",
    "// Grades are 0..3 and come from the consult pass and explicit corrections only.",
    "// Run scripts/pool_eval_queries.py to pool candidates and assign classes",
    "// before reading any Gate B verdict from these.",
]


def redact(text: str, counts: dict[str, int]) -> str:
    for name, pattern in PATTERNS:
        text, n = pattern.subn(f"[{name}]", text)
        counts[name] = counts.get(name, 0) + n
    return text


def write_harvested_queries(
    path: pathlib.Path,
    labels: list[dict],
    positional: dict[str, str],
    counts: dict[str, int],
) -> tuple[int, int, int]:
    """Turn harvested labels into `queries.jsonl` with real judgments.

    One query per turn. Memory UUIDs are mapped through the positional ids
    assigned while the corpus was written, in this same run — see `positional`.

    Only `grade` is exported, never `heuristicGrade`. Thumbs and regenerate are
    verdicts on the *answer*, and a verdict spread across every memory recalled
    for that turn produces rows that all grade alike; nDCG cannot separate those,
    so letting them through would move the metric with how often the user tapped
    thumbs-down rather than with retrieval quality. They stay in the database for
    the judge to calibrate against.
    """
    by_turn: dict[tuple[str, int], dict] = {}
    dropped_judgments = 0

    for row in labels:
        key = (str(row.get("conversationId", "")), int(row.get("turnTimestamp", 0)))
        entry = by_turn.setdefault(
            key, {"query": "", "judgments": {}, "cls": None, "graded": 0, "pool": []})
        if not entry["query"]:
            entry["query"] = redact(str(row.get("queryText", "")).strip(), counts)
        entry["cls"] = entry["cls"] or row.get("queryClass")

        memory_id = str(row.get("memoryId") or "")
        # rank 0 with a blank memory is the sentinel for "recall found nothing",
        # which is exactly an expect-empty query: it contributes no judgments.
        if not memory_id:
            continue
        mapped_any = positional.get(memory_id)
        if mapped_any is not None and mapped_any not in entry["pool"]:
            # Everything recall returned, graded or not.
            #
            # RetrievalMetrics treats an id with no judgment as grade 0, so a
            # memory returned at rank 1 that nobody happened to grade is scored
            # as irrelevant — the current ranker penalised for the results it got
            # right. These ids have to reach the judge, and they are carried on a
            # field the Kotlin harness ignores (EvalFixtures parses with
            # ignoreUnknownKeys) so `queries.jsonl` stays a valid EvalQuery.
            entry["pool"].append(mapped_any)

        grade = row.get("grade")
        if grade is None:
            continue
        # Counted BEFORE the mapping can drop it. Without this, a query whose
        # only graded memory has since been forgotten arrives here with an empty
        # judgments dict and is indistinguishable from a query that genuinely
        # expected nothing — so it would be exported as an expect-empty case and
        # inflate correctly_empty_rate for a reason unrelated to retrieval. That
        # is exactly what the first run of this function did.
        entry["graded"] += 1
        mapped = positional.get(memory_id)
        if mapped is None:
            # The memory was forgotten between the recall and this export.
            dropped_judgments += 1
            continue
        entry["judgments"][mapped] = int(grade)

    out = []
    dropped_queries = 0
    for i, ((_, _), entry) in enumerate(sorted(by_turn.items(), key=lambda kv: kv[1]["query"])):
        if not entry["query"]:
            continue
        had_any = any(v >= 1 for v in entry["judgments"].values())
        # A query that HAD grades and kept none is not an expect-empty case, it
        # is a casualty of a forgotten memory. A query that never had one is a
        # genuine expect-empty and stays. The difference is `graded`, counted
        # above, and it is the whole reason that counter exists.
        if entry["graded"] and not had_any:
            dropped_queries += 1
            continue
        out.append({
            "qid": f"q{i + 1:04d}",
            # Ignored by the Kotlin harness; consumed by pool_eval_queries.py.
            "pool": entry["pool"],
            # Assigned by the judge, which is the only thing that can tell a
            # synonym-only query from a lexical one. Unclassified until then.
            "class": entry["cls"] or "unclassified",
            "query": entry["query"],
            "judgments": entry["judgments"],
        })

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for line in HARVEST_HEADER:
            fh.write(line + chr(10))
        for r in out:
            fh.write(json.dumps(r, ensure_ascii=False) + chr(10))
    return len(out), dropped_judgments, dropped_queries


def self_test() -> int:
    """Exercise the selection and judgment rules against a synthetic backup.

    These rules are the ones that fail quietly. A labelled memory falling
    outside the corpus window, or a query losing its last positive to a
    forgotten memory, both produce a *plausible* eval file that measures the
    wrong thing — no error, just a number that moved for the wrong reason. The
    second of those got past a hand check on this function's first run.

    No pytest, no fixtures on disk, no new dependency: this repo gates its
    scripts from bash, and `python3 scripts/build_eval_corpus.py --self-test`
    is something the `gates` job can run.
    """
    import tempfile

    now = 1_700_000_000_000

    def mem(i, content, acc=1, retired=None):
        return {"id": f"uuid-{i}", "content": content, "category": "fact",
                "importance": 0.5, "createdAt": now - i * DAY_MS,
                "accessedAt": now - i * DAY_MS, "accessCount": acc,
                "decayScore": 1.0, "tags": "", "retiredAt": retired}

    def lab(conv, turn, mid, rank, grade=None, sup=False):
        return {"id": f"{conv}|{turn}|{mid}", "conversationId": conv,
                "turnTimestamp": turn, "queryText": f"question for {conv}",
                "memoryId": mid, "rank": rank, "grade": grade, "gradeSource": "",
                "heuristicGrade": 0, "signalsJson": "[]", "sampled": False,
                "judgedAt": None, "queryClass": None, "supersededByEdit": sup,
                "createdAt": now}

    backup = {
        "memories": [
            mem(1, "Elnur's favourite programming language is Kotlin", acc=9),
            mem(2, "The lantern had not been lit in forty years", acc=8),
            mem(3, "short", acc=7),
            mem(4, "A retired memory that must never reach the corpus", acc=99, retired=now),
            mem(5, "Filler memory about the weather in Baku last winter", acc=1),
        ],
        "retrievalLabels": [
            lab("c1", 1000, "uuid-1", 1, grade=3),
            lab("c1", 1000, "uuid-3", 2, grade=1),
            lab("c1", 1000, "uuid-2", 3),
            lab("c2", 2000, "", 0),
            lab("c3", 3000, "uuid-1", 1, grade=2, sup=True),
            lab("c4", 4000, "uuid-999", 1, grade=3),
        ],
    }

    failures: list[str] = []

    def check(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        (root / "backup.json").write_text(json.dumps(backup), encoding="utf-8")
        rc = main([str(root / "backup.json"), "--out", str(root / "corpus.jsonl"),
                   "--queries", str(root / "queries.jsonl")])
        check(rc == 0, f"the script exited {rc}")

        corpus = [json.loads(l) for l in (root / "corpus.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
        contents = [c["content"] for c in corpus]
        queries = [json.loads(l) for l in (root / "queries.jsonl").read_text(encoding="utf-8").splitlines()
                   if l.strip() and not l.startswith("//")]

        check(not any("retired" in c for c in contents),
              "a retired memory reached the corpus; production can never return it")
        check("short" in contents,
              "a labelled memory shorter than --min-chars was dropped, which would dangle its judgment")
        judged = {k for q in queries for k in q["judgments"]}
        corpus_ids = {c["id"] for c in corpus}
        check(judged <= corpus_ids,
              f"judgments reference ids not in the corpus: {sorted(judged - corpus_ids)}")
        check(len(queries) == 2,
              f"expected 2 queries (one real, one expect-empty), got {len(queries)}")
        empties = [q for q in queries if not q["judgments"]]
        check(len(empties) == 1,
              f"expected exactly 1 expect-empty query, got {len(empties)}. A query that LOST its "
              f"last positive to a forgotten memory must be dropped, not exported as expect-empty "
              f"— that inflates correctly_empty_rate for a reason unrelated to retrieval.")
        check(all(not q["judgments"] or max(q["judgments"].values()) >= 1 for q in queries),
              "a query kept judgments but no positive")

    for f in failures:
        print(f"FAIL: {f}", file=sys.stderr)
    print("self-test: " + ("PASS" if not failures else f"{len(failures)} failure(s)"))
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("backup", type=pathlib.Path, nargs="?",
                    help="Aura backup JSON (Settings -> Backup -> Export)")
    ap.add_argument("--out", type=pathlib.Path,
                    default=pathlib.Path("aura-core/src/test/resources/retrieval-eval/corpus.jsonl"))
    ap.add_argument("--limit", type=int, default=400,
                    help="documents to keep, most-accessed first (default 400)")
    ap.add_argument("--min-chars", type=int, default=20, help="skip memories shorter than this")
    ap.add_argument("--self-test", action="store_true",
                    help="run the built-in checks over a synthetic backup and exit")
    ap.add_argument("--no-labels", action="store_true",
                    help="ignore harvested retrievalLabels and use the --seed template path")
    ap.add_argument("--queries", type=pathlib.Path,
                    help="write queries.jsonl here. Uses harvested retrievalLabels from the backup "
                         "when present; falls back to a hand-judged TEMPLATE built from --seed")
    ap.add_argument("--seed", type=pathlib.Path,
                    help="newline-separated queries, for the template fallback when the backup "
                         "carries no harvested labels")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()
    if args.backup is None:
        ap.error("a backup path is required (or pass --self-test)")

    data = json.loads(args.backup.read_text(encoding="utf-8"))
    memories = data.get("memories") or data.get("memoryBackups") or []
    if not memories:
        print(f"No memories in {args.backup}. Keys present: {sorted(data)[:12]}", file=sys.stderr)
        return 1

    # Newest timestamp as "now", so ages come out RELATIVE. An absolute epoch
    # would make the corpus age between runs and drift recency and decay until
    # a suite that passed in August fails in December for no reason.
    now = max(int(m.get("createdAt", 0)) for m in memories)

    # Retired memories are excluded from every MemoryDao retrieval path, so a
    # corpus containing them is a corpus of documents production can never
    # return — and a retrieval change would be scored partly on reaching things
    # it is forbidden to reach.
    live = [m for m in memories if m.get("retiredAt") in (None, 0)]
    retired = len(memories) - len(live)

    labels = [] if args.no_labels else (data.get("retrievalLabels") or [])
    # Rows the user rewrote the question on say the *question* was wrong, not the
    # memories. Grading them would teach the eval the opposite of what happened.
    labels = [l for l in labels if not l.get("supersededByEdit")]
    labelled_ids = {str(l.get("memoryId")) for l in labels if l.get("memoryId")}

    # Selection is inverted: labelled memories are emitted FIRST and are exempt
    # from --min-chars, then the rest fill to --limit most-accessed first.
    #
    # The obvious order — filter, sort, truncate, then map judgments — drops a
    # labelled memory that falls outside the window, and both ways of handling
    # that are wrong. Keeping the judgment dangles an id and RetrievalEvalTest
    # goes red; dropping it silently strips a query's only relevant document and
    # turns it into an accidental expect-empty case, which inflates
    # correctly_empty_rate and depresses recall5 for reasons that have nothing to
    # do with retrieval and are invisible in the report. Pinning them here makes
    # every judgment resolve by construction rather than by validation.
    pinned = [m for m in live if str(m.get("id")) in labelled_ids]
    rest = [m for m in live
            if str(m.get("id")) not in labelled_ids
            and len(str(m.get("content", "")).strip()) >= args.min_chars]
    # Most-accessed first: if the corpus is truncated, keep the memories that
    # actually get recalled rather than an arbitrary slice.
    rest.sort(key=lambda m: (int(m.get("accessCount", 0)), float(m.get("decayScore", 0))), reverse=True)
    kept = pinned + rest[: max(0, args.limit - len(pinned))]

    counts: dict[str, int] = {}
    rows = []
    # Built here and consumed below, in the same pass, never written to disk. A
    # committed uuid -> m0001 table would leak exactly the identifiers the
    # positional ids exist to keep on the device, and so would any stable hash
    # of them. It is also why labels ride the backup rather than a second export:
    # joining two files produced at different instants is how a labelled memory
    # ends up missing from the corpus it is judged against.
    positional: dict[str, str] = {}
    for i, m in enumerate(kept):
        created = int(m.get("createdAt", now))
        accessed = int(m.get("accessedAt", created))
        positional[str(m.get("id"))] = f"m{i + 1:04d}"
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

    if retired:
        print(f"skipped {retired} retired memories (production can never return them)")

    if args.queries and labels:
        written, dropped_judgments, dropped_queries = write_harvested_queries(
            args.queries, labels, positional, counts,
        )
        print(f"{written} harvested queries -> {args.queries}")
        if dropped_judgments or dropped_queries:
            print(
                f"  dropped {dropped_judgments} judgment(s) whose memory is gone, and "
                f"{dropped_queries} quer(y/ies) that lost every positive with them.",
                file=sys.stderr,
            )
        print(
            "",
            "Next: pool and judge. These queries carry only the grades Aura could infer —",
            "the consult pass and your explicit corrections — over the memories it",
            "actually returned. Grading only what was returned makes recall improvements",
            "structurally unmeasurable, which is the one thing Gate B is trying to",
            "measure. Run scripts/pool_eval_queries.py before trusting a Gate B verdict.",
            sep=chr(10),
        )
    elif args.queries:
        if not args.seed:
            print("--queries needs --seed (a file of queries, one per line) when the backup "
                  "carries no harvested labels", file=sys.stderr)
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
