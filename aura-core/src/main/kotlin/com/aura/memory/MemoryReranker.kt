package com.aura.memory

import com.aura.agent.Brain
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
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
        } catch (e: Exception) {
            // Reranking is best-effort — fall back to RRF order
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
        val scores = mutableMapOf<Int, Float>()
        val batches = candidates.chunked(BATCH_SIZE)

        for ((batchIdx, batch) in batches.withIndex()) {
            val offset = batchIdx * BATCH_SIZE
            val batchScores = scoreOneBatch(query, batch, model)
            batchScores.forEach { (localIdx, score) ->
                scores[offset + localIdx] = score
            }
        }

        return scores
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
        } catch (e: Exception) {
            return batch.indices.associateWith { 0.5f } // neutral fallback
        }

        // Parse lines as floats
        val lines = response.toString()
            .trim()
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                line.trim()
                    .removePrefix("Memory ")
                    .removeSuffix(":")
                    .split(":").lastOrNull()
                    ?.trim()
                    ?.toFloatOrNull()
            }

        return batch.indices.associateWith { idx ->
            lines.getOrNull(idx) ?: 0.5f
        }
    }

    companion object {
        const val RERANK_TIMEOUT_MS = 10_000L
        const val BATCH_SIZE = 4
        const val MAX_CONTENT_CHARS = 500
    }
}