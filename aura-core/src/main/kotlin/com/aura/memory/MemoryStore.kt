package com.aura.memory

import com.aura.provenance.ConversationProvenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

@Singleton
class MemoryStore @Inject constructor(
    private val dao: MemoryDao,
    private val embedder: Embedder,
    private val vectorIndex: VectorIndex,
    private val writeGate: WriteGate,
    private val memoryEditDao: MemoryEditDao,
    private val memoryFeedbackDao: MemoryFeedbackDao,
    private val reranker: MemoryReranker? = null,
    private val queryRewriter: QueryRewriter? = null,
    private val evolutionHooks: com.aura.evolution.EvolutionHooks? = null,
) {
    private val exactInsertMutex = Mutex()
    suspend fun maybeStore(
        content: String,
        source: String = "user",
        scope: String = "general",
        provenance: ConversationProvenance = ConversationProvenance(),
    ): String? = exactInsertMutex.withLock {
        val decision = writeGate.evaluate(content, source)
        if (!decision.shouldStore) return@withLock null
        // Dedup: skip if an identical memory already exists. This
        // prevents "I prefer dark mode" from being stored 3 times
        // across 3 conversations, which would waste recall slots
        // and skew the RRF ranking with duplicate hits.
        if (dao.existsByContent(content) > 0) return@withLock null
        val embedding = embedder.embed(content)

        // Semantic dedup: scan existing memories with embeddings for
        // cosine similarity > 0.92. This catches paraphrased versions
        // of the same fact ("I like dark mode" vs "I prefer dark
        // mode") that exact-match misses. When a match is found, we
        // merge — keep the richer (longer) version and re-null its
        // embedding so the next recall re-embeds with the updated text.
        val existing = runCatching { dao.allWithEmbeddings() }
            .onFailure { Log.w("MemoryStore", "allWithEmbeddings failed during dedup", it) }
            .getOrDefault(emptyList())
        if (existing.isNotEmpty()) {
            val match = existing.firstOrNull { mem ->
                mem.embedding?.let {
                    cosineSimilarity(embedding, Embedder.fromBytes(it)) > SEMANTIC_DEDUP_THRESHOLD
                } == true
            }
            if (match != null) {
                // Merge: keep the longer content (richer version of
                // the fact). If the new content is longer, replace
                // the existing memory's content and re-null the
                // embedding. If the existing is longer or same, skip.
                if (content.length > match.content.length) {
                    runCatching {
                        dao.update(match.copy(
                            content = content,
                            embedding = null,
                            accessedAt = System.currentTimeMillis(),
                        ))
                    }.onFailure { Log.w("MemoryStore", "dao.update during dedup merge failed", it) }
                }
                // Either way, we don't store a new memory.
                return@withLock null
            }
        }

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insert(
            MemoryEntity(
                id = id,
                content = content,
                source = source,
                category = decision.category,
                importance = decision.importance,
                embedding = Embedder.toBytes(embedding),
                createdAt = now,
                accessedAt = now,
                decayScore = 1.0f,
                sourceConversationId = provenance.conversationId,
                sourceTurnTimestamp = provenance.turnTimestamp,
                scope = scope,
            )
        )
        id
    }

    /**
     * Store a system/user marker at most once by exact content. The mutex
     * closes the in-process check-then-insert race, while the DAO lookup makes
     * the decision durable across ViewModel recreation and app restarts.
     */
    suspend fun storeIfAbsent(
        content: String,
        source: String,
        category: String,
        importance: Float,
        tags: List<String> = emptyList(),
        scope: String = "general",
    ): String? = exactInsertMutex.withLock {
        if (dao.existsByContent(content) > 0) return@withLock null
        store(content, source, category, importance, tags, scope)
    }

    suspend fun store(
        content: String,
        source: String,
        category: String,
        importance: Float,
        tags: List<String> = emptyList(),
        scope: String = "general",
        provenance: ConversationProvenance = ConversationProvenance(),
    ): String {
        val id = UUID.randomUUID().toString()
        val embedding = embedder.embed(content)
        val now = System.currentTimeMillis()
        dao.insert(
            MemoryEntity(
                id = id,
                content = content,
                source = source,
                category = category,
                importance = importance,
                embedding = Embedder.toBytes(embedding),
                embeddingModel = embedder.modelId(),
                embeddingVersion = embedder.dimension(),
                createdAt = now,
                accessedAt = now,
                decayScore = 1.0f,
                tags = tags.joinToString(","),
                sourceConversationId = provenance.conversationId,
                sourceTurnTimestamp = provenance.turnTimestamp,
                scope = scope,
            )
        )
        runCatching {
            evolutionHooks?.onMemoryStored(id, category, runId = null, provenance.conversationId, provenance.turnTimestamp)
        }.onFailure { Log.w("MemoryStore", "evolutionHooks.onMemoryStored failed (non-fatal)", it) }
        return id
    }

        /**
         * Options for [query]. Encapsulates the growing parameter list into
         * a single object so callers don't need to pass 6 positional args.
         */
        data class RecallOptions(
            val limit: Int = 5,
            val scopeFilter: Set<String>? = null,
            /** Model for cross-encoder reranking. Null = skip reranking. */
            val rerankModel: String? = null,
            /** Model for query rewriting. Null = skip rewriting. */
            val rewriteModel: String? = null,
            /** Recent conversation turns for deictic resolution. */
            val recentContext: String = "",
        )

        suspend fun query(
            text: String,
            options: RecallOptions = RecallOptions(),
        ): List<MemoryEntity> {
            val limit = options.limit
            val scopeFilter = options.scopeFilter
            val rerankModel = options.rerankModel
            val rewriteModel = options.rewriteModel
            val recentContext = options.recentContext
        // RRF fusion: text match + vector similarity + recency + access + decay + importance.
        // See [Retrieval.rankCandidates] for the RRF scoring details.
        // On hit, call [touch] to bump accessedAt + accessCount + decayScore. This
        // is what makes FadeMem meaningful — a frequently-recalled fact decays
        // slower. Without it, every memory decays at the same rate regardless of
        // how useful it actually is to the model.
        // Query rewriting: resolve deictic references ("that thing we
        // discussed" -> "the database migration strategy from Tuesday")
        // before BM25 + embedding. The rewritten query improves lexical
        // and vector recall. The original query is kept for the reranker,
        // which judges relevance against what the user actually said.
        val retrievalQuery = if (queryRewriter != null && rewriteModel != null && recentContext.isNotBlank()) {
            queryRewriter.rewrite(text, recentContext, rewriteModel)
        } else {
            text
        }

        val escapedText = escapeLikeWildcards(retrievalQuery)
        val scopes = scopeFilter?.toList() ?: listOf("general")
        // Split the query into individual words and search for any match.
        // A full-phrase LIKE (%programming languages i enjoy%) would match
        // almost nothing — individual word LIKEs (%kotlin% OR %love% OR %programming%)
        // catch any memory that shares at least one word with the query.
        val queryWords = retrievalQuery.lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.length > 2 }
            .take(6)
        val pad = List(6) { i -> if (i < queryWords.size) "%${escapeLikeWildcards(queryWords[i])}%" else "%%" }
        val textHits = if (queryWords.isNotEmpty()) {
            dao.searchByWordsInScopes(
                word1 = pad[0], word2 = pad[1], word3 = pad[2],
                word4 = pad[3], word5 = pad[4], word6 = pad[5],
                scopes = scopes, limit = limit * 3,
            )
        } else {
            dao.searchByTextInScopes("%$escapedText%", scopes, limit * 3)
        }
        val qVec = embedder.embed(retrievalQuery)

        if (textHits.isEmpty()) {
            // Vector fallback: no text overlap between query and any stored
            // memory in the scoped set. But the query might still be
            // semantically similar to a memory (e.g. query "programming
            // languages I enjoy" vs stored "I love Kotlin" — zero shared
            // words, but vectors are close). Scan all scoped memories with
            // embeddings and rank by cosine similarity.
            val all = dao.allByScopes(scopes).filter { it.embedding != null }
            if (all.isEmpty()) return emptyList()
            val scored = all.map { mem ->
                val embedding = Embedder.fromBytes(mem.embedding!!)
                ScoredMemory(memory = mem, textScore = 0f, vectorScore = cosineSimilarity(qVec, embedding))
            }.filter { it.vectorScore > 0.05f }
            if (scored.isEmpty()) return emptyList()
            val vectorResults = Retrieval.rankCandidates(text, qVec, scored, limit)
            // Route vector fallback through reranker too — it catches
            // semantic matches that BM25+vector both missed.
            val results = if (reranker != null && vectorResults.size >= RERANK_MIN_CANDIDATES && rerankModel != null) {
                reranker.rerank(text, vectorResults, rerankModel, topK = limit)
            } else {
                vectorResults.take(limit)
            }
            for (mem in results) { runCatching { touch(mem.id) }.onFailure { Log.w("MemoryStore", "inline touch in vector fallback failed", it) } }
            // P1 MEMORY B1: vector-fallback recall path
            // skipped evolutionHooks.onMemoryRecalled until
            // now. The main path (BM25+vector) at line ~289
            // calls it, so the EvolutionShadowEvaluator's
            // recall telemetry was half-wired — it saw
            // BM25-hits but never saw fallback hits. Now
            // both paths fire the same telemetry so the
            // Evolution engine can rank memories honestly.
            for ((index, mem) in results.withIndex()) {
                runCatching {
                    evolutionHooks?.onMemoryRecalled(mem.id, text, index + 1, null, null, null)
                }.onFailure { Log.w("MemoryStore", "evolutionHooks.onMemoryRecalled in vector fallback failed (non-fatal)", it) }
            }
            return results
        }

        // Build [ScoredMemory] candidates with text and vector similarity scores.
        // BM25 text scoring: build a BM25 index from the scoped text hits
        // and score each candidate against the query. This replaces the
        // naive term-overlap score with proper IDF-weighted BM25.
        val bm25 = if (textHits.isNotEmpty()) BM25(textHits.map { it.content }) else null
        val queryTokens = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val scoredCandidates = textHits.mapIndexed { idx, mem ->
            val embedding = mem.embedding?.let { Embedder.fromBytes(it) }

            // BM25 text score (normalized 0-1) or fallback to term overlap.
            val textScore = if (bm25 != null) {
                bm25.normalizedScore(text, idx)
            } else {
                val contentLower = mem.content.lowercase()
                val matchedTokens = queryTokens.count { it in contentLower }
                if (queryTokens.isNotEmpty()) matchedTokens.toFloat() / queryTokens.size else 0f
            }

            // Vector score: cosine similarity if embedding available, else 0.
            val vectorScore = if (embedding != null) cosineSimilarity(qVec, embedding) else 0f

            ScoredMemory(memory = mem, textScore = textScore, vectorScore = vectorScore)
        }

        // RRF ranking: overfetch to RERANK_POOL_SIZE, then let the
        // reranker (if available) pick the final topK from the pool.
        // Without a reranker, RRF returns topK directly.
        val rrfTopN = if (reranker != null) minOf(RERANK_POOL_SIZE, scoredCandidates.size) else limit
        val rrfResults = Retrieval.rankCandidates(
            query = text,
            queryEmbedding = qVec,
            candidates = scoredCandidates,
            topK = rrfTopN,
            now = System.currentTimeMillis(),
        )

        // Cross-encoder reranking: only worth the LLM calls when we have
        // enough candidates to justify it. With <5 candidates, RRF already
        // ranks them well and the reranker just adds latency + cost.
        val results = if (reranker != null && rrfResults.size >= RERANK_MIN_CANDIDATES && rerankModel != null) {
            reranker.rerank(text, rrfResults, rerankModel, topK = limit)
        } else {
            rrfResults.take(limit)
        }

        // Touch is fire-and-forget; we don't want a failed decay update to break recall.
        for ((index, mem) in results.withIndex()) {
            runCatching { touch(mem.id) }
                .onFailure { Log.w("MemoryStore", "touch on recall failed", it) }
            runCatching {
                evolutionHooks?.onMemoryRecalled(mem.id, text, index + 1, null, null, null)
            }.onFailure { Log.w("MemoryStore", "evolutionHooks.onMemoryRecalled failed (non-fatal)", it) }
        }
        return results
    }

    companion object {
        /** How many candidates RRF overfetches for the reranker pool. */
        const val RERANK_POOL_SIZE = 20
        /** Minimum candidates to justify reranker LLM calls. */
        const val RERANK_MIN_CANDIDATES = 5
    }

    /**
     * List memories filtered by category.
     * Room filter (no embedding or text matching) and does NOT touch the
     * returned memories — category browsing is metadata, not a recall.
     */
    suspend fun listByCategory(category: String, limit: Int = 50): List<MemoryEntity> =
        dao.byCategory(category, limit)

    /**
     * Fast text-only search via SQL LIKE. No embedding, no cloud
     * call, no RRF — just a substring match on content. Used by the
     * Memory screen's search bar for instant browsing. The semantic
     * [query] method is still available for the agentic loop's
     * recall, which needs the full RRF pipeline.
     */
    suspend fun searchByText(text: String, limit: Int = 50): List<MemoryEntity> {
        val escaped = escapeLikeWildcards(text)
        return dao.searchByText("%$escaped%", limit)
    }

    suspend fun recent(limit: Int = 20): List<MemoryEntity> = dao.recent(limit)

    /**
     * Memories created in the last [sinceMs] ms, newest first. Used
     * by the morning brief to surface "what you learned yesterday."
     * Bounded by [limit] so a freshly-imported backup with 500
     * new rows doesn't blow up the LLM prompt.
     */
    suspend fun recentSince(sinceMs: Long, limit: Int = 20): List<MemoryEntity> =
        dao.recentSince(sinceMs, limit)

    /**
     * Memories whose decayScore is at or below [threshold]. The
     * morning brief uses this for the "X memories are fading" line.
     * Most-faded first.
     */
    suspend fun decayedBelow(threshold: Float, limit: Int = 20): List<MemoryEntity> =
        dao.decayedBelow(threshold, limit)
    suspend fun byCategory(category: String, limit: Int = 20): List<MemoryEntity> = dao.byCategory(category, limit)
    suspend fun top(limit: Int = 20): List<MemoryEntity> = dao.top(limit)
    suspend fun get(id: String): MemoryEntity? = dao.getById(id)
    suspend fun forget(id: String) {
        dao.delete(id)
        runCatching { evolutionHooks?.onMemoryForgotten(id) }
            .onFailure { Log.w("MemoryStore", "evolutionHooks.onMemoryForgotten failed (non-fatal)", it) }
    }

    suspend fun recordFeedback(memoryId: String, kind: String, note: String = "") {
        val row = MemoryFeedbackEntity(
            id = java.util.UUID.randomUUID().toString(),
            memoryId = memoryId,
            kind = kind,
            note = note,
        )
        runCatching { memoryFeedbackDao.insert(row) }
            .onFailure { Log.w("MemoryStore", "memoryFeedbackDao.insert failed", it) }
    }

    suspend fun deleteBySource(source: String) = dao.deleteBySource(source)

    /**
     * Delete all memories. Irreversible. Used by the "Clear all"
     * action in the Memory screen.
     */
    suspend fun forgetAll() = dao.deleteAll()

    /**
     * Delete all memories in a given category. Irreversible. Used
     * by the "Clear category" action when the user wants to prune
     * a whole class (e.g. all "episode" memories that turned out
     * to be noise).
     */
    suspend fun forgetByCategory(category: String) {
        dao.deleteByCategory(category)
    }

    /**
     * Rename a category — updates all memories in [oldCategory] to
     * [newCategory]. Used by the Memory screen's category management.
     */
    suspend fun renameCategory(oldCategory: String, newCategory: String) {
        dao.updateCategory(oldCategory, newCategory)
    }

    /**
     * Merge [source] category into [target] — all memories in source
     * are moved to target. Source becomes empty.
     */
    suspend fun mergeCategories(source: String, target: String) {
        dao.updateCategory(source, target)
    }

    /**
     * Re-embed every memory that currently has a null embedding.
     * Returns the number of rows that were re-embedded.
     *
     * Use case: after a backup restore (which intentionally drops
     * embeddings — see [com.aura.backup.BackupManager.snapshot] for
     * why), every imported row has embedding=null. The next recall
     * would trigger a lazy re-embed per row, but that's slow when
     * there are hundreds. The Memory screen exposes this as a
     * "Rebuild embeddings" action so the user can do the sweep in
     * one pass.
     *
     * Memories with a non-null embedding are left alone. Embeddings
     * are model-specific so re-embedding with the current model is
     * safe and the result is what future recalls will use.
     *
     * Failures on individual rows are swallowed and logged; the
     * rebuild returns the count of successful re-embeds so the UI
     * can show "Rebuilt 142 of 145".
     */
    suspend fun rebuildEmbeddings(): Int {
        val pending = dao.allForExport().filter { it.embedding == null }
        if (pending.isEmpty()) return 0
        var rebuilt = 0
        // Batch in groups of 5 with parallel async to avoid sequential
        // cloud round-trips. Each embedding is an independent API call.
        pending.chunked(5).forEach { batch ->
            coroutineScope {
                batch.map { mem ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val vec = embedder.embed(mem.content)
                            dao.update(mem.copy(embedding = Embedder.toBytes(vec)))
                        }.onFailure { Log.w("MemoryStore", "rebuildEmbeddings: re-embed failed for memory ${mem.id}", it) }
                            .isSuccess
                    }
                }.awaitAll().forEach { ok -> if (ok) rebuilt += 1 }
            }
        }
        return rebuilt
    }

    /**
     * Update an existing memory's content + category. Used by the
     * Memory edit UI when the user fixes a fact the model got wrong.
     * The embedding is set to null — the next recall will trigger a
     * lazy re-embed, or the user can hit the Memory tab's
     * "Rebuild embeddings" action to re-embed every invalidated row
     * in one pass.
     *
     * If [id] does not exist this is a no-op (the user probably
     * deleted the row from another path between opening the edit
     * dialog and tapping Save). The refresh happens automatically
     * via [observeCount] in the calling VM.
     */
    suspend fun update(id: String, content: String, category: String, importance: Float = 0.5f, tags: String = "") {
        val existing = dao.getById(id) ?: return
        // Record the edit in the audit trail before applying it.
        runCatching {
            memoryEditDao.insert(
                MemoryEditEntity(
                    memoryId = id,
                    oldContent = existing.content,
                    newContent = content,
                    oldCategory = existing.category,
                    newCategory = category,
                    editedBy = "user",
                )
            )
        }.onFailure { Log.w("MemoryStore", "memoryEditDao.insert during update() failed (audit trail lost, main update still applied)", it) }
        dao.update(
            existing.copy(
                content = content,
                category = category,
                importance = importance,
                tags = tags,
                // Invalidate the embedding so the next recall re-embeds.
                embedding = null,
                // Bump accessedAt so a freshly-edited memory ranks higher
                // in the next recall.
                accessedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun touch(id: String) {
        dao.touch(id)
    }

    /**
     * Get the edit history for a memory. Returns entries newest-first.
     * Used by the Memory edit dialog to show what changed and when.
     */
    suspend fun getEditHistory(memoryId: String): List<MemoryEditEntity> {
        return runCatching { memoryEditDao.getForMemory(memoryId) }
            .onFailure { Log.w("MemoryStore", "getEditHistory for $memoryId failed", it) }
            .getOrDefault(emptyList())
    }

    /** Reinsert the exact deleted row and its CASCADE-deleted audit trail. */
    suspend fun restore(memory: MemoryEntity, edits: List<MemoryEditEntity> = emptyList()) {
        dao.insert(memory)
        if (edits.isNotEmpty()) memoryEditDao.insertAll(edits)
    }

    fun observeCount(): Flow<Int> = dao.count()
    suspend fun count(): Int = dao.countOnce()

    /**
     * Run decay pass: recompute the decay score for every memory.
     * Uses batch updates (50 per batch) to avoid N+1 individual
     * UPDATE statements.
     */
    suspend fun runDecayPass() {
        val now = System.currentTimeMillis()
        val all = dao.recent(10_000)  // hard cap; raise if needed
        val toUpdate = mutableListOf<MemoryEntity>()
        for (mem in all) {
            val newScore = FadeMem.compute(mem.createdAt, mem.accessedAt, now)
            if (kotlin.math.abs(newScore - mem.decayScore) > 0.05f) {
                toUpdate.add(mem.copy(decayScore = newScore))
            }
        }
        // Batch in chunks of 50 to keep each transaction small.
        toUpdate.chunked(50).forEach { batch ->
            dao.updateAll(batch)
        }
    }

    /**
     * Set the decay score for a single memory without touching
     * other fields or writing to the edit-audit trail. Used by
     * [DreamConsolidator] to mark stale memories as forgotten.
     */
    suspend fun updateDecayScore(id: String, decayScore: Float) {
        dao.updateDecayScore(id, decayScore)
    }
}

/** Fast cosine similarity between two same-dimension float arrays. */
private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) {
        android.util.Log.w("MemoryStore", "Embedding dimension mismatch: ${a.size} vs ${b.size} — scoring 0.0. Rebuild embeddings to fix.")
        return 0f
    }
    var dot = 0f
    var aNorm = 0f
    var bNorm = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        aNorm += a[i] * a[i]
        bNorm += b[i] * b[i]
    }
    val aN = sqrt(aNorm)
    val bN = sqrt(bNorm)
    if (aN == 0f || bN == 0f) return 0f
    return dot / (aN * bN)
}

/**
 * Escape SQL LIKE wildcards (% and _) so user queries containing these
 * characters are matched literally rather than acting as pattern metacharacters.
 * Must be kept in sync with the ESCAPE '\' clause in MemoryDao.searchByText.
 */
internal fun escapeLikeWildcards(s: String): String = s
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

/**
 * Cosine similarity threshold for semantic memory dedup. 0.92 is
 * conservative — it catches paraphrased versions of the same fact
 * ("I like dark mode" vs "I prefer dark mode") while allowing
 * related-but-distinct facts ("I prefer dark mode" vs "I prefer
 * light mode") to be stored separately.
 */
private const val SEMANTIC_DEDUP_THRESHOLD = 0.92f
