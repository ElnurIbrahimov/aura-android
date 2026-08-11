package com.aura.memory

import com.aura.provenance.ConversationProvenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

@Singleton
class MemoryStore @Inject constructor(
    private val dao: MemoryDao,
    private val embedder: Embedder,
    private val writeGate: WriteGate,
    private val memoryEditDao: MemoryEditDao,
    private val memoryFeedbackDao: MemoryFeedbackDao,
    private val reranker: MemoryReranker? = null,
    private val queryRewriter: QueryRewriter? = null,
    private val evolutionHooks: com.aura.evolution.EvolutionHooks? = null,
    /**
     * Every retrieval tunable. Appended LAST on purpose: existing tests build
     * this positionally with five arguments and rely on defaults for the rest,
     * so a parameter inserted anywhere else would break all of them.
     */
    private val config: RetrievalConfig = RetrievalConfig.DEFAULT,
) {
    private val exactInsertMutex = Mutex()

    /**
     * Gate + dedup + store. When [category]/[importance] are provided
     * (e.g. the agentic loop's LLM write gate already decided), the
     * internal heuristic gate is skipped and only dedup runs — this is
     * how the loop's auto-store gets exact-match + semantic dedup
     * without re-litigating the store decision.
     */
    suspend fun maybeStore(
        content: String,
        source: String = "user",
        scope: String = "general",
        provenance: ConversationProvenance = ConversationProvenance(),
        category: String? = null,
        importance: Float? = null,
    ): String? = exactInsertMutex.withLock {
        val resolvedCategory: String
        val resolvedImportance: Float
        if (category != null && importance != null) {
            resolvedCategory = category
            resolvedImportance = importance
        } else {
            val decision = writeGate.evaluate(content, source)
            if (!decision.shouldStore) return@withLock null
            resolvedCategory = decision.category
            resolvedImportance = decision.importance
        }
        // Dedup: skip if an identical memory already exists. This
        // prevents "I prefer dark mode" from being stored 3 times
        // across 3 conversations, which would waste recall slots
        // and skew the RRF ranking with duplicate hits.
        if (dao.existsByContent(content) > 0) return@withLock null
        val embedding = embedder.embed(content)

        // Semantic dedup: scan recent memories with embeddings for
        // cosine similarity > 0.92. This catches paraphrased versions
        // of the same fact ("I like dark mode" vs "I prefer dark
        // mode") that exact-match misses. When a match is found, we
        // merge — keep the richer (longer) version and re-null its
        // embedding so the next recall re-embeds with the updated text.
        // Bounded to the most recent [SEMANTIC_DEDUP_SCAN_LIMIT] embedded
        // rows: the previous full-table scan decoded every embedding in
        // the DB under the mutex on every auto-store, an O(N) cost that
        // grew with install age for a check that only needs to catch
        // recent rephrasings.
        val existing = runCatching { dao.recentWithEmbeddings(SEMANTIC_DEDUP_SCAN_LIMIT) }
            .onFailure { Log.w("MemoryStore", "recentWithEmbeddings failed during dedup", it) }
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

        // Delegate to store() so maybeStore-inserted rows carry the same
        // fields (embeddingModel/embeddingVersion) and fire the same
        // evolution hooks as direct stores. The embedder call inside
        // store() is a cache hit — embed(content) ran above.
        store(
            content = content,
            source = source,
            category = resolvedCategory,
            importance = resolvedImportance,
            scope = scope,
            provenance = provenance,
        )
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
        // embedTagged, not embed: the tag has to describe the vector actually
        // produced. CloudEmbedder falls back to a 384-dim local hash when the
        // network call fails, and this used to write the CONFIGURED model and
        // dimension over it — so the row claimed 768 while holding 384, and a
        // later re-embed keyed on the tag would skip exactly the rows that
        // most needed redoing.
        val tagged = embedder.embedTagged(content)
        val embedding = tagged.vector
        val now = System.currentTimeMillis()
        dao.insert(
            MemoryEntity(
                id = id,
                content = content,
                source = source,
                category = category,
                importance = importance,
                embedding = Embedder.toBytes(embedding),
                embeddingModel = tagged.modelId,
                embeddingVersion = tagged.dim,
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
            val startedNanos = System.nanoTime()
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
        // Split the query into terms and match any of them, so a memory sharing
        // one word with the query is a candidate. A full-phrase match would
        // find almost nothing. Stopwords are dropped: "the"/"want" appear in
        // nearly every row and would flood the pool with fresh-but-irrelevant
        // candidates.
        //
        // The cap used to be six, because the DAO took six `LIKE` parameters —
        // an arity limit that silently truncated the user's message. FTS MATCH
        // takes any number of terms, so [MAX_QUERY_TERMS] is now only a sanity
        // bound against someone pasting a document into the chat.
        //
        // Through RetrievalTokenizer so these terms and BM25's tokens come from
        // the SAME split. They did not before: this side split on whitespace,
        // BM25 split on non-alphanumeric, and `corpusDocFreq` was keyed by one
        // and read by the other. Any word the two disagreed on — apostrophes,
        // hyphens, slashes — had its corpus df computed, stored, and never
        // read, silently falling back to candidate-set df. See
        // RetrievalTokenizer's KDoc.
        val queryWords = RetrievalTokenizer.queryTerms(retrievalQuery, config.maxQueryTerms)
        // Fetch enough candidates that the reranker actually gets its
        // RERANK_POOL_SIZE pool (limit*3 = 15 used to cap below 20).
        val candidateLimit = maxOf(limit * config.candidateMultiplier, config.rerankPoolSize + 5)
        val ftsQuery = FtsQuery.build(queryWords)
        // Over-fetched by [RetrievalConfig.ftsOverfetch]. `searchFts` orders by
        // `decayScore DESC` — freshness — so at the bare candidate limit the
        // pool was "the N freshest rows sharing any word with the query", and
        // every stage after it (BM25, vectors, RRF, the LLM reranker) re-ranked
        // a set that had been selected with no relevance signal at all. The
        // widened window is BM25's to select from; see the config field for why
        // this is a widening rather than the exact `matchinfo()` ordering.
        val keywordHits = if (ftsQuery != null) {
            dao.searchFts(ftsQuery, scopes, candidateLimit * config.ftsOverfetch)
        } else {
            // Every term was a stopword, too short, or pure punctuation. FTS
            // cannot express that — `MATCH ''` is a syntax error, not an empty
            // result — so fall back to the substring LIKE, which is also what
            // the Memory-screen search bar uses.
            dao.searchByTextInScopes("%$escapedText%", scopes, candidateLimit * config.ftsOverfetch)
        }
        // The embedder is the only network call on the recall path and it had no
        // deadline. Null here means "rank this recall without the vector
        // signal" — degraded and immediate, rather than a turn hung behind a
        // vector nobody is waiting for. Logged, because a recall that silently
        // loses one of its two relevance signals is indistinguishable from a
        // working one in the output.
        val qVec: FloatArray? = withTimeoutOrNull(config.embedTimeoutMs) { embedder.embed(retrievalQuery) }
        if (qVec == null) {
            Log.w(
                "MemoryStore",
                "query embedding did not return within ${config.embedTimeoutMs}ms; " +
                    "ranking this recall on the lexical signal alone",
            )
        }
        // The second arm of the pool. This is the only place a relevance signal
        // reaches pool SELECTION: without it a memory that shares no word with
        // the query can never become a candidate here, because the vector
        // fallback below runs only when the keyword arm is completely empty and
        // one incidental word match is enough to prevent that.
        //
        // The `embedding != null` test is redundant against the DAO — the query
        // carries `WHERE ... embedding IS NOT NULL` — and is kept because the
        // `!!` below needs it to be local, not a fact about a string in a
        // @Query annotation.
        val vectorPool: List<MemoryEntity> = if (qVec == null || config.vectorPoolSize <= 0) {
            emptyList()
        } else {
            runCatching { dao.vectorScanCandidates(scopes, config.vectorFallbackScanLimit) }
                .onFailure { Log.w("MemoryStore", "vector arm of the candidate pool failed; pool is keyword-only", it) }
                .getOrDefault(emptyList())
                .asSequence()
                .filter { it.embedding != null && embedder.isCurrent(it.embeddingModel) }
                .map { it to cosineSimilarity(qVec, Embedder.fromBytes(it.embedding!!)) }
                .filter { it.second >= config.minRelevance }
                .sortedByDescending { it.second }
                .take(config.vectorPoolSize)
                .map { it.first }
                .toList()
        }
        val keywordIds = keywordHits.mapTo(HashSet(keywordHits.size)) { it.id }
        val textHits = keywordHits + vectorPool.filterNot { it.id in keywordIds }

        if (textHits.isEmpty()) {
            // Vector fallback: no text overlap between query and any stored
            // memory in the scoped set. But the query might still be
            // semantically similar to a memory (e.g. query "programming
            // languages I enjoy" vs stored "I love Kotlin" — zero shared
            // words, but vectors are close). Scan a bounded set of the most
            // active scoped memories with embeddings and rank by cosine
            // similarity. The bound (M1 fix) caps heap churn: loading the
            // full table and decoding every embedding costs O(N) float
            // allocations per fallback recall.
            val all = dao.vectorScanCandidates(scopes, config.vectorFallbackScanLimit)
            if (all.isEmpty()) {
                trace(RetrievalTrace.Branch.VECTOR_FALLBACK, startedNanos = startedNanos)
                return emptyList()
            }
            // Same staleness guard as the main path — and it matters more here,
            // because this branch has NOTHING but the vector signal. A pool of
            // stale vectors would all score 0, fail the cutoff, and the recall
            // would return empty for a reason no log explains.
            val staleInFallback = all.count { !embedder.isCurrent(it.embeddingModel) }
            if (staleInFallback > 0) {
                Log.w(
                    "MemoryStore",
                    "vector fallback: $staleInFallback/${all.size} candidates were embedded by a " +
                        "different model and cannot be scored; run rebuildEmbeddings()",
                )
            }
            // No query vector means this branch has no signal whatsoever: it has
            // hardcoded textScore = 0 by construction, so ranking here without a
            // cosine would be ranking on recency and decay alone and calling the
            // result a semantic match.
            val scored = if (qVec == null) {
                emptyList()
            } else {
                all.asSequence()
                    .filter { embedder.isCurrent(it.embeddingModel) }
                    .map { mem ->
                        val embedding = Embedder.fromBytes(mem.embedding!!)
                        ScoredMemory(memory = mem, textScore = 0f, vectorScore = cosineSimilarity(qVec, embedding))
                    }
                    // Was `> 0.05f`, which is the noise floor of a 384-dim hash
                    // sketch rather than a relevance threshold — so a query with
                    // no answer still returned whatever random rows cleared it,
                    // straight into the system prompt. Same scale, same units,
                    // higher bar. See [RetrievalConfig.minRelevance].
                    .filter { it.vectorScore >= config.minRelevance }
                    .toList()
            }
            if (scored.isEmpty()) {
                trace(
                    RetrievalTrace.Branch.VECTOR_FALLBACK,
                    candidateCount = all.size,
                    staleVectorCount = staleInFallback,
                    startedNanos = startedNanos,
                )
                return emptyList()
            }
            // `queryEmbedding` is accepted and unused by rankCandidates (see its
            // KDoc); an empty array is the honest stand-in when the embed timed
            // out, and `scored` is already empty in that case.
            val vectorResults = Retrieval.rankCandidates(text, qVec ?: FloatArray(0), scored, limit, config = config)
            // Route vector fallback through reranker too — it catches
            // semantic matches that BM25+vector both missed.
            val reranked = shouldRerank(vectorResults.size, rerankModel)
            val results = if (reranked) {
                reranker!!.rerank(text, vectorResults, rerankModel!!, topK = limit)
            } else {
                vectorResults.take(limit)
            }
            if (config.touchOnRecall) {
                for (mem in results) { runCatching { touch(mem.id) }.onFailure { Log.w("MemoryStore", "inline touch in vector fallback failed", it) } }
            }
            // P1 MEMORY B1: vector-fallback recall path
            // skipped evolutionHooks.onMemoryRecalled until
            // now. The main path (BM25+vector) at line ~289
            // calls it, so evolution recall telemetry was
            // half-wired — it saw BM25-hits but never saw
            // fallback hits. Now both paths fire the same
            // telemetry so the Evolution engine can rank
            // memories honestly.
            for ((index, mem) in results.withIndex()) {
                runCatching {
                    evolutionHooks?.onMemoryRecalled(mem.id, text, index + 1, null, null, null)
                }.onFailure { Log.w("MemoryStore", "evolutionHooks.onMemoryRecalled in vector fallback failed (non-fatal)", it) }
            }
            trace(
                RetrievalTrace.Branch.VECTOR_FALLBACK,
                candidateCount = scored.size,
                staleVectorCount = staleInFallback,
                rerankRan = reranked,
                startedNanos = startedNanos,
            )
            return results
        }

        // Build [ScoredMemory] candidates with text and vector similarity scores.
        // BM25 text scoring: build a BM25 index from the scoped text hits
        // and score each candidate against the query. This replaces the
        // naive term-overlap score with proper IDF-weighted BM25.
        // Corpus statistics for BM25's IDF.
        //
        // The index used to be built as BM25(textHits.map { it.content }) — a
        // "corpus" consisting only of rows that had already matched a query
        // term. Document frequency for exactly the discriminating terms then
        // approached N, ln((N - df + 0.5) / (df + 0.5)) went negative, and the
        // 0.1 floor gave every query term identical weight. Since
        // Retrieval.rankCandidates fuses by rank order, a tied lexical score is
        // the same as no lexical signal at all.
        //
        // One COUNT for the corpus size plus one indexed FTS probe per term,
        // bounded by MAX_QUERY_TERMS. Both are cheap; matchinfo() could return
        // all of it in a single query, but that needs @RawQuery plus manual
        // BLOB parsing and there is no @RawQuery precedent in this DAO — see
        // ENGINEERING_HISTORY §3 for the recorded follow-up.
        //
        // `textHits` is now the union of two arms — an over-fetched FTS window
        // and a cosine-ranked vector window — so the BM25 index is built over
        // rows that may contain none of the query terms. That is intended: they
        // score 0 lexically, which under COMPETITION tie handling is exactly
        // "this signal has nothing to say about them", and they carry their
        // relevance in the vector signal instead.
        val corpusSize = runCatching { dao.countInScopes(scopes) }
            .onFailure { Log.w("MemoryStore", "corpus size lookup failed; BM25 falls back to candidate-set IDF", it) }
            .getOrDefault(textHits.size)
        val corpusDocFreq: Map<String, Int> = runCatching {
            val unigrams = queryWords.mapNotNull { term ->
                FtsQuery.quote(term)?.let { quoted -> term to dao.docFreqInScopes(quoted, scopes) }
            }
            // Bigrams too, when BM25 is emitting them. Without this they fall
            // back to candidate-set df — and because bigrams are rarer than
            // unigrams by construction, their candidate-set df sits relatively
            // CLOSER to N, so the distortion is worst for the most
            // discriminating token class. The probe is an FTS phrase query
            // ("word1 word2"), matching how the bigram token was formed.
            val bigrams = if (config.bm25Bigrams) {
                RetrievalTokenizer.queryBigrams(retrievalQuery, config.maxQueryTerms).mapNotNull { bigram ->
                    val phrase = bigram.replace('_', ' ')
                    FtsQuery.quote(phrase)?.let { quoted -> bigram to dao.docFreqInScopes(quoted, scopes) }
                }
            } else {
                emptyList()
            }
            (unigrams + bigrams).toMap()
        }.onFailure { Log.w("MemoryStore", "document frequency lookup failed; BM25 falls back to candidate-set IDF", it) }
            .getOrDefault(emptyMap())

        val bm25 = if (textHits.isNotEmpty()) {
            BM25(
                textHits.map { it.content },
                corpusSize = corpusSize,
                corpusDocFreq = corpusDocFreq,
                k1 = config.bm25K1,
                b = config.bm25B,
                idfFloor = config.bm25IdfFloor,
                bigrams = config.bm25Bigrams,
            )
        } else {
            null
        }
        val queryTokens = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        // Counted, not just skipped: a stale-vector population is invisible
        // otherwise, and it is the most likely explanation for "recall got
        // worse after I changed the embedding model".
        var staleVectors = 0
        val scoredCandidates = textHits.mapIndexed { idx, mem ->
            val embedding = mem.embedding?.let { Embedder.fromBytes(it) }

            // BM25 text score (normalized 0-1) or fallback to term overlap.
            // Scored against `retrievalQuery`, not the original `text`: the
            // candidates were fetched with the rewritten query, so scoring them
            // against the un-rewritten one measured a different question than
            // the one that selected them. The reranker still sees the original
            // `text`, which is what the user actually asked.
            val textScore = if (bm25 != null) {
                bm25.normalizedScore(retrievalQuery, idx)
            } else {
                val contentLower = mem.content.lowercase()
                val matchedTokens = queryTokens.count { it in contentLower }
                if (queryTokens.isNotEmpty()) matchedTokens.toFloat() / queryTokens.size else 0f
            }

            // Vector score: cosine similarity, but only against a vector this
            // embedder actually produced.
            //
            // A vector from a different model is not a weak signal, it is a
            // meaningless one — and when the dimensions happen to match (every
            // credible small model is 384, same as the local hash embedder)
            // cosineSimilarity returns a plausible number with no warning at
            // all. Scoring 0 and counting it is the honest answer; the count is
            // what turns "recall got worse and nothing says why" into a number.
            val vectorScore = when {
                embedding == null -> 0f
                !embedder.isCurrent(mem.embeddingModel) -> { staleVectors += 1; 0f }
                // Ordered AFTER the staleness test on purpose: a timed-out embed
                // must not also suppress the stale-vector count, which is the
                // number that explains "recall got worse after I changed model".
                qVec == null -> 0f
                else -> cosineSimilarity(qVec, embedding)
            }

            ScoredMemory(memory = mem, textScore = textScore, vectorScore = vectorScore)
        }

        // Relevance floor, applied before fusion so a candidate with no evidence
        // does not occupy a pool slot and shift everyone else's ranks.
        //
        // It is NOT a floor on the RRF score. RRF is a function of RANKS: a pool
        // of uniformly irrelevant candidates still has a rank-1 member and still
        // produces the full range of fused scores, so the fused score says
        // nothing about whether anything was relevant.
        //
        // It is also NOT `max(textScore, vectorScore) >= minRelevance`, which is
        // the obvious form and is wrong. The two are not the same scale.
        // `vectorScore` is a cosine. `textScore` is `BM25.normalizedScore`, a
        // ratio to `sum(idf) * (k1 + 1)` over every distinct query token —
        // including bigrams that no document contains, which have df 0 and so
        // the LARGEST idf in the sum. Query "kotlin android" against rows that
        // each contain both words normalises to 0.033, because idf(kotlin) and
        // idf(android) are floored to 0.1 (df = N) while idf(kotlin_android) is
        // ln(13). A shared threshold discards exact lexical matches and keeps
        // hash-sketch noise, and ReembedOnModelChangeTest fails on precisely
        // that row.
        //
        // So: any lexical evidence at all keeps a candidate — it got into the
        // FTS window by matching a real query term — and a candidate with none,
        // which is what the vector arm contributes, must clear the cosine floor.
        // That is exactly the case the old `vectorScore > 0.05f` was meant to
        // gate and did not.
        val relevantCandidates = if (config.minRelevance <= 0f) {
            scoredCandidates
        } else {
            scoredCandidates.filter { it.textScore > 0f || it.vectorScore >= config.minRelevance }
        }

        // RRF ranking: overfetch to RERANK_POOL_SIZE, then let the
        // reranker (if available) pick the final topK from the pool.
        // Without a reranker, RRF returns topK directly.
        val rrfTopN = if (reranker != null) minOf(config.rerankPoolSize, relevantCandidates.size) else limit
        val rrfResults = Retrieval.rankCandidates(
            query = text,
            queryEmbedding = qVec ?: FloatArray(0),
            candidates = relevantCandidates,
            topK = rrfTopN,
            now = System.currentTimeMillis(),
            config = config,
        )

        // Cross-encoder reranking: only worth the LLM calls when we have
        // enough candidates to justify it. With <5 candidates, RRF already
        // ranks them well and the reranker just adds latency + cost.
        val rerankRan = shouldRerank(rrfResults.size, rerankModel)
        val results = if (rerankRan) {
            reranker!!.rerank(text, rrfResults, rerankModel!!, topK = limit)
        } else {
            rrfResults.take(limit)
        }

        // Touch is fire-and-forget; we don't want a failed decay update to break recall.
        //
        // Gated because `touch` MUTATES the corpus — accessedAt, accessCount and
        // decayScore all feed fusion signals, so query N+1 sees a corpus altered
        // by query N and results depend on query order. Production wants that
        // (recall should reinforce what gets recalled); an eval run cannot have
        // it and stay reproducible.
        for ((index, mem) in results.withIndex()) {
            if (config.touchOnRecall) {
                runCatching { touch(mem.id) }
                    .onFailure { Log.w("MemoryStore", "touch on recall failed", it) }
            }
            runCatching {
                evolutionHooks?.onMemoryRecalled(mem.id, text, index + 1, null, null, null)
            }.onFailure { Log.w("MemoryStore", "evolutionHooks.onMemoryRecalled failed (non-fatal)", it) }
        }
        trace(
            RetrievalTrace.Branch.LEXICAL,
            queryTerms = queryWords,
            // Null when rewriting did not run, which is the common case. Whether
            // it fired at all is the first question when a deictic query returns
            // the wrong thing.
            rewrittenQuery = retrievalQuery.takeIf { it != text },
            candidateCount = relevantCandidates.size,
            staleVectorCount = staleVectors,
            rerankRan = rerankRan,
            startedNanos = startedNanos,
        )
        return results
    }

    companion object {
        /** How many candidates RRF overfetches for the reranker pool. */
        const val RERANK_POOL_SIZE = 20
        /** Minimum candidates to justify reranker LLM calls. */
        const val RERANK_MIN_CANDIDATES = 5
        /**
         * Cap for the vector-fallback scan. Most-active memories first
         * (accessCount, decayScore); bounds heap churn on recalls that
         * miss lexical overlap.
         */
        const val VECTOR_FALLBACK_SCAN_LIMIT = 2000
        /**
         * Upper bound on lexical query terms.
         *
         * Not an arity limit — FTS MATCH takes any number of terms. This
         * replaced a hard six, which existed only because the DAO took six
         * `LIKE` parameters, and which silently truncated the user's message
         * (the whole message is the query). 24 is generous for a question and
         * still bounds the per-term document-frequency probes in query().
         *
         * The old NO_MATCH_SENTINEL went with it: there are no unused word
         * slots left to pad.
         */
        internal const val MAX_QUERY_TERMS = 24

        /**
         * How many recent embedded rows the semantic-dedup scan in
         * [maybeStore] examines. Bounds the O(N) cosine scan that used
         * to load the entire table under the insert mutex.
         */
        internal const val SEMANTIC_DEDUP_SCAN_LIMIT = 200

        /**
         * Rows per re-embed page. Small enough that an interrupted run loses
         * little, large enough that the DAO round-trip is not the cost.
         */
        internal const val REEMBED_PAGE_SIZE = 200
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
    suspend fun rebuildEmbeddings(onProgress: ((done: Int, total: Int) -> Unit)? = null): Int {
        val model = embedder.modelId()
        val total = runCatching { dao.countNeedingReembed(model) }
            .onFailure { Log.w("MemoryStore", "countNeedingReembed failed", it) }
            .getOrDefault(0)
        if (total == 0) return 0

        var rebuilt = 0
        // Paged by the DAO query rather than loaded whole, which makes the
        // query itself the work queue: every successful update removes a row
        // from the next page, so an interrupted run resumes exactly where it
        // stopped with no bookkeeping.
        while (true) {
            val pending = runCatching { dao.needingReembed(model, REEMBED_PAGE_SIZE) }
                .onFailure { Log.w("MemoryStore", "needingReembed failed", it) }
                .getOrDefault(emptyList())
            if (pending.isEmpty()) break

            var pageSucceeded = 0
            // Batch in groups of 5 with parallel async to avoid sequential
            // cloud round-trips. Each embedding is an independent API call.
            pending.chunked(5).forEach { batch ->
                coroutineScope {
                    batch.map { mem ->
                        async(Dispatchers.IO) {
                            runCatching {
                                val tagged = embedder.embedTagged(mem.content)
                                // The tag MUST be written alongside the vector.
                                // The previous version updated `embedding` only,
                                // so a rebuilt row kept its stale
                                // embeddingModel — and the next run would find
                                // it again, forever, re-embedding the same rows
                                // and never converging.
                                dao.update(
                                    mem.copy(
                                        embedding = Embedder.toBytes(tagged.vector),
                                        embeddingModel = tagged.modelId,
                                        embeddingVersion = tagged.dim,
                                    ),
                                )
                            }.onFailure { Log.w("MemoryStore", "rebuildEmbeddings: re-embed failed for memory ${mem.id}", it) }
                                .isSuccess
                        }
                    }.awaitAll().forEach { ok -> if (ok) pageSucceeded += 1 }
                }
            }
            rebuilt += pageSucceeded
            onProgress?.invoke(rebuilt, total)

            // Every row in the page failed — most likely the embedder itself is
            // down. Stop rather than spin: the query would return the same page
            // again on the next iteration, forever.
            if (pageSucceeded == 0) {
                Log.w("MemoryStore", "rebuildEmbeddings: no progress on a page of ${pending.size}; stopping")
                break
            }
        }
        return rebuilt
    }

    /** How many rows would be re-embedded by [rebuildEmbeddings] right now. */
    suspend fun countNeedingReembed(): Int =
        runCatching { dao.countNeedingReembed(embedder.modelId()) }
            .onFailure { Log.w("MemoryStore", "countNeedingReembed failed", it) }
            .getOrDefault(0)

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
        // Record the edit in the audit trail and apply it atomically
        // (M6 fix): the audit row and the row update now commit or roll
        // back together instead of two independent writes.
        runCatching {
            dao.updateWithAudit(
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
                MemoryEditEntity(
                    memoryId = id,
                    oldContent = existing.content,
                    newContent = content,
                    oldCategory = existing.category,
                    newCategory = category,
                    editedBy = "user",
                ),
            )
        }.onFailure { Log.w("MemoryStore", "updateWithAudit failed (edit not applied)", it) }
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
        if (edits.isEmpty()) dao.insert(memory) else dao.restoreWithAudit(memory, edits)
    }

    /**
     * Whether the reranker runs for this result set.
     *
     * Routed through [RetrievalConfig.rerankMode] rather than testing
     * `rerankModel != null` inline at both call sites. Those two conditions were
     * the entire rerank policy, which meant the config field existed and decided
     * nothing — the same defect as `ToolPolicy.allowedScopes`, introduced in the
     * commit that added the config.
     *
     * The default reproduces the shipped behaviour exactly: the four
     * tool-initiated callers pass no model, so they have never been reranked and
     * still are not. `OFF` is now a genuine kill switch.
     */
    private fun shouldRerank(candidateCount: Int, rerankModel: String?): Boolean {
        if (reranker == null || candidateCount < config.rerankMinCandidates) return false
        return when (config.rerankMode) {
            RerankMode.OFF -> false
            RerankMode.LLM -> rerankModel != null
        }
    }

    private fun trace(
        branch: RetrievalTrace.Branch,
        queryTerms: List<String> = emptyList(),
        rewrittenQuery: String? = null,
        candidateCount: Int = 0,
        staleVectorCount: Int = 0,
        rerankRan: Boolean = false,
        startedNanos: Long,
    ) {
        if (!config.trace) return
        lastTrace = RetrievalTrace(
            branch = branch,
            queryTerms = queryTerms,
            rewrittenQuery = rewrittenQuery,
            candidateCount = candidateCount,
            staleVectorCount = staleVectorCount,
            rerankRan = rerankRan,
            elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000,
        )
    }

    /**
     * What the last query actually did, when [RetrievalConfig.trace] is on.
     *
     * Single-slot and last-write-wins: this is a debugging and eval affordance,
     * not a log. Off in production, where it stays null.
     */
    @Volatile
    var lastTrace: RetrievalTrace? = null
        private set

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

    /**
     * Set the tags for a single memory without touching other fields
     * or the edit-audit trail. Used by [DreamConsolidator] to mark
     * sources as consolidated — routing this through [update] used to
     * null the embedding (killing vector recall for the row), reset
     * the decay clock, and forge a `editedBy="user"` audit row on
     * every dream cycle.
     */
    suspend fun updateTags(id: String, tags: String) {
        dao.updateTags(id, tags)
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
