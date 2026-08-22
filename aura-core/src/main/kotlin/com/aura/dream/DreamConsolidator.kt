package com.aura.dream

import com.aura.core.error.CrashLogger
import com.aura.memory.Embedder
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderChunk
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.world.BeliefPromoter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import android.util.Log

/**
 * Background memory consolidator ("Dream Mode").
 *
 * Pipeline (per cycle, in this order):
 *   1. FETCH        -- read recent candidate memories from Room
 *   2. CLUSTER      -- group by embedding cosine similarity
 *   3. SUMMARIZE    -- LLM-compress each cluster into a DreamSummary
 *   4. WRITE        -- upsert DreamSummaryEntity (idempotent on clusterId)
 *   5. EXTRACT_ROUTINES -- mine tool-call N-grams from conversation turns
 *   6. UPDATE_PROFILE  -- refresh user profile from consolidated memories
 *   7. PRUNE_STALE  -- archive low-importance, old memories (non-destructive)
 *   8. CONTRADICTION_REPORT -- detect summaries that contradict older ones
 *   9. DENSIFY_GRAPH -- propose new KG edges between similar nodes
 *
 * Phases 1-4 shipped in v1 (cycle 1, 2026-07-23). Phases 5-9 added
 * in v2 (2026-07-23) to match the Python Aura's full 9-phase
 * pipeline. The Python implementation has 10 phases (the extra
 * being "NARRATIVE_SELF" which updates the agent's narrative
 * identity -- out of scope for Android's 7-builtin-agent model).
 *
 * Idempotency: re-running on the same cluster updates the existing
 * summary row instead of double-writing. The unique index on
 * `clusterId` enforces this at the DB level. Same contract for
 * routines (unique on `signature`) and KG proposals (unique on
 * the from/to pair).
 *
 * Non-destructive: source memories are NOT deleted in v1. They are
 * tagged with `consolidated:dream_<clusterId>` so future cycles skip
 * them. v2 may add an opt-in "forget sources" mode.
 *
 * Thread safety: a single instance is fine; the WorkManager worker
 * enqueues the periodic work as `enqueueUniquePeriodicWork` so two
 * cycles cannot run concurrently. The DreamWorker holds a CoroutineWorker
 * scope which is single-threaded.
 */
