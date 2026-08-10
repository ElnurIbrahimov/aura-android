package com.aura.memory.eval

import kotlin.math.ln
import kotlin.math.pow

/**
 * Ranking metrics for the retrieval eval harness.
 *
 * Graded relevance throughout (0 = irrelevant, 3 = ideal) rather than binary.
 * nDCG needs grades to be worth anything, and the distinction the golden set
 * most needs to express — "this is the answer" versus "this is related and
 * would be acceptable filler" — is exactly what a binary label destroys.
 */
object RetrievalMetrics {

    /**
     * Normalised discounted cumulative gain at [k].
     *
     * `gain = 2^grade - 1`, `discount = 1 / log2(rank + 1)`. Ideal DCG comes
     * from the judged set sorted by grade, so a query whose relevant documents
     * were never retrieved scores 0 rather than being quietly excluded.
     *
     * Returns 0.0 when nothing is judged relevant — such a query says nothing
     * about ranking and must not be averaged in as a perfect score.
     */
    fun nDCG(ranked: List<String>, judgments: Map<String, Int>, k: Int): Double {
        val idcg = idcg(judgments, k)
        if (idcg == 0.0) return 0.0
        return dcg(ranked, judgments, k) / idcg
    }

    private fun dcg(ranked: List<String>, judgments: Map<String, Int>, k: Int): Double =
        ranked.take(k).withIndex().sumOf { (i, id) ->
            val grade = judgments[id] ?: 0
            if (grade <= 0) 0.0 else (2.0.pow(grade) - 1.0) / log2(i + 2.0)
        }

    private fun idcg(judgments: Map<String, Int>, k: Int): Double =
        judgments.values.filter { it > 0 }.sortedDescending().take(k)
            .withIndex()
            .sumOf { (i, grade) -> (2.0.pow(grade) - 1.0) / log2(i + 2.0) }

    /**
     * Fraction of relevant documents (grade >= 1) that appear in the top [k].
     *
     * Returns 0.0 when nothing is relevant, for the same reason as [nDCG]: a
     * query with no right answer cannot be scored as having found them all.
     */
    fun recallAt(ranked: List<String>, judgments: Map<String, Int>, k: Int): Double {
        val relevant = judgments.filterValues { it >= 1 }.keys
        if (relevant.isEmpty()) return 0.0
        return ranked.take(k).count { it in relevant }.toDouble() / relevant.size
    }

    /**
     * Reciprocal rank of the first STRONGLY relevant result (grade >= 2).
     *
     * Grade 2 rather than 1 on purpose: MRR answers "how fast does the user
     * reach the thing they wanted", and a merely-related hit at position 1 is
     * not that.
     */
    fun reciprocalRank(ranked: List<String>, judgments: Map<String, Int>): Double {
        val idx = ranked.indexOfFirst { (judgments[it] ?: 0) >= 2 }
        return if (idx < 0) 0.0 else 1.0 / (idx + 1)
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)
}

/** One query's outcome. */
data class QueryResult(
    val qid: String,
    val queryClass: String,
    val returned: List<String>,
    val judgments: Map<String, Int>,
    val elapsedMs: Long,
) {
    val ndcg5: Double get() = RetrievalMetrics.nDCG(returned, judgments, 5)
    val ndcg10: Double get() = RetrievalMetrics.nDCG(returned, judgments, 10)
    val recall1: Double get() = RetrievalMetrics.recallAt(returned, judgments, 1)
    val recall3: Double get() = RetrievalMetrics.recallAt(returned, judgments, 3)
    val recall5: Double get() = RetrievalMetrics.recallAt(returned, judgments, 5)
    val recall10: Double get() = RetrievalMetrics.recallAt(returned, judgments, 10)
    val mrr: Double get() = RetrievalMetrics.reciprocalRank(returned, judgments)

    /** True when this query legitimately expects nothing back. */
    val expectsNothing: Boolean get() = judgments.none { it.value >= 1 }
}

/**
 * Aggregate scores for one config over one query set.
 *
 * [zeroResultRate] is not a vanity metric. `MemoryStoreQueryTest` asserts that
 * certain queries return EMPTY, and that is real product behaviour — recall
 * inventing context for a question it has no answer to is worse than silence.
 * A greedier fusion improves nDCG while breaking it, so the two have to be
 * watched together.
 */
