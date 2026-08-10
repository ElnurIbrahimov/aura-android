package com.aura.memory

import android.util.Log
import com.aura.agent.Brain
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-encoder reranker for memory retrieval.
 *
 * After RRF picks the top-N candidates, the reranker asks a small LLM
 * to score each (query, memory) pair for actual semantic relevance.
 * This catches cases where BM25 and vector search both miss — e.g.
 * "that thing we discussed" has zero lexical overlap with "the
 * database migration strategy from Tuesday" but a reranker understands
 * the intent.
 *
 * Batches 4 candidates per LLM call to minimize round trips. Falls
 * back to the original RRF order if the model is unavailable or errors.
 */
@Singleton
class MemoryReranker @Inject constructor(
    private val brain: Brain,
) {
    /**
     * Rerank [candidates] by asking a small model to score each
     * (query, memory) pair for relevance to [query].
     *
     * @param query The user's original query
     * @param candidates RRF top-N results (already ranked by RRF)
     * @param model The model to use for reranking (should be small/fast)
     * @param topK How many to return after reranking
     * @return Reranked candidates, or original order if reranking fails
     */
    suspend fun rerank(
        query: String,
        candidates: List<MemoryEntity>,
        model: String,
        topK: Int = 5,
    ): List<MemoryEntity> {
        if (candidates.size <= 1 || topK <= 0) return candidates.take(topK)

        return try {
            withTimeout(RERANK_TIMEOUT_MS) {
                val scores = scoreBatch(query, candidates, model)
                // Sort by reranker score descending, take topK
                candidates.indices
                    .map { it to scores.getOrDefault(it, 0f) }
                    .sortedByDescending { it.second }
                    .take(topK)
                    .map { candidates[it.first] }
            }
        } catch (e: TimeoutCancellationException) {
            // Expected under a slow or overloaded model, and falling back is
            // correct — but it must be visible. A reranker that has timed out on
            // every query for a month is indistinguishable from one that works,
            // because the output is always a plausible list of memories.
            Log.w(TAG, "rerank timed out after ${RERANK_TIMEOUT_MS}ms; using RRF order", e)
            candidates.take(topK)
        } catch (e: CancellationException) {
            // The CALLER gave up: a cancelled recall, a closed conversation.
            // Swallowing it breaks cooperative cancellation and leaves the model
            // call running for a result nobody is waiting for. The previous
            // `catch (e: Exception)` caught this, because CancellationException
            // is an Exception — the defect this repo has fixed twice before.
            throw e
        } catch (e: Exception) {
            // Anything else is a real failure: no model configured, a provider
            // error, a malformed score response. Silent, this made a
            // permanently broken reranker look exactly like a working one — in
            // a repo that runs a CI gate on logging hygiene for this reason.
            Log.w(TAG, "rerank failed (${e.javaClass.simpleName}); using RRF order: ${e.message}", e)
            candidates.take(topK)
        }
    }

    /**
     * Score all candidates in batches of [BATCH_SIZE]. Each batch
     * sends 4 (query, memory) pairs in one LLM call and asks the
     * model to return 4 scores. This reduces N LLM calls to ceil(N/4).
     */
    private suspend fun scoreBatch(
        query: String,
        candidates: List<MemoryEntity>,
        model: String,
    ): Map<Int, Float> {
        val batches = candidates.chunked(BATCH_SIZE)

        // Run all batches in parallel — each batch is one LLM call.
        // Sequential for-loop made 20 candidates = 5 sequential calls;
        // parallel makes it 1 call wall time.
        return coroutineScope {
            batches.mapIndexed { batchIdx, batch ->
                async {
                    val offset = batchIdx * BATCH_SIZE
                    val batchScores = scoreOneBatch(query, batch, model)
                    batchScores.mapKeys { (localIdx, score) -> offset + localIdx }
                }
            }.awaitAll().fold(emptyMap<Int, Float>()) { acc, map -> acc + map }
        }
    }

    /**
     * Score a single batch of up to 4 candidates. The prompt asks
     * the model to return one float per candidate on a new line.
     */
    private suspend fun scoreOneBatch(
        query: String,
        batch: List<MemoryEntity>,
        model: String,
    ): Map<Int, Float> {
        val systemPrompt = buildString {
            append("You are a relevance judge. For each memory, rate how relevant it is to the query. ")
            append("Return ONLY one number per memory on its own line, between 0.0 (irrelevant) and 1.0 (perfectly relevant). ")
            append("Do not include any other text, explanation, or formatting.\n\n")
            append("Query: $query\n\n")
            for ((i, mem) in batch.withIndex()) {
                append("Memory ${i + 1}: ${mem.content.take(MAX_CONTENT_CHARS)}\n")
            }
            append("\nReturn one score per line:")
        }

        val messages = listOf(
            ProviderMessage(
                role = ProviderMessage.Role.system,
                content = systemPrompt,
            ),
            ProviderMessage(
                role = ProviderMessage.Role.user,
                content = "Score the ${batch.size} memories above.",
            ),
        )

        val options = ChatOptions(temperature = 0.0, maxTokens = 50)
        val response = StringBuilder()

        try {
            brain.stream(model, messages, options = options).collect { chunk ->
                if (chunk is com.aura.agent.BrainChunk.Text) {
                    response.append(chunk.text)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A neutral 0.5 for the whole batch is not a small fallback: every
            // candidate in it ties, so `sortedByDescending` leaves them in RRF
            // order while the batches that DID score get reordered around them.
            // The result is a half-reranked list that looks reranked.
            Log.w(TAG, "batch scoring failed for ${batch.size} candidates; scoring them neutral: ${e.message}", e)
            return batch.indices.associateWith { 0.5f }
        }

        // Parse lines as floats. Models return scores in various formats:
        // "0.8", "1. 0.8", "Memory 1: 0.8", "- 0.8", "1) 0.8"
        // Strip all prefixes/labels and extract the first float on each line.
        val responseText = response.toString()
        val rawLines = responseText.trim().lines().filter { it.isNotBlank() }

        // Parse each line as (optional index, score). If the model
        // included "Memory N:" or "N." or "N)" prefixes, we can
        // match score to the candidate by index. Otherwise fall
        // back to positional order. P1 MEMORY B4: pre-fix, the
        // parser was strictly positional — if the model returned
        // scores out of order (e.g. "Memory 4: 0.8\nMemory 1: 0.7")
        // or skipped a line, the wrong score would be assigned to
        // the wrong candidate, silently degrading rerank quality.
        data class ParsedLine(val candidateIdx: Int?, val score: Float)
        val parsedLines = rawLines.mapNotNull { line ->
            val cleaned = line.trim()
                .replace(Regex("""^\d+[.)]\s+"""), "") // "1. " or "1) " — REQUIRES whitespace
                .replace(Regex("""^[-*]\s+"""), "")     // "- " or "* " — REQUIRES whitespace
                .replace(Regex("""(?i)^Memory\s*(\d+)\s*:?\s*"""), "$1 ") // "Memory 1: " → "1 "
                .trim()
            // Try to find an explicit candidate index (1-based)
            // at the start of the line. If present, strip it
            // so the score regex doesn't grab the index as
            // the score (e.g. "4 0.9" — index=4, score=0.9,
            // not index=null, score=4.0).
            val idxMatch = Regex("""^(\d+)\s+(?=\d*\.?\d+)""").find(cleaned)
            val explicitIdx = idxMatch?.groupValues?.get(1)?.toIntOrNull()
            val scoreCleaned = idxMatch?.let { cleaned.removePrefix(idxMatch.value) } ?: cleaned
            // Extract the first float from the (cleaned) remaining text
            val score = Regex("""\d*\.?\d+""").find(scoreCleaned)?.value?.toFloatOrNull()
                ?: return@mapNotNull null
            val candidateIdx = explicitIdx?.let { if (it in 1..batch.size) it - 1 else null }
            ParsedLine(candidateIdx, score.coerceIn(0f, 1f))
        }

        // Build the score map. If a line had an explicit candidate
        // index, use it. Otherwise assign positionally to remaining
        // candidates in order. If counts mismatch, log a warning
        // so the user can see the model is mis-behaving.
        val result = mutableMapOf<Int, Float>()
        val usedByIdx = mutableSetOf<Int>()
        // First pass: explicit indices
        for (pl in parsedLines) {
            val idx = pl.candidateIdx
            if (idx != null && idx !in usedByIdx) {
                result[idx] = pl.score
                usedByIdx.add(idx)
            }
        }
        // Second pass: positional for the rest
        val remaining = parsedLines.filter { it.candidateIdx == null }
        val positionalIdx = batch.indices.filter { it !in usedByIdx }
        for ((pl, idx) in remaining.zip(positionalIdx)) {
            result[idx] = pl.score
            usedByIdx.add(idx)
        }
        // Default remaining candidates to neutral (0.5f) so the
        // ranking is stable even when the model under-responds.
        for (idx in batch.indices) {
            if (idx !in result) result[idx] = 0.5f
        }
        if (parsedLines.size != batch.size) {
            Log.w(
                "MemoryReranker",
                "reranker response had ${parsedLines.size} score lines for ${batch.size} candidates " +
                "(some scores defaulted to 0.5)",
            )
        }
        return result
    }

    companion object {
        private const val TAG = "MemoryReranker"
        const val RERANK_TIMEOUT_MS = 10_000L
        const val BATCH_SIZE = 4
        const val MAX_CONTENT_CHARS = 500
    }
}