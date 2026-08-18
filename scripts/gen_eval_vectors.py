#!/usr/bin/env python3
"""Generate precomputed embeddings for the Gate B retrieval experiment.

Gate B answers one question before anyone spends a week on an on-device ONNX
runtime: **how much would a real embedding model actually buy this system?**

The answer is the synonym-only query class. Every other class has lexical
overlap that BM25 already handles; synonym-only is the one where a hash cannot
possibly work and a semantic model must. If that class is a small share of real
queries, or if it barely moves from local-hash-v2 to gte-small, the ONNX phase
is buying very little and should not be built.

    pip install sentence-transformers einops
    python scripts/gen_eval_vectors.py

`einops` is not optional and is not pulled in by sentence-transformers. It is
required by `nomic-embed-text-v1.5`'s remote modeling code, which loads under
`trust_remote_code=True` — so the failure arrives two models in, *after*
gte-small and bge-small have already written their files, as an ImportError from
inside transformers rather than anything this script can see coming. The run
looks half-finished and the cause is three stack frames deep in a dependency.

Writes aura-core/src/test/resources/retrieval-eval/vectors-<model>.jsonl, then:

    ./gradlew :aura-core:testDebugUnitTest --tests '*RetrievalEvalTest*'
    cat aura-core/build/reports/retrieval-eval/scorecard.md

The output files are gitignored on purpose: they are derived from corpus.jsonl,
they are megabytes of floats, and a stale one silently measures the wrong model.

IMPORTANT: the numbers only mean something on a corpus of REAL memories. The
shipped scaffold is synthetic, and synthetic corpora have no natural synonymy to
find, so every model scores about the same against it and the experiment reads
as "a semantic model buys nothing" no matter what is true. Export and redact a
real memory database into corpus.jsonl first.
"""

from __future__ import annotations

import json
import pathlib
import sys

# Model id -> HuggingFace name. The Kotlin side looks for these exact ids in
# vectors-<id>.jsonl, so keep them in sync with PrecomputedEmbedder.GATE_B_MODELS.
MODELS = {
    "gte-small": "thenlper/gte-small",                        # what ONNX would ship: 384-dim, 33M params
    "bge-small-en-v1.5": "BAAI/bge-small-en-v1.5",            # same size; needs a query prefix, hence the asymmetry below
    "nomic-embed-text": "nomic-ai/nomic-embed-text-v1.5",     # the ceiling: what the existing cloud path already buys
}

# Models trained with asymmetric encoding. Getting this wrong does not error —
# it just quietly costs a few points of nDCG and makes the model look worse than
# it is, which would be a bad reason to cancel the ONNX phase.
QUERY_PREFIX = {
    "bge-small-en-v1.5": "Represent this sentence for searching relevant passages: ",
    "nomic-embed-text": "search_query: ",
}
DOC_PREFIX = {
    "nomic-embed-text": "search_document: ",
}

ROOT = pathlib.Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "aura-core/src/test/resources/retrieval-eval"


def read_jsonl(path: pathlib.Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("//"):
            rows.append(json.loads(line))
    return rows


def main() -> int:
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError:
        print("pip install sentence-transformers", file=sys.stderr)
        return 1

    corpus = read_jsonl(FIXTURES / "corpus.jsonl")
    queries = read_jsonl(FIXTURES / "queries.jsonl")

    if any("scaffold" in row.get("tags", "") for row in corpus):
        print(
            "WARNING: corpus.jsonl is still the synthetic scaffold. It has no natural synonymy,\n"
            "         so every model will score about the same and the experiment will read as\n"
            "         'a semantic model buys nothing' regardless of the truth. Replace it with a\n"
            "         redacted export of a real memory database before believing any number.\n",
            file=sys.stderr,
        )

    # Both sides of every comparison the harness makes: MemoryStore embeds the
    # query text, and a query with no vector aborts the run by design.
    doc_texts = sorted({row["content"].strip() for row in corpus})
    query_texts = sorted({row["query"].strip() for row in queries})
    print(f"{len(doc_texts)} documents, {len(query_texts)} queries")

    for model_id, hf_name in MODELS.items():
        print(f"\n{model_id}  ({hf_name})")
        model = SentenceTransformer(hf_name, trust_remote_code=True)

        out = FIXTURES / f"vectors-{model_id}.jsonl"
        with out.open("w", encoding="utf-8") as fh:
            for texts, prefix in (
                (doc_texts, DOC_PREFIX.get(model_id, "")),
                (query_texts, QUERY_PREFIX.get(model_id, "")),
            ):
                if not texts:
                    continue
                vecs = model.encode(
                    [prefix + t for t in texts],
                    normalize_embeddings=True,
                    show_progress_bar=True,
                    batch_size=32,
                )
                for text, vec in zip(texts, vecs):
                    # Key on the BARE text: the prefix is an encoding detail the
                    # Kotlin side neither knows nor should know about.
                    fh.write(json.dumps({"text": text, "vector": [round(float(x), 6) for x in vec]}) + "\n")

        print(f"  -> {out.relative_to(ROOT)}  ({out.stat().st_size / 1e6:.1f} MB)")

    print(
        "\nNow run:\n"
        "  ./gradlew :aura-core:testDebugUnitTest --tests '*RetrievalEvalTest*'\n"
        "  cat aura-core/build/reports/retrieval-eval/scorecard.md\n\n"
        "Read the synonym-only row. Proceed to ONNX only if that class is >=15% of real\n"
        "queries AND gains >=0.15 nDCG@10 from local-hash-v2 to gte-small.",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