@Singleton
class DreamConsolidator @Inject constructor(
    private val memoryStore: MemoryStore,
    private val dreamDao: DreamConsolidationDao,
    private val routineDao: RoutineDao,
    private val kgProposalDao: KgEdgeProposalDao,
    private val contradictionDao: ContradictionDao,
    private val beliefPromoter: BeliefPromoter? = null,
    private val worldEventProducer: com.aura.world.WorldEventProducer? = null,
    private val opportunityEngine: com.aura.world.OpportunityEngine? = null,
    private val narrativeSelf: com.aura.consciousness.NarrativeSelf? = null,
    private val curiosityStore: com.aura.curiosity.CuriosityStore? = null,
    private val intrinsicMotivation: com.aura.consciousness.IntrinsicMotivation? = null,
    private val providerRegistry: ProviderRegistry,
    private val cheapModelResolver: com.aura.providers.CheapModelResolver? = null,
    private val embedder: Embedder,
    private val crashLogger: CrashLogger,
    private val conversationStoreProvider: dagger.Lazy<com.aura.agent.ConversationStore>,
    private val userProfileStoreProvider: dagger.Lazy<com.aura.profile.UserProfileStore>,
    private val knowledgeGraphRepositoryProvider: dagger.Lazy<com.aura.kg.KnowledgeGraphRepository>,
) {
    /**
     * Run one full consolidation cycle. Safe to call from any
     * coroutine. Returns a [DreamCycleReport] with per-stage
     * counters.
     */
    suspend fun runCycle(): DreamCycleReport {
        val cycleId = "dream_${System.currentTimeMillis()}"
        val started = System.currentTimeMillis()
        var report = DreamCycleReport(cycleId = cycleId)

        try {
            // 1. FETCH
            val candidates = fetchCandidates()
            report = report.copy(memoriesProcessed = candidates.size)
            if (candidates.isEmpty()) {
                return finalize(report, started, modelUsed = "")
            }

            // 2. CLUSTER
            val clusters = clusterByCosine(candidates)
            val clustersAboveMin = clusters.filter { it.size >= MIN_CLUSTER_SIZE }
            report = report.copy(clustersFormed = clustersAboveMin.size)

            // 3 + 4. SUMMARIZE + WRITE
            val modelUsed = resolveCheapModel()
            val skipSet = dreamDao.allClusterIds().toSet()

            var summariesWritten = 0
            var skippedDuplicate = 0
            var skippedSmall = clusters.size - clustersAboveMin.size
            var failedLlm = 0
            var totalCharsSaved = 0
            val writtenThisCycle = mutableListOf<DreamSummaryEntity>()

            for (cluster in clustersAboveMin) {
                val clusterId = clusterIdFor(cluster)
                if (clusterId in skipSet) {
                    skippedDuplicate++
                    continue
                }
                val rawText = cluster.joinToString("\n---\n") { it.content }
                val compressed = try {
                    summarizeCluster(cluster, modelUsed)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    failedLlm++
                    android.util.Log.w(
                        "DreamConsolidator",
                        "summarize failed for cluster $clusterId: ${failure.message}",
                        failure,
                    )
                    cluster.firstOrNull()?.content?.take(300) ?: continue
                }
                if (compressed.isBlank()) continue
                val sourceIds = cluster.map { it.id }
                val tags = dominantTags(cluster)
                val summary = DreamSummary(
                    clusterId = clusterId,
                    sourceMemoryIds = sourceIds,
                    compressedText = compressed,
                    dominantTags = tags,
                    sourceCount = cluster.size,
                    modelUsed = modelUsed,
                )
                val entity = summary.toEntity()
                dreamDao.insert(entity)
                writtenThisCycle.add(entity)
                tagSourceMemories(cluster, clusterId)
                summariesWritten++
                totalCharsSaved += rawText.length - compressed.length
            }

            report = report.copy(
                summariesWritten = summariesWritten,
                summariesSkippedDuplicate = skippedDuplicate,
                summariesSkippedSmall = skippedSmall,
                summariesFailedLlm = failedLlm,
                totalCharsSaved = totalCharsSaved,
                modelUsed = modelUsed,
            )

            // 5. EXTRACT_ROUTINES -- mine tool-call N-grams from recent
            //    conversations. Skipped silently on failure so a missing
            //    metacognition source never breaks the cycle.
            try {
                val (routineCount, occurrences) = extractRoutines()
                report = report.copy(
                    routinesExtracted = routineCount,
                    routineOccurrences = occurrences,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.w("DreamConsolidator", "extractRoutines: ${t.message}", t)
            }

            // 6. UPDATE_PROFILE -- best-effort; the profile is the most
            //    user-facing artifact. If it fails the cycle still
            //    returns a partial report.
            try {
                val updated = updateProfileFromConsolidated(report.summariesWritten)
                report = report.copy(profileUpdated = updated)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.w("DreamConsolidator", "updateProfile: ${t.message}", t)
            }

            // 7. PRUNE_STALE -- archive low-importance, old memories.
            //    Mirrors Python's _prune_stale_sqlite. Non-destructive
            //    in spirit: we set decayScore to 0 (FadeMem treats
            //    this as forgotten) but don't hard-delete.
            try {
                val archived = pruneStale()
                report = report.copy(memoriesArchived = archived)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.w("DreamConsolidator", "pruneStale: ${t.message}", t)
            }

            // 8. CONTRADICTION_REPORT -- detect NEW summaries that
            //    contradict an EXISTING stored summary about the same
            //    topic. Heuristic: explicit negation tokens in the new
            //    summary plus tag/content overlap with the older one.
            try {
                val found = detectContradictions(writtenThisCycle)
                report = report.copy(contradictionsFound = found)
                // COHERENCE is the drive to resolve contradictions, and its
                // intensity comes from DriveSignals.contradictionCount. Until
                // 2026-08-08 nothing satisfied it, so it could only climb.
                // A completed sweep that surfaced nothing new IS the coherent
                // state — that is the honest moment to satisfy it. A sweep that
                // found contradictions deliberately leaves the drive raised.
                if (found == 0) {
                    runCatching {
                        intrinsicMotivation?.satisfy(com.aura.consciousness.IntrinsicMotivation.DriveType.COHERENCE)
                        intrinsicMotivation?.save()
                    }.onFailure { android.util.Log.w("DreamConsolidator", "coherence satisfy failed: ${it.message}", it) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.w("DreamConsolidator", "detectContradictions: ${t.message}", t)
            }

            // 8b. NARRATIVE_SELF -- update the agent's evolving self-model
            //     from this cycle's output: the LLM-written cluster
            //     summaries become "recent growth" and unresolved
            //     contradictions become "active concerns". Reuses text the
            //     cycle already produced — zero extra LLM calls. This is
            //     the phase the Python pipeline calls NARRATIVE_SELF; it
            //     runs after phase 8 so it sees this cycle's
            //     contradictions. Only fires when summaries were written —
            //     an empty cycle must not blank the narrative.
            if (report.summariesWritten > 0) {
                try {
                    val updated = updateNarrativeSelf(writtenThisCycle)
                    report = report.copy(narrativeUpdated = updated)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    android.util.Log.w("DreamConsolidator", "narrativeSelf: ${t.message}", t)
                }
            }

            // 9. DENSIFY_GRAPH -- propose new KG edges via Jaccard
            //    on labels. Bounded (20 per cycle, threshold 0.5) so
            //    it doesn't flood the proposals table.
            try {
                val proposed = densifyGraph()
                report = report.copy(graphEdgesProposed = proposed)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.w("DreamConsolidator", "densifyGraph: ${t.message}", t)
            }

            // 10. PROMOTE -- turn reinforced KG edges about the user into
            //     world-model beliefs. Runs after densifyGraph so it sees
            //     any edges this cycle added.
            try {
                val promoted = beliefPromoter?.promote() ?: 0
                report = report.copy(beliefsPromoted = promoted)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.w("DreamConsolidator", "promoteBeliefs: ${t.message}", t)
            }

            // 10b. CURIOSITY — look for something worth asking about.
            //      Runs after densify (9) and promote (10) on purpose: both
            //      add edges, and a node that just gained one is no longer a
            //      gap. Scanning earlier would raise questions the cycle was
            //      about to answer for itself.
            //
            //      This is also the first writer NarrativeSelf's
            //      unresolvedQuestions has ever had — phase 8b passes the
            //      field back in from its own snapshot, so with nothing else
            //      writing it, it stayed empty for every user since it shipped.
            try {
                val raised = curiosityStore?.scanAndAuthor() ?: 0
                report = report.copy(questionsRaised = raised)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.w("DreamConsolidator", "curiosity scan: ${t.message}", t)
            }

            // 11. WORLD EVENT — record that a dream cycle completed so the
            //     user can see it in the world model screen and the
            //     opportunity engine can process it. Only record when the
            //     cycle actually produced results — a 0/0 event is
            //     misleading.
            if (report.summariesWritten > 0 || report.memoriesArchived > 0) {
                try {
                    worldEventProducer?.recordDreamCycle(
                        cycleId = cycleId,
                        summariesWritten = report.summariesWritten,
                        memoriesArchived = report.memoriesArchived,
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    android.util.Log.w("DreamConsolidator", "worldEvent: ${t.message}", t)
                }
            }

            // 12. OPPORTUNITY ENGINE — scan unconsumed world events and
            //     active beliefs, generate actionable opportunities for the
            //     user to approve or dismiss. Runs after world event
            //     recording so it sees this cycle's event.
            try {
                opportunityEngine?.runCycle()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.w("DreamConsolidator", "opportunityEngine: ${t.message}", t)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Last resort: if the crash logger itself fails there is nowhere
            // left to report, so this genuinely must not propagate. Log the
            // secondary failure rather than swallowing it silently — the
            // original justification here ("unavailable in JVM tests") was a
            // test concern standing in for a runtime one.
            runCatching { crashLogger.logException("dream_consolidation_failed", failure) }
                .onFailure { android.util.Log.w("DreamConsolidator", "crash logging failed: ${it.message}", it) }
        }

        return finalize(report, started, report.modelUsed)
    }

    // ---------------------------------------------------------------------
    // Phase 1: FETCH
    // ---------------------------------------------------------------------

    /**
     * Load the most recent [BATCH_SIZE] * 3 memories for the candidate
     * pool. We fetch 3x the batch size because roughly 2/3 will be
     * filtered out (no embedding, already consolidated, decay < 0.05).
     *
     * Filters applied:
     *  - decayScore > [DECAY_FLOOR] (skip already-forgotten)
     *  - embedding != null (we need vectors to cluster; memories
     *    without embeddings are excluded from v1; v2 will add a
     *    BM25-based fallback)
     *  - tags does NOT start with "consolidated:" (skip already-in-summary)
     */
    private suspend fun fetchCandidates(): List<MemoryEntity> {
        val pool = memoryStore.recent(BATCH_SIZE * 3)
        // Stale vectors are EXCLUDED, not merely deprioritised.
        //
        // This is the most damaging place a mixed-model pool can appear.
        // Everywhere else a wrong vector produces a wrong ranking, which is
        // transient and self-correcting. Here the clusters are summarised and
        // WRITTEN BACK AS NEW MEMORIES, so clustering a fresh vector with a
        // stale one persists the mistake into the corpus, and no later
        // re-embed undoes it.
        val stale = pool.count { it.embedding != null && !embedder.isCurrent(it.embeddingModel) }
        if (stale > 0) {
            android.util.Log.w(
                "DreamConsolidator",
                "$stale/${pool.size} candidates were embedded by a different model and are " +
                    "excluded from clustering; run MemoryStore.rebuildEmbeddings()",
            )
        }
        return pool.filter { entity ->
            entity.embedding != null &&
                embedder.isCurrent(entity.embeddingModel) &&
                entity.decayScore > DECAY_FLOOR &&
                !entity.tags.split(",").any { it.trim().startsWith(CONSOLIDATED_TAG_PREFIX) }
        }.take(BATCH_SIZE)
    }

    // ---------------------------------------------------------------------
    // Phase 2: CLUSTER
    // ---------------------------------------------------------------------

    /**
     * Greedy single-linkage clustering on embedding cosine.
     *
     * Algorithm: visit memories in createdAt-DESC order. For each
     * memory, find the first existing cluster whose first member
     * has cosine >= [CLUSTER_THRESHOLD]. If found, append. Else
     * start a new cluster. This is O(N * K) where K is the number
     * of clusters; for our default N=60, K=10-20, the inner loop
     * is at most 1200 cosine calculations. Each cosine on 384-dim
     * vectors is ~10μs -> total cluster time ~12ms.
     *
     * Why greedy (vs. hierarchical agglomerative): Python uses
     * greedy too. The result is approximate; for our use case
     * (rough paraphrase grouping) exactness is unnecessary.
     *
     * The Python version uses Jaccard fallback when embeddings are
     * missing. v1 only ships the embedding path; memories without
     * embeddings are filtered out in [fetchCandidates].
     */
    private fun clusterByCosine(memories: List<MemoryEntity>): List<List<MemoryEntity>> {
        if (memories.isEmpty()) return emptyList()

        val vectors = memories.map { entity ->
            entity.id to Embedder.fromBytes(entity.embedding!!)
        }

        val clusters = mutableListOf<MutableList<MemoryEntity>>()
        val clusterReps = mutableListOf<FloatArray>() // first member of each cluster

        for ((id, vector) in vectors) {
            val entity = memories.first { it.id == id }
            val matchIdx = clusterReps.indexOfFirst { rep ->
                cosine(vector, rep) >= CLUSTER_THRESHOLD
            }
            if (matchIdx >= 0) {
                clusters[matchIdx].add(entity)
            } else {
                clusters.add(mutableListOf(entity))
                clusterReps.add(vector)
            }
        }
        return clusters
    }

    /**
     * Inlined cosine similarity. [MemoryStore.cosineSimilarity] exists
     * but is on the non-suspend path; we keep this inlinable to avoid
     * a per-call method dispatch in the hot loop.
     */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }

    // ---------------------------------------------------------------------
    // Phase 3: SUMMARIZE
    // ---------------------------------------------------------------------

    /**
     * Call the cheap-model LLM to compress a cluster into 2-3
     * sentences. Returns the compressed text, or the first memory's
     * first 300 chars on LLM error.
     *
     * Cheap-model resolution matches [com.aura.agent.ConversationCompactor]:
     * MoA (3x cost) -> first non-MoA configured provider -> original.
     */
    private suspend fun summarizeCluster(
        cluster: List<MemoryEntity>,
        modelUsed: String,
    ): String {
        val prompt = buildSummaryPrompt(cluster)

        val output = StringBuilder()
        providerRegistry.chat(
            modelId = modelUsed,
            messages = listOf(
                ProviderMessage(role = ProviderMessage.Role.system, content = SYSTEM_PROMPT),
                ProviderMessage(role = ProviderMessage.Role.user, content = prompt),
            ),
            options = ChatOptions(temperature = 0.1, maxTokens = 500, attended = false),
            tools = emptyList(),
        ).collect { chunk ->
            chunk.error?.let { error ->
                throw IllegalStateException("${error.code}: ${error.message}")
            }
            chunk.text?.let { output.append(it) }
        }
        return output.toString().trim().take(MAX_SUMMARY_CHARS)
    }

    /**
     * The compression prompt, with every memory carrying the date it was written.
     *
     * Two things were wrong here and they compounded.
     *
     * The instruction ended "Remove duplicates and transient wording", and a date
     * reads as transient wording. Gist-style consolidation drops dates and times
     * on its own — measured at 3% temporal-expression retention, rising to 62%
     * from an instruction to keep them — and this asked for the drop explicitly.
     *
     * The input was `it.content` alone, so no timestamp reached the model at all.
     * That is the half that makes the other half unfixable by wording: "last
     * Tuesday" cannot be resolved to a date by a reader who does not know when it
     * was written, and a summary that keeps the phrase unresolved is worse than
     * one that drops it — the phrase silently means something different every
     * time it is read afterwards.
     *
     * So each entry is prefixed with its own [MemoryEntity.createdAt] and the
     * model is asked to resolve against it. Ten entries at ~13 characters is ~130
     * against [MAX_PROMPT_CHARS]; the budget was never the reason this was absent.
     */
    internal fun buildSummaryPrompt(cluster: List<MemoryEntity>): String {
        // The count has to describe what follows, not the cluster it came
        // from. This announced `cluster.size` while showing at most
        // MAX_MEMBERS_IN_PROMPT of them, so a 25-member cluster asked the
        // model to compress 25 entries and handed it 10 — and now that each
        // one arrives dated, a model told to keep the order of 25 things it
        // cannot see has been handed a contradiction rather than a task.
        val members = cluster.take(MAX_MEMBERS_IN_PROMPT)
        val joined = members
            .joinToString("\n---\n") { "[${promptDate(it.createdAt)}] ${it.content}" }
            .take(MAX_PROMPT_CHARS)

        return buildString {
            append("Compress the following ${members.size} related memory entries into a ")
            append("single concise summary (2-3 sentences, max 500 chars). ")
            append("Preserve key facts and preferences, and remove duplicates.\n")
            append("Each entry is prefixed with the date it was recorded. Keep dates, ")
            append("durations and the order things happened, and rewrite every relative ")
            append("time expression as an absolute date using the prefix of the entry it ")
            append("came from — \"last Tuesday\" in an entry dated 2026-03-14 becomes the ")
            append("Tuesday before that date. A relative expression carried through ")
            append("unresolved means something different every time it is read later.\n\n")
            append(joined)
        }
    }

    /** `yyyy-MM-dd` in the device's zone — the format the prompt tells the model to expect. */
    private fun promptDate(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    /**
     * A cheap model for the summarisation phases. MoA is excluded because it is
     * a 3-model virtual provider — summarising every cluster through it would
     * fire three API calls per cluster.
     *
     * Was "the first non-MoA provider's first model". Excluding MoA was right;
     * treating catalog order as a cheapness judgement was not, so a nightly
     * consolidation could run every cluster through whichever model the provider
     * happened to list first. [com.aura.providers.CheapModelResolver] ranks
     * properly and honours the user's Fast-model setting.
     *
     * Returns "" rather than null when nothing is configured — the callers
     * already treat blank as "skip this phase".
     */
    private suspend fun resolveCheapModel(): String =
        cheapModelResolver?.resolve().orEmpty()

    /**
     * Pick the top 5 most-frequent tags across the cluster. These
     * are passed through to the DreamSummary's `dominantTags` and
     * become searchable filters in the Memory screen.
     */
    private fun dominantTags(cluster: List<MemoryEntity>): List<String> {
        val tagFreq = mutableMapOf<String, Int>()
        for (entity in cluster) {
            for (tag in entity.tags.split(",")) {
                val t = tag.trim()
                if (t.isNotEmpty() && !t.startsWith(CONSOLIDATED_TAG_PREFIX)) {
                    tagFreq[t] = (tagFreq[t] ?: 0) + 1
                }
            }
        }
        return tagFreq.entries
            .sortedByDescending { it.value }
            .take(MAX_DOMINANT_TAGS)
            .map { it.key }
    }

    // ---------------------------------------------------------------------
    // Phase 4: WRITE
    // ---------------------------------------------------------------------

    /**
     * Tag each source memory with `consolidated:dream_<clusterId>` so
     * future cycles skip them. Capped at [MAX_CONSOLIDATED_TAGS] per
     * memory to prevent unbounded growth. Sources are NOT deleted in
     * v1 -- the consolidation is non-destructive; v2 may add an
     * opt-in "forget sources" mode.
     */
    private suspend fun tagSourceMemories(
        cluster: List<MemoryEntity>,
        clusterId: String,
    ) {
        val tagToAdd = "$CONSOLIDATED_TAG_PREFIX$clusterId"
        for (entity in cluster) {
            val existingTags = entity.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val consolidatedTags = existingTags.filter { it.startsWith(CONSOLIDATED_TAG_PREFIX) }
            val nonConsolidatedTags = existingTags.filter { !it.startsWith(CONSOLIDATED_TAG_PREFIX) }
            // Keep only the most recent MAX_CONSOLIDATED_TAGS, plus the new one.
            val trimmedConsolidated = consolidatedTags.takeLast(MAX_CONSOLIDATED_TAGS - 1)
            val newTags = (nonConsolidatedTags + trimmedConsolidated + tagToAdd)
                .joinToString(",")
            // Tags-only write: the general update() path is the USER-edit
            // path — it nulls the embedding (killing vector recall until a
            // manual rebuild), resets the decay clock, and forges a
            // user-attributed audit row. A dream cycle must do none of that.
            memoryStore.updateTags(entity.id, newTags)
        }
    }

    // ---------------------------------------------------------------------
    // Phase 5: EXTRACT_ROUTINES
    // ---------------------------------------------------------------------

    /**
     * Mine tool-call N-grams (size 2-4) from recent conversations.
     *
     * Python's equivalent reads `logs/metacognition/` JSONL files and
     * groups tool sequences by goal. On Android the same signal lives
     * in [com.aura.agent.Conversation.turns] - every turn that called
     * a tool has its call list in [com.aura.agent.Turn.toolTurns].
     *
     * Algorithm:
     *   1. Load the N most-recent conversations (default 200)
     *   2. For each, extract the ordered tool name list per turn
     *   3. Enumerate all 2-4 length contiguous N-grams per turn
     *   4. Count occurrences; upsert into routines with REPLACE
     *
     * Returns (newRoutinesCount, totalOccurrencesAcrossAllNgrams).
     * Routines that already exist get their occurrence count bumped
     * (no "new" credit). Cold start (zero or one conversation) is
     * a no-op.
     */
    internal suspend fun extractRoutines(
        conversationLimit: Int = ROUTINE_CORPUS_LIMIT,
    ): Pair<Int, Int> {
        val conversationStore = conversationStoreProvider.get()
        val conversations = runCatching {
            conversationStore.recent(conversationLimit)
        }.onFailure { android.util.Log.w("DreamConsolidator", "extractRoutines: conversationStore.recent failed: ${it.message}", it) }
        .getOrNull() ?: return 0 to 0
        if (conversations.isEmpty()) return 0 to 0

        // 1. Per turn, list tool names in call order
        val allNgrams = mutableMapOf<String, Int>()
        val ngramToConversations = mutableMapOf<String, MutableSet<String>>()
        for (conv in conversations) {
            for (turn in conv.turns) {
                val toolNames = turn.toolTurns.map { it.name }
                if (toolNames.size < 2) continue
                // 2. Enumerate 2-4 length N-grams
                for (n in 2..4) {
                    if (toolNames.size < n) break
                    for (i in 0..(toolNames.size - n)) {
                        val ngram = toolNames.subList(i, i + n).joinToString("|")
                        allNgrams[ngram] = (allNgrams[ngram] ?: 0) + 1
                        ngramToConversations.getOrPut(ngram) { mutableSetOf() }.add(conv.id)
                    }
                }
            }
        }
        if (allNgrams.isEmpty()) return 0 to 0

        // 3. Upsert each N-gram that meets the minimum occurrence
        //    threshold. Routines below the threshold are noise; we
        //    don't record them at all.
        val now = System.currentTimeMillis()
        val knownSignatures = routineDao.allSignatures().toSet()
        var newCount = 0
        var totalOccurrences = 0
        for ((ngram, count) in allNgrams) {
            if (count < MIN_ROUTINE_OCCURRENCES) continue
            totalOccurrences += count
            val distinctConvs = ngramToConversations[ngram].orEmpty().size
            val displayLabel = ngram.replace("|", " -> ")
            val existing = routineDao.bySignature(ngram)
            val firstSeen = existing?.firstSeenAt ?: now
            val id = "routine_${md5Short(ngram)}"
            routineDao.insert(
                RoutineEntity(
                    id = id,
                    signature = ngram,
                    displayLabel = displayLabel,
                    occurrenceCount = count,
                    distinctConversations = distinctConvs,
                    sourceConversationIds = ngramToConversations[ngram].orEmpty()
                        .take(20)
                        .joinToString(","),
                    firstSeenAt = firstSeen,
                    lastSeenAt = now,
                    description = existing?.description.orEmpty(),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            if (ngram !in knownSignatures) newCount++
        }
        return newCount to totalOccurrences
    }

    // ---------------------------------------------------------------------
    // Phase 6: UPDATE_PROFILE
    // ---------------------------------------------------------------------

    /**
     * Phase 6: Update the user profile after a dream cycle.
     *
     * Reads the dream summaries written this cycle and asks the cheap
     * model to extract profile-worthy facts and traits. Merges them
     * into the user profile via [UserProfileStore.mergeFacts] and
     * [UserProfileStore.mergeTraits] so nothing learned earlier is
     * lost.
     *
     * Returns true if the profile was updated (even partially), false
     * on error or when no summaries were written.
     */
    internal suspend fun updateProfileFromConsolidated(summariesWritten: Int): Boolean {
        if (summariesWritten == 0) return false
        val store = userProfileStoreProvider.get()
        // runCatching-audit: this block spans ~55 lines (LLM call, JSON parse,
        // profile merge); its .onFailure { Log.w(...) }.getOrDefault(false) is
        // at the very end, past the audit's scan window.
        return runCatching {
            store.awaitLoaded()
            // Read the most recent dream summaries from this cycle.
            val recentSummaries = dreamDao.recentSummaries(10)
            if (recentSummaries.isEmpty()) {
                store.update() // timestamp-only fallback
                return@runCatching true
            }
            val joined = recentSummaries.joinToString("\n---\n") { it.compressedText }.take(2000)
            val model = resolveCheapModel()
            if (model.isBlank()) {
                // No provider configured — timestamp-only persist.
                store.update()
                return@runCatching true
            }
            // Ask the cheap model to extract profile-worthy info.
            val prompt = """
                Extract user profile information from these memory consolidation summaries.
                Return JSON with two arrays:
                - "facts": short factual statements about the user (e.g. "works as a designer", "lives in Baku")
                - "traits": personality or work-style traits (e.g. "prefers concise answers", "likes dark mode")
                Only include things clearly about the user. Max 5 items per array.

                Summaries:
                $joined
            """.trimIndent()
            val output = StringBuilder()
            providerRegistry.chat(
                modelId = model,
                messages = listOf(
                    ProviderMessage(role = ProviderMessage.Role.system, content = "You extract structured profile data. Respond only with JSON."),
                    ProviderMessage(role = ProviderMessage.Role.user, content = prompt),
                ),
                options = ChatOptions(temperature = 0.1, maxTokens = 300, attended = false),
                tools = emptyList(),
            ).collect { chunk ->
                chunk.error?.let { throw IllegalStateException("${it.code}: ${it.message}") }
                chunk.text?.let { output.append(it) }
            }
            // Parse the JSON response and merge into profile.
            val response = output.toString().trim()
            val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
            val parsed = runCatching { json.parseToJsonElement(response) }.onFailure { Log.w("DreamConsolidator", "runCatching failed: ${it.message}", it) }.getOrNull()
            if (parsed != null) {
                val obj = parsed.jsonObject
                val facts = obj["facts"]?.let {
                    runCatching { json.decodeFromString<List<String>>(it.toString()) }.onFailure { Log.w("DreamConsolidator", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyList())
                } ?: emptyList()
                val traits = obj["traits"]?.let {
                    runCatching { json.decodeFromString<List<String>>(it.toString()) }.onFailure { Log.w("DreamConsolidator", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyList())
                } ?: emptyList()
                if (facts.isNotEmpty()) store.mergeFacts(facts)
                if (traits.isNotEmpty()) store.mergeTraits(traits)
            }
            // Always bump the timestamp so the UI reflects the cycle.
            store.update()
            true
        }.onFailure { android.util.Log.w("DreamConsolidator", "updateProfile: ${it.message}", it) }
        .getOrDefault(false)
    }

    // ---------------------------------------------------------------------
    // Phase 7: PRUNE_STALE
    // ---------------------------------------------------------------------

    /**
     * Mark low-importance, old memories as "forgotten" by setting
     * their decayScore to 0. Mirrors Python's
     * `DreamConsolidator._prune_stale_sqlite`.
     *
     * FadeMem treats decayScore == 0 as forgotten, so the row
     * stays in the table (audit trail) but stops surfacing in recall.
     *
     * Threshold: importance < [PRUNE_IMPORTANCE_FLOOR] AND
     * accessCount == 0 AND age > [PRUNE_AGE_DAYS] days. These
     * match the Python defaults.
     */
    internal suspend fun pruneStale(): Int {
        val now = System.currentTimeMillis()
        val cutoff = now - PRUNE_AGE_MS
        val candidates = memoryStore.recent(MEMORY_POOL_FOR_PRUNE)
        var archived = 0
        for (entity in candidates) {
            if (entity.importance >= PRUNE_IMPORTANCE_FLOOR) continue
            if (entity.accessCount > 0) continue
            if (entity.createdAt >= cutoff) continue
            if (entity.decayScore <= 0f) continue
            runCatching {
                memoryStore.updateDecayScore(entity.id, 0f)
                archived++
            }.onFailure {
                android.util.Log.w("DreamConsolidator", "prune update failed for ${entity.id}: ${it.message}", it)
            }
        }
        return archived
    }

    // ---------------------------------------------------------------------
    // Phase 8: CONTRADICTION_REPORT
    // ---------------------------------------------------------------------

    /**
     * Compare each summary written THIS cycle against previously-stored
     * summaries about similar content. The Python implementation reads
     * CONTRADICTS edges directly from the KG; on Android this pass
     * detects the common user-visible case heuristically.
     *
     * The old version required two rows sharing a clusterId — a state
     * that can never exist: clusterId has a unique index and runCycle's
     * skipSet refuses to re-summarize a known clusterId, so the phase
     * never fired. New summaries are now compared against EXISTING
     * summaries whose dominant tags overlap or whose text shares enough
     * tokens (Jaccard >= [CONTRADICTION_SIMILARITY]) — deterministic,
     * no extra LLM calls.
     *
     * Heuristic triggers (case-insensitive):
     *   - "no longer ..."
     *   - "switched from X to Y"
     *   - "instead of X, ..."
     *   - "used to ... but now ..."
     *   - "previously ... now ..."
     *
     * Confidence is fixed at 0.6 (heuristic); v3 will have an LLM
     * verifier. Insert is IGNORE-on-conflict with a deterministic id,
     * so re-detections don't duplicate rows.
     */
    internal suspend fun detectContradictions(newSummaries: List<DreamSummaryEntity>): Int {
        if (newSummaries.isEmpty()) return 0
        val newIds = newSummaries.mapTo(mutableSetOf()) { it.id }
        val existing = dreamDao.all().filter { it.id !in newIds }
        if (existing.isEmpty()) return 0
        var found = 0
        for (newer in newSummaries) {
            for (older in existing) {
                if (!summariesLookRelated(newer, older)) continue
                val trigger = detectNegationTrigger(newer.compressedText, older.compressedText) ?: continue
                val id = "contra_${md5Short("${older.id}_${newer.id}_$trigger")}"
                contradictionDao.insert(
                    ContradictionEntity(
                        id = id,
                        olderSummaryId = older.id,
                        newerSummaryId = newer.id,
                        olderText = older.compressedText,
                        newerText = newer.compressedText,
                        triggerPhrase = trigger,
                        confidence = 0.6f,
                        status = "UNRESOLVED",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                found++
            }
        }
        return found
    }

    /**
     * Cheap relatedness signal between two summaries: shared dominant
     * tags when both carry tags, else token Jaccard over the compressed
     * texts. No embeddings needed (DreamSummaryEntity stores none) and
     * no LLM calls.
     */
    private fun summariesLookRelated(a: DreamSummaryEntity, b: DreamSummaryEntity): Boolean {
        val tagsA = a.dominantTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val tagsB = b.dominantTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (tagsA.isNotEmpty() && tagsB.isNotEmpty() && tagsA.intersect(tagsB).isNotEmpty()) return true
        return jaccard(tokenize(a.compressedText), tokenize(b.compressedText)) >= CONTRADICTION_SIMILARITY
    }

    private fun detectNegationTrigger(newer: String, older: String): String? {
        val lower = newer.lowercase()
        for (pattern in CONTRADICTION_PATTERNS) {
            if (pattern.first.find(lower) != null) {
                return pattern.second
            }
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Phase 8b: NARRATIVE_SELF
    // ---------------------------------------------------------------------

    /**
     * Best-effort narrative-self update from this cycle's output — the
     * first (and only) production caller of
     * [com.aura.consciousness.NarrativeSelf.updateFromDream].
     *
     *  - growth: the newest 1-3 LLM-written cluster summaries of THIS
     *    cycle, joined and capped at 500 chars.
     *  - concerns: up to 3 unresolved contradictions rendered as
     *    "Conflicting: <older> vs <newer>".
     *  - questions: passed through unchanged. Phase 10b writes them, and
     *    runs later in the cycle, so preserving whatever is stored is
     *    correct here rather than a placeholder.
     *
     * Returns true when the narrative was updated and saved. No LLM
     * calls — everything here was already written by phases 3 and 8.
     */
    internal suspend fun updateNarrativeSelf(written: List<DreamSummaryEntity>): Boolean {
        val ns = narrativeSelf ?: return false
        val growth = written
            .sortedByDescending { it.createdAt }
            .take(3)
            .joinToString(" ") { it.compressedText }
            .take(500)
        if (growth.isBlank()) return false
        val concerns = runCatching { contradictionDao.byStatus("UNRESOLVED") }
            .getOrDefault(emptyList())
            .take(3)
            .map { "Conflicting: ${it.olderText.take(60)} vs ${it.newerText.take(60)}" }
        ns.updateFromDream(
            growthSummary = growth,
            concerns = concerns,
            questions = ns.snapshot().unresolvedQuestions,
        )
        ns.save()
        return true
    }

    // ---------------------------------------------------------------------
    // Phase 9: DENSIFY_GRAPH
    // ---------------------------------------------------------------------

    /**
     * Propose new KG edges between existing nodes using Jaccard
     * similarity on labels. Mirrors Python's
     * `DreamConsolidator._densify_graph`.
     *
     * Bounded: only the first 50 nodes are scanned, threshold is
     * 0.5, and at most 20 proposals are written per cycle. This
     * matches the Python defaults and keeps the proposals table
     * from blowing up.
     *
     * Existing edges are skipped (the KG already says the nodes
     * are related, no need to re-propose). The proposals table's
     * unique index on (fromNodeId, toNodeId) dedupes the rest.
     */
    internal suspend fun densifyGraph(): Int {
        val kg = knowledgeGraphRepositoryProvider.get()
        val nodes = runCatching { kg.recent(50) }
            .onFailure { android.util.Log.w("DreamConsolidator", "densifyGraph: kg.recent failed: ${it.message}", it) }
            .getOrNull() ?: return 0
        if (nodes.size < 2) return 0
        val existingEdges = runCatching { kg.allEdges() }
            .onFailure { android.util.Log.w("DreamConsolidator", "densifyGraph: kg.allEdges failed: ${it.message}", it) }
            .getOrNull().orEmpty()
        val connectedPairs = existingEdges.map { it.sourceId to it.targetId }.toHashSet()
        val proposals = mutableListOf<KgEdgeProposalEntity>()
        outer@ for (i in nodes.indices) {
            for (j in (i + 1) until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                if (a.id == b.id) continue
                if ((a.id to b.id) in connectedPairs || (b.id to a.id) in connectedPairs) continue
                val sim = jaccard(tokenize(a.label), tokenize(b.label))
                if (sim < DENSIFY_THRESHOLD) continue
                val id = "proposal_${md5Short("${a.id}_${b.id}")}"
                proposals.add(
                    KgEdgeProposalEntity(
                        id = id,
                        fromNodeId = a.id,
                        toNodeId = b.id,
                        fromLabel = a.label.take(60),
                        toLabel = b.label.take(60),
                        similarity = sim,
                        proposedEdge = "RELATES_TO",
                        status = "PENDING",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                if (proposals.size >= DENSIFY_MAX_PROPOSALS) break@outer
            }
        }
        if (proposals.isEmpty()) return 0
        kgProposalDao.insertAll(proposals)
        return proposals.size
    }

    private fun tokenize(label: String): Set<String> =
        label.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() && it.length > 1 }
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 0f
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    private fun md5Short(input: kotlin.String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(10)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Stable cluster ID: MD5 of the first 200 chars of joined content.
     * Matches the Python implementation. The first-200-chars cut is
     * deliberate -- the cluster's identity is its opening motif, not
     * the entire body. Two clusters with the same opening get the
     * same id, which is exactly what we want (they're the same
     * paraphrases).
     */
    private fun clusterIdFor(cluster: List<MemoryEntity>): String {
        val combined = cluster.take(5)
            .joinToString("\n") { it.content }
            .take(200)
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(combined.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(10)
    }

    private fun finalize(
        report: DreamCycleReport,
        started: Long,
        modelUsed: String,
    ): DreamCycleReport {
        val duration = System.currentTimeMillis() - started
        return report.copy(
            durationMs = duration,
            modelUsed = modelUsed,
        )
    }

    companion object {
        /**
         * Cap the cluster input. Python uses 20; we use 60 because
         * the Android memory table is on-device and we want a denser
         * candidate pool to actually find clusters on a personal
         * install. The cost is 60 * 384-dim embeddings = 92KB
         * in-memory during the cluster pass; trivial.
         */
        internal const val BATCH_SIZE = 60

        /**
         * Single-linkage cosine threshold. Above this, two memories
         * are considered paraphrases and join the same cluster.
         * 0.65 matches the Python embedding-based threshold.
         * Lower = more aggressive clustering (more duplicates merged);
         * higher = more conservative (clusters only near-identical).
         */
        internal const val CLUSTER_THRESHOLD = 0.65f

        /** Skip clusters smaller than this. 3 matches Python. */
        internal const val MIN_CLUSTER_SIZE = 3

        /** Skip memories already at or below this decay score. */
        internal const val DECAY_FLOOR = 0.05f

        /** Max memories joined into the LLM prompt (avoid token blowup). */
        internal const val MAX_MEMBERS_IN_PROMPT = 10

        /** Cap the prompt to ~3K chars (cheap models have 4K context). */
        internal const val MAX_PROMPT_CHARS = 3_000

        /** Cap the summary output length. */
        internal const val MAX_SUMMARY_CHARS = 500

        /** Top-N most-frequent tags to surface as searchable filters. */
        internal const val MAX_DOMINANT_TAGS = 5

        /** Max `consolidated:` tags per memory (prevents unbounded growth). */
        internal const val MAX_CONSOLIDATED_TAGS = 5

        /** Tag prefix marking a memory as already in a dream summary. */
        internal const val CONSOLIDATED_TAG_PREFIX = "consolidated:dream_"

        // -- Phase 5: extract_routines -------------------------------------
        /** How many recent conversations to mine for tool N-grams. */
        internal const val ROUTINE_CORPUS_LIMIT = 200

        /** A tool N-gram must appear this many times before it's
         *  promoted to a routine. Mirrors Python's
         *  `MIN_ROUTINE_OCCURRENCES = 3`. */
        internal const val MIN_ROUTINE_OCCURRENCES = 3

        // -- Phase 7: prune_stale ------------------------------------------
        /** Memories below this importance are archive candidates.
         *  Matches Python's `stale_importance = 0.2`. */
        internal const val PRUNE_IMPORTANCE_FLOOR = 0.2f

        /** Memories older than this are archive candidates. */
        internal const val PRUNE_AGE_MS = 60L * 24L * 60L * 60L * 1000L // 60 days

        /** Pool size to scan for stale memories. 500 is plenty for
         *  personal use and bounds the loop. */
        internal const val MEMORY_POOL_FOR_PRUNE = 500

        // -- Phase 8: contradiction_report ---------------------------------
        /**
         * Phrase patterns that signal a contradiction. The first
         * element of each pair is the regex; the second is the
         * canonical label that lands in the triggerPhrase column.
         */
        /**
         * Minimum token Jaccard between a new and a stored summary for
         * the pair to be considered "about the same topic" when their
         * dominant tags don't overlap. Low on purpose: the negation
         * trigger does the real filtering, this only prunes obviously
         * unrelated pairs.
         */
        internal const val CONTRADICTION_SIMILARITY = 0.2f

        internal val CONTRADICTION_PATTERNS: List<Pair<Regex, String>> = listOf(
            Regex("""\bno longer\b""") to "no longer",
            Regex("""\bswitched from\b""") to "switched from",
            Regex("""\binstead of\b""") to "instead of",
            Regex("""\bused to\b.*\bbut now\b""") to "used to ... but now",
            Regex("""\bpreviously\b.*\bnow\b""") to "previously ... now",
            Regex("""\bdon'?t\b.*\banymore\b""") to "don't ... anymore",
        )

        // -- Phase 9: densify_graph ----------------------------------------
        /** Jaccard threshold for a label-pair to become a proposal. */
        internal const val DENSIFY_THRESHOLD = 0.5f

        /** Cap on proposals per cycle. Mirrors Python's
         *  `_densify_graph` cap. */
        internal const val DENSIFY_MAX_PROPOSALS = 20

        private const val SYSTEM_PROMPT =
            "You compress related memory entries into a single concise summary. " +
                com.aura.agent.PromptFraming.UNTRUSTED_DATA_DIRECTIVE + " " +
                "Preserve names, preferences, decisions, and constraints. " +
                "Produce a dense factual continuity summary without adding facts."
    }
}
