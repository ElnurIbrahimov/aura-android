# Retrieval eval harness

Measures whether a change to recall made it better or worse. Before this
existed, the whole retrieval stack — six-signal RRF fusion, corpus-weighted
BM25, query rewriting, a reranker, FadeMem decay — was tuned entirely by
intuition, and there was no way to tell whether any component helped.

**Location:** `aura-core/src/test/kotlin/com/aura/memory/eval/`
**Fixtures:** `aura-core/src/test/resources/retrieval-eval/`
**Report:** `aura-core/build/reports/retrieval-eval/scorecard.md`, written on
every run whether or not the assertions pass.

```bash
./gradlew :aura-core:testDebugUnitTest --tests "com.aura.memory.eval.*"
```

## The shipped fixtures are a scaffold

`corpus.jsonl` and `queries.jsonl` are synthetic. **Their absolute scores mean
nothing.** Synthetic corpora have uniform style and no natural
vocabulary-overlap structure, so almost any change to retrieval scores as an
improvement against them. They exist so the harness runs and its own tests are
not vacuous.

What the scaffold *does* give you is regression detection: a change that moves
the numbers changed retrieval, and that is worth knowing even when the absolute
value is meaningless.

## Making it real

### 1. Export a corpus

The corpus should be **your actual memories, redacted** — not hand-written
examples. Real memories have the vocabulary overlap, the near-duplicates and
the uneven lengths that decide whether a retrieval change helps.

Use the app's backup export, or `MemoryDao.allForExport()`, and convert to
`corpus.jsonl`:

```json
{"id":"m001","content":"...","category":"fact","created_days_ago":40,"accessed_days_ago":12,"access_count":3,"decay_score":0.45,"importance":0.7,"tags":""}
```

Ages are **relative days, never absolute epoch millis** — an absolute timestamp
makes the corpus age between runs and drifts the recency and decay signals until
a suite that passed in August fails in December for reasons unrelated to
retrieval.

Target 300–500 documents.

### 2. Write queries

Target **60–80**. Below roughly 50, nDCG@10 noise is ±0.05 and a 3% improvement
is indistinguishable from luck.

Judgments are **graded**, not binary: 0 irrelevant, 1 related, 2 relevant, 3
ideal. nDCG needs grades, and the distinction the set most needs to express —
"this is the answer" versus "this is related and would be acceptable filler" —
is exactly what a binary label destroys.

The classes are deliberately adversarial. Each names a known weakness:

| class | what it probes |
|---|---|
| `lexical` | plain term overlap — the control |
| `synonym-only` | **zero** lexical overlap. The only class a hash-based embedder cannot serve, so its share and its score are the entire business case for a real on-device embedding model |
| `deictic` | "that thing we discussed" — exercises query rewriting |
| `fresh-decoy` | one old relevant row against a wall of fresh irrelevant ones; measures the metadata-vs-relevance imbalance directly |
| `ambiguous-term` | the answer is old **and** low-importance |
| `expect-empty` | feeds the zero-result metric |

### 3. Judge by pooling, not by inspection

Run the current system, a lexical-only config and a vector-only config at k=20,
union the three result sets, and judge that union.

Judging only what the current system returns makes recall improvements
**structurally unmeasurable** — a document the current system never retrieves
gets no grade, so retrieving it later scores as no gain.

### 4. Regenerate the baseline

Run the suite, read `scorecard.md`, and copy the current-config figures into
`baseline.json` with a `label` and a `note` saying what the corpus is.

## How the gate works

**No-regression against a committed baseline, never an absolute threshold.**
Absolute thresholds get quietly lowered under deadline pressure and stop meaning
anything. A baseline diff is a reviewed one-file change that says plainly "this
commit moved retrieval quality by this much".

A missing `baseline.json` **fails**. It does not skip — a gate that reports OK
over absent data is the exact defect `ENGINEERING_HISTORY.md` records finding in
four separate source-scanning tests.

Tolerance is 0.005 rather than zero: BM25 is float arithmetic and tie order can
legitimately flip across JDK versions.

## Exactly four test methods

`scripts/check-test-count.sh` gates the "N unit tests" string in `README.md` and
`architecture.md` against the JUnit XML. One `@Test` per golden query would force
a two-document edit every time a query is added, so all four methods iterate the
fixtures internally. **Fixtures grow; the test count does not.** Keep it that
way.

## Gate B: does a real embedding model buy anything?

The on-device ONNX embedder is five to ten days of work, a WordPiece tokenizer
port, and 33 MB. Gate B decides whether it is worth any of that, for the cost of
one Python script.

```bash
pip install sentence-transformers
python scripts/gen_eval_vectors.py
./gradlew :aura-core:testDebugUnitTest --tests '*RetrievalEvalTest*'
cat aura-core/build/reports/retrieval-eval/scorecard.md
```

The generator writes `vectors-<model>.jsonl` beside the fixtures — a vector per
corpus document and per query, for `gte-small`, `bge-small-en-v1.5` and
`nomic-embed-text`. `PrecomputedEmbedder` serves them by exact text lookup, so
the harness scores real semantic vectors without an inference runtime existing.
The files are gitignored: they are derived, they are large, and a stale one
silently measures the wrong model.

**Read one row: `synonym-only`.** Every other query class has lexical overlap
that BM25 already handles. Synonym-only is the class where a hash cannot work and
a semantic model must, so it is the only class whose movement is evidence about
ONNX. The bars — set before the numbers existed, which is the only moment a bar
means anything — are **≥15% of real queries** and **≥0.15 nDCG@10 on that class**
from `local-hash-v2` to `gte-small`. The report computes the verdict itself.

Also read the `nomic-embed-text` row. It is the ceiling: on-device will not beat
the cloud model the Ollama path already buys. A small gap between `gte-small` and
`nomic-embed-text` means ONNX is close to the best available; a large one means
it is buying the cheap half of the improvement.

Two ways to get a wrong answer here:

- **Running it against the scaffold.** The synthetic corpus has no natural
  synonymy, so nothing can find any. The report detects this and returns
  *inconclusive* regardless of how good the numbers look — a real-looking gain on
  fake data is the failure mode most likely to be believed.
- **A missing vector.** Handled by failing loudly. A hash fallback on a lookup
  miss would score the fixture and report it as the model's number.

When no vector file is present the report says **"Not run"** in words. It does not
omit the section: a decision this size should not be made against evidence that
is merely absent rather than visibly absent.

## Known limitation: `expect-empty` needs a real embedder

`correctly_empty_rate` is 0.0 in the scaffold baseline, and that is a finding
rather than an oversight.

The vector-fallback branch admits any candidate above 0.05 cosine. Once rows
carry embeddings, a query with no lexical match still returns whatever clears
that floor. `MemoryStoreQueryTest`'s "returns empty, not fresh rows" cases pass
only because their rows have **no embedding at all** — which is not true of
anything stored through `MemoryStore.store`.

So the product's "say nothing rather than invent context" guarantee is weaker in
production than those tests suggest. Worth measuring against a real embedder
before deciding whether the 0.05 floor is right.

Do not raise the metric by loosening it.
