package com.aura.agent

import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCTS-lite reasoning tree (ported concept from Python Aura's
 * `mcts_reasoning.py` / `reasoning_tree_tool.py`).
 *
 * For hard multi-step questions, instead of a single linear plan, the
 * tree generates 2-3 DISTINCT approach branches, scores each with a
 * cheap-model value call, and commits to the highest-scoring branch.
 * The winning approach is injected into the system prompt as a plan
 * prefix — the model then executes the best strategy rather than
 * improvising.
 *
 * This is "lite" MCTS: one level of expansion + one level of value
 * estimation. No recursive rollouts — bounded to 2 cheap LLM calls
 * (expansion + scoring) with a hard timeout, so it never blocks the
 * user's real answer for more than a few seconds.
 *
 * Cost: at most 2 auxiliary calls on hard questions only (>80 chars,
 * step 1, strategy = MULTI_STEP_REFLECT or planning enabled).
 * Falls back to null (no plan) on any error — the loop runs normally.
 */
@Singleton
class ReasoningTree @Inject constructor(
    private val brain: Brain,
) {
    companion object {
        const val MAX_BRANCHES = 3
        const val MIN_MESSAGE_LENGTH = 80
        const val EXPAND_TIMEOUT_MS = 12_000L
        const val SCORE_TIMEOUT_MS = 8_000L
        const val VALUE_THRESHOLD = 0.4
    }

    data class Branch(
        val summary: String,
        val score: Double,
    )

    /**
     * Generate branches for a hard question, score them, and return the
     * best one's summary as a plan prefix (or null to skip).
     *
     * @return the winning branch summary, or null when the question is
     * too short / the model calls fail / all branches score below
     * [VALUE_THRESHOLD].
     */
    suspend fun bestApproach(userMessage: String, modelId: String): String? {
        if (userMessage.length < MIN_MESSAGE_LENGTH) return null
        val branches = expand(userMessage, modelId) ?: return null
        if (branches.size < 2) return branches.firstOrNull()
        val scored = branches.map { summary ->
            Branch(summary, score(summary, userMessage, modelId))
        }
        val best = scored.maxByOrNull { it.score } ?: return null
        if (best.score < VALUE_THRESHOLD) return null
        return best.summary
    }

    /** Expand the question into 2-3 distinct approach summaries. */
    internal suspend fun expand(userMessage: String, modelId: String): List<String>? {
        return try {
            val messages = listOf(
                ProviderMessage(
                    role = ProviderMessage.Role.system,
                    content = "You are a reasoning strategist. For the user's request, propose $MAX_BRANCHES DISTINCT approaches. " +
                        "Each approach must be genuinely different in strategy (e.g. different tool usage, different decomposition, different angle). " +
                        "Format: one approach per line, each starting with 'A1:', 'A2:', 'A3:'. " +
                        "Keep each approach to 1-2 sentences. No preamble.",
                ),
                ProviderMessage(role = ProviderMessage.Role.user, content = userMessage),
            )
            val chunks = withTimeoutOrNull(EXPAND_TIMEOUT_MS) {
                brain.stream(
                    modelId,
                    messages,
                    options = ChatOptions(temperature = 0.7, maxTokens = 300),
                ).toList()
            } ?: return null
            val text = chunks.filterIsInstance<BrainChunk.Text>()
                .joinToString("") { it.text }
                .trim()
            val branches = text.lines()
                .mapNotNull { line ->
                    val cleaned = line.trim()
                        .removePrefix("A1:").removePrefix("A2:").removePrefix("A3:")
                        .trim()
                    cleaned.takeIf { it.length in 10..300 }
                }
                .take(MAX_BRANCHES)
            if (branches.size >= 2) branches else null
        } catch (e: Exception) {
            null
        }
    }

    /** Score one branch 0.0-1.0 with a cheap-model value call. */
    internal suspend fun score(summary: String, userMessage: String, modelId: String): Double {
        return try {
            val messages = listOf(
                ProviderMessage(
                    role = ProviderMessage.Role.system,
                    content = "You evaluate reasoning strategies. Given the user's request and a proposed approach, " +
                        "score how likely this approach leads to a correct, efficient answer. " +
                        "Reply with ONLY a number between 0.0 and 1.0.",
                ),
                ProviderMessage(
                    role = ProviderMessage.Role.user,
                    content = "REQUEST: $userMessage\n\nAPPROACH: $summary",
                ),
            )
            val chunks = withTimeoutOrNull(SCORE_TIMEOUT_MS) {
                brain.stream(
                    modelId,
                    messages,
                    options = ChatOptions(temperature = 0.0, maxTokens = 20),
                ).toList()
            } ?: return 0.0
            val text = chunks.filterIsInstance<BrainChunk.Text>()
                .joinToString("") { it.text }
                .trim()
            text.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
        } catch (e: Exception) {
            0.0
        }
    }
}