data class Scorecard(
    val label: String,
    val queryCount: Int,
    val ndcg5: Double,
    val ndcg10: Double,
    val recall1: Double,
    val recall3: Double,
    val recall5: Double,
    val recall10: Double,
    val mrr: Double,
    val zeroResultRate: Double,
    /** Of the queries that SHOULD return nothing, the fraction that did. */
    val correctlyEmptyRate: Double,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val embedCalls: Int,
    val byClass: Map<String, ClassScore>,
) {
    companion object {
        fun from(label: String, results: List<QueryResult>, embedCalls: Int): Scorecard {
            if (results.isEmpty()) {
                return Scorecard(label, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, emptyMap())
            }
            // Queries that expect nothing are excluded from the ranking means:
            // they score 0 on every one of them by construction, so averaging
            // them in would make "add more should-return-nothing cases" look
            // like a quality regression.
            val ranked = results.filterNot { it.expectsNothing }
            val expectEmpty = results.filter { it.expectsNothing }
            val latencies = results.map { it.elapsedMs }.sorted()
            fun mean(f: (QueryResult) -> Double) = if (ranked.isEmpty()) 0.0 else ranked.sumOf(f) / ranked.size

            return Scorecard(
                label = label,
                queryCount = results.size,
                ndcg5 = mean { it.ndcg5 },
                ndcg10 = mean { it.ndcg10 },
                recall1 = mean { it.recall1 },
                recall3 = mean { it.recall3 },
                recall5 = mean { it.recall5 },
                recall10 = mean { it.recall10 },
                mrr = mean { it.mrr },
                zeroResultRate = results.count { it.returned.isEmpty() }.toDouble() / results.size,
                correctlyEmptyRate = if (expectEmpty.isEmpty()) 1.0
                else expectEmpty.count { it.returned.isEmpty() }.toDouble() / expectEmpty.size,
                p50LatencyMs = latencies[latencies.size / 2],
                p95LatencyMs = latencies[minOf(latencies.size - 1, (latencies.size * 95) / 100)],
                embedCalls = embedCalls,
                byClass = results.groupBy { it.queryClass }.mapValues { (_, rs) ->
                    val r = rs.filterNot { it.expectsNothing }
                    ClassScore(
                        count = rs.size,
                        ndcg10 = if (r.isEmpty()) 0.0 else r.sumOf { it.ndcg10 } / r.size,
                        recall5 = if (r.isEmpty()) 0.0 else r.sumOf { it.recall5 } / r.size,
                    )
                },
            )
        }
    }

    /**
     * Markdown, written on every run whether or not the assertions pass.
     *
     * The per-class table is the point. An aggregate nDCG hides that
     * synonym-only went from 0.10 to 0.60 while everything else stayed flat —
     * and that single number is the entire business case for an on-device
     * embedding model.
     */
    fun toMarkdown(): String = buildString {
        appendLine("## $label")
        appendLine()
        appendLine("| metric | value |")
        appendLine("|---|---|")
        appendLine("| queries | $queryCount |")
        appendLine("| nDCG@5 | ${"%.4f".format(ndcg5)} |")
        appendLine("| nDCG@10 | ${"%.4f".format(ndcg10)} |")
        appendLine("| recall@1 | ${"%.4f".format(recall1)} |")
        appendLine("| recall@3 | ${"%.4f".format(recall3)} |")
        appendLine("| recall@5 | ${"%.4f".format(recall5)} |")
        appendLine("| recall@10 | ${"%.4f".format(recall10)} |")
        appendLine("| MRR | ${"%.4f".format(mrr)} |")
        appendLine("| zero-result rate | ${"%.4f".format(zeroResultRate)} |")
        appendLine("| correctly empty | ${"%.4f".format(correctlyEmptyRate)} |")
        appendLine("| p50 latency (ms) | $p50LatencyMs |")
        appendLine("| p95 latency (ms) | $p95LatencyMs |")
        appendLine("| embed calls | $embedCalls |")
        appendLine()
        appendLine("### By query class")
        appendLine()
        appendLine("| class | n | nDCG@10 | recall@5 |")
        appendLine("|---|---|---|---|")
        byClass.toSortedMap().forEach { (name, s) ->
            appendLine("| $name | ${s.count} | ${"%.4f".format(s.ndcg10)} | ${"%.4f".format(s.recall5)} |")
        }
    }
}

data class ClassScore(val count: Int, val ndcg10: Double, val recall5: Double)
