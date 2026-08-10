package com.aura.memory.eval

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.Embedder
import com.aura.memory.FakeEmbedder
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
    ): Scorecard = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        try {
            val embedder = embedderFactory()
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

            Scorecard.from(label, results, embedCalls = (embedder as? FakeEmbedder)?.callCount?.get() ?: 0)
        } finally {
            db.close()
        }
    }

    /**
     * Write a scorecard set to the build reports directory.
     *
     * Written on EVERY run, pass or fail. A report that only appears when the
     * assertions succeed is useless for the case it is most needed in.
     */
    fun writeReport(cards: List<Scorecard>, baseline: EvalBaseline?) {
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
    }
}
