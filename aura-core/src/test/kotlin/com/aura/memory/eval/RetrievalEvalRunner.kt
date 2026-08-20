package com.aura.memory.eval

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.CountingEmbedder
import com.aura.memory.Embedder
import com.aura.memory.FakeEmbedder
import com.aura.memory.LocalEmbedder
import com.aura.memory.MemoryDatabase
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryFtsSchema
import com.aura.memory.MemoryStore
import com.aura.memory.RetrievalConfig
import com.aura.memory.WriteGate
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Runs the golden query set against a real Room database and scores the result.
 *
 * Built on the `MemoryStoreQueryTest` scaffold — an in-memory `MemoryDatabase`
 * with [MemoryFtsSchema.triggerCallback] attached, because Room creates the FTS
 * virtual table but not the triggers that fill it, and without them the index is
 * empty and every lexical result is silently zero.
 *
 * Deliberately in `:aura-core`'s test source set rather than a separate Gradle
 * module. `MemoryFtsSchema` is `internal`, so a module would have to widen its
 * visibility or duplicate the DDL — and preventing exactly that duplication is
 * why the file exists. In-module also costs zero build-file and zero CI changes.
 */
class RetrievalEvalRunner(
    private val embedderFactory: () -> Embedder = { FakeEmbedder(384) },
) {

    /**
     * Score one config.
     *
     * A fresh database per run: `touch` is disabled for eval, but `store` and
     * the dedup path still write, and a config sweep that shared state between
     * runs would compare the second config against a corpus the first one had
     * altered.
     */
    fun run(
        label: String,
        config: RetrievalConfig,
        corpus: List<EvalMemory> = EvalFixtures.corpus(),
        queries: List<EvalQuery> = EvalFixtures.queries(),
        limit: Int = 10,
        withEmbedder: () -> Embedder = embedderFactory,
    ): Scorecard = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        try {
            val embedder = withEmbedder()
            // A fixed `now`, so relative ages resolve to the same instants for
            // every query in the run and for every run of the suite.
            val now = FIXED_NOW
            val dao = db.memoryDao()
            corpus.forEach { m ->
                dao.insert(
                    MemoryEntity(
                        id = m.id,
                        content = m.content,
                        source = "user",
                        category = m.category,
                        scope = m.scope,
                        importance = m.importance,
                        embedding = Embedder.toBytes(embedder.embed(m.content)),
                        embeddingModel = embedder.modelId(),
                        embeddingVersion = embedder.dimension(),
                        createdAt = now - m.createdDaysAgo * DAY_MS,
                        accessedAt = now - m.accessedDaysAgo * DAY_MS,
                        accessCount = m.accessCount,
                        decayScore = m.decayScore,
                        tags = m.tags,
                    ),
                )
            }

            // touchOnRecall forced off regardless of what the caller passed:
            // `touch` mutates accessedAt/accessCount/decayScore, all of which
            // feed fusion, so query N+1 would see a corpus altered by query N
            // and the whole run would depend on query order.
            val evalConfig = config.copy(touchOnRecall = false)
            val store = MemoryStore(
                dao,
                embedder,
                WriteGate(),
                db.memoryEditDao(),
                db.memoryFeedbackDao(),
                null,
                null,
                null,
                evalConfig,
            )

            val results = queries.map { q ->
                val started = System.nanoTime()
                val hits = store.query(
                    q.query,
                    MemoryStore.RecallOptions(
                        limit = limit,
                        scopeFilter = setOf(q.scope),
                        recentContext = q.recentContext,
                    ),
                )
                val elapsed = (System.nanoTime() - started) / 1_000_000
                QueryResult(
                    qid = q.qid,
                    queryClass = q.queryClass,
                    returned = hits.map { it.id },
                    judgments = q.judgments,
                    elapsedMs = elapsed,
                )
            }

            Scorecard.from(label, results, embedCalls = (embedder as? CountingEmbedder)?.callCount?.get() ?: 0)
        } finally {
            db.close()
        }
    }

    /**
     * The Gate B experiment: how much would a real embedding model actually buy?
     *
     * Scores the same golden set once per available `vectors-*.jsonl`, against a
     * `local-hash-v2` floor — what every user without an Ollama key gets today.
     * The whole point is to answer the ONNX question for the cost of one Python
     * script, before five to ten days go into a runtime and a WordPiece port.
     *
     * Returns an empty card list when no vector files are present, which is the
     * normal case in CI. The caller must then say so in the report: "did not
     * run" and "ran and found nothing" are opposite results, and a section that
     * quietly disappears reads as the second.
     */
    fun gateB(config: RetrievalConfig = RetrievalConfig.DEFAULT): List<Scorecard> {
        val models = PrecomputedEmbedder.available(PrecomputedEmbedder.GATE_B_MODELS)
        if (models.isEmpty()) return emptyList()

        // The floor is the real production fallback, not FakeEmbedder — the
        // question is what a semantic model buys over the HASH that ships today,
        // and a different hash would answer a question nobody asked.
        val cards = mutableListOf(
            run("local-hash-v2 (today's floor)", config, withEmbedder = { LocalEmbedder() }),
        )
        models.forEach { id ->
            cards += run(id, config, withEmbedder = { PrecomputedEmbedder.loadOrNull(id)!! })
        }
        return cards
    }

    /**
     * Write a scorecard set to the build reports directory.
     *
     * Written on EVERY run, pass or fail. A report that only appears when the
     * assertions succeed is useless for the case it is most needed in.
     */
    fun writeReport(cards: List<Scorecard>, baseline: EvalBaseline?, gateB: List<Scorecard> = emptyList()) {
        val dir = File(reportDir())
        dir.mkdirs()
        val out = File(dir, "scorecard.md")
        out.writeText(
            buildString {
                appendLine("# Retrieval eval")
                appendLine()
                if (EvalFixtures.isScaffold()) {
                    appendLine(
                        "> **These numbers are from the synthetic scaffold corpus and mean nothing.** " +
                            "Synthetic corpora have uniform style and no natural vocabulary-overlap " +
                            "structure, so every retrieval change scores as an improvement against " +
                            "them. Replace `corpus.jsonl` and `queries.jsonl` with a redacted export " +
                            "of a real memory database before reading any absolute score.",
                    )
                    appendLine()
                }
                baseline?.let {
                    appendLine("Baseline `${it.label}`: nDCG@10 ${"%.4f".format(it.ndcg10)} (tolerance ${it.tolerance})")
                    appendLine()
                }
                cards.forEach { appendLine(it.toMarkdown()); appendLine() }
                if (cards.size > 1) {
                    appendLine("## Deltas vs `${cards.first().label}`")
                    appendLine()
                    appendLine("| config | ΔnDCG@10 | ΔnDCG@5 | Δrecall@5 | ΔMRR | Δzero-result |")
                    appendLine("|---|---|---|---|---|---|")
                    val base = cards.first()
                    cards.drop(1).forEach { c ->
                        appendLine(
                            "| ${c.label} | ${delta(c.ndcg10 - base.ndcg10)} | ${delta(c.ndcg5 - base.ndcg5)} | " +
                                "${delta(c.recall5 - base.recall5)} | ${delta(c.mrr - base.mrr)} | " +
                                "${delta(c.zeroResultRate - base.zeroResultRate)} |",
                        )
                    }
                }
                appendLine()
                append(gateBSection(gateB))
            },
        )
    }

    /**
     * The Gate B verdict, or a plain statement that it did not run.
     *
     * Always emitted. An absent section reads as "nothing to report", which is
     * the opposite of the truth when the experiment has never been run — and the
     * decision it feeds (five to ten days of ONNX work) is exactly the kind that
     * gets made by default when the evidence is merely missing rather than
     * visibly missing.
     */
    private fun gateBSection(cards: List<Scorecard>): String = buildString {
        appendLine("## Gate B — what a real embedding model would buy")
        appendLine()
        if (cards.isEmpty()) {
            appendLine(
                "**Not run.** No `vectors-*.jsonl` on the test classpath. Generate them with " +
                    "`python scripts/gen_eval_vectors.py` (needs `sentence-transformers`), then re-run " +
                    "this suite. Until then the ONNX embedding phase has no evidence either way — " +
                    "see `docs/RETRIEVAL_EVAL.md`.",
            )
            return@buildString
        }
        val floor = cards.first()
        // correctly-empty is in this table because a config can buy nDCG by returning more,
        // and returning more is exactly how "nothing is relevant" stops being expressible.
        // Reading the two apart is the only way to tell a better ranking from a looser one.
        appendLine(
            "| model | nDCG@10 | Δ vs floor | synonym-only nDCG@10 | Δ synonym-only | correctly empty |",
        )
        appendLine("|---|---|---|---|---|---|")
        cards.forEach { c ->
            val syn = c.byClass[SYNONYM_CLASS]?.ndcg10
            val synFloor = floor.byClass[SYNONYM_CLASS]?.ndcg10
            appendLine(
                "| ${c.label} | ${"%.4f".format(c.ndcg10)} | ${delta(c.ndcg10 - floor.ndcg10)} | " +
                    "${syn?.let { "%.4f".format(it) } ?: "—"} | " +
                    "${if (syn != null && synFloor != null) delta(syn - synFloor) else "—"} | " +
                    "${"%.4f".format(c.correctlyEmptyRate)} |",
            )
        }
        appendLine()

        // The verdict is computed rather than left to the reader, because the
        // bar was set before the numbers existed and that is the only moment at
        // which a bar means anything.
        val share = floor.byClass[SYNONYM_CLASS]?.let { it.count.toDouble() / floor.queryCount } ?: 0.0
        val gain = cards.drop(1).mapNotNull { c ->
            val s = c.byClass[SYNONYM_CLASS]?.ndcg10 ?: return@mapNotNull null
            val f = floor.byClass[SYNONYM_CLASS]?.ndcg10 ?: return@mapNotNull null
            s - f
        }.maxOrNull() ?: 0.0
        appendLine(
            "Synonym-only is ${"%.0f".format(share * 100)}% of the query set; best gain over the " +
                "hash floor is ${delta(gain)} nDCG@10 on that class.",
        )
        appendLine()
        appendLine(
            when {
                EvalFixtures.isScaffold() ->
                    "**Inconclusive — this is the synthetic scaffold.** It has no natural synonymy " +
                        "to find, so a semantic model cannot show a gain here no matter what is true " +
                        "of the real corpus. This says nothing about ONNX."
                share >= 0.15 && gain >= 0.15 ->
                    "**Proceed.** Both bars cleared (>=15% of queries, >=0.15 nDCG@10 on the class)."
                else ->
                    "**Do not proceed.** The bars were >=15% of queries and >=0.15 nDCG@10 on the " +
                        "synonym-only class. On this corpus an on-device embedder would cost five to " +
                        "ten days and 33 MB to buy what is measured above."
            },
        )
    }

    private fun delta(d: Double): String = (if (d >= 0) "+" else "") + "%.4f".format(d)

    private fun reportDir(): String {
        // Tests run with the module dir as cwd in some invocations and the repo
        // root in others; both are handled the same way SourceScan does it.
        val local = File("build/reports/retrieval-eval")
        if (File("build").isDirectory) return local.path
        return File("aura-core/build/reports/retrieval-eval").path
    }

    companion object {
        /**
         * A fixed instant so relative corpus ages resolve identically on every
         * run. The value is arbitrary; that it never changes is the point.
         */
        const val FIXED_NOW = 1_760_000_000_000L
        const val DAY_MS = 86_400_000L

        /**
         * The query class Gate B turns on.
         *
         * Every other class has lexical overlap that BM25 already handles.
         * Synonym-only is the one where a hash cannot work and a semantic model
         * must, so it is the only class whose movement is evidence about ONNX.
         * Must match the `class` field in `queries.jsonl`.
         */
        const val SYNONYM_CLASS = "synonym-only"
    }
}
