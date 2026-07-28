package com.aura.evolution

import android.util.Log
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderMessage.Role
import com.aura.providers.ProviderRegistry
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real evaluation metrics for the evolution system.
 *
 * Replaces the toy token-length Gaussian in [EvolutionShadowEvaluator]
 * with two LLM-based evaluators:
 *
 * 1. Self-consistency: asks the model the same question twice and
 *    compares the answers for semantic similarity. High consistency
 *    means the model is reliable on this type of task.
 *
 * 2. LLM-as-judge: asks a cheap model to score the response quality
 *    on a 0-1 scale. The judge sees the user's question and the
 *    assistant's answer and rates it.
 *
 * Both are cheap LLM calls (200 tokens, 5s timeout, non-blocking).
 * If either fails, the composite falls back to the existing
 * token-length heuristic.
 */
@Singleton
class EvolutionEvaluators @Inject constructor(
    private val providerRegistry: ProviderRegistry,
) {
    /**
     * Evaluate a response using self-consistency + LLM-as-judge.
     *
     * @param userMessage The user's original question
     * @param response The assistant's response to evaluate
     * @param model Cheap model ID for evaluation calls
     * @return Composite score in [0, 1], or null on failure
     */
    suspend fun evaluate(
        userMessage: kotlin.String,
        response: kotlin.String,
        model: kotlin.String,
    ): kotlin.Float? {
        val consistency = runCatching {
            evaluateSelfConsistency(userMessage, model)
        }.onFailure { Log.w("EvolutionEvaluators", "self-consistency failed: ${it.message}") }
            .getOrNull()

        val judge = runCatching {
            evaluateJudge(userMessage, response, model)
        }.onFailure { Log.w("EvolutionEvaluators", "judge failed: ${it.message}") }
            .getOrNull()

        return when {
            consistency != null && judge != null -> 0.4f * consistency + 0.6f * judge
            consistency != null -> consistency
            judge != null -> judge
            else -> null
        }
    }

    /**
     * Self-consistency: ask the model the same question twice and
     * compare the answers. If both answers are semantically similar,
     * the model is reliable on this task type.
     *
     * Returns a [0, 1] score where 1 = perfectly consistent.
     */
    private suspend fun evaluateSelfConsistency(
        question: kotlin.String,
        model: kotlin.String,
    ): kotlin.Float {
        val prompt = "Answer this question in one sentence: ${question.take(200)}"
        val messages = listOf(
            ProviderMessage(role = Role.user, content = prompt),
        )
        val options = ChatOptions(temperature = 0.3, maxTokens = 100)

        val answer1 = collectResponse(model, messages, options)
        val answer2 = collectResponse(model, messages, options)

        if (answer1.isBlank() || answer2.isBlank()) return 0.5f

        // Simple similarity: word overlap ratio
        val words1 = answer1.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val words2 = answer2.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (words1.isEmpty() || words2.isEmpty()) return 0.5f
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        return (intersection.toFloat() / union).coerceIn(0f, 1f)
    }

    /**
     * LLM-as-judge: ask a cheap model to rate the response quality.
     *
     * Returns a [0, 1] score where 1 = excellent.
     */
    private suspend fun evaluateJudge(
        question: kotlin.String,
        response: kotlin.String,
        model: kotlin.String,
    ): kotlin.Float {
        val systemPrompt = "You are a response quality judge. Rate the response on a scale of 0.0 to 1.0. " +
            "Consider: Does it answer the question? Is it accurate? Is it helpful? " +
            "Return ONLY a single number (e.g. 0.8). No other text."
        val userPrompt = "Question: ${question.take(200)}\n\nResponse: ${response.take(500)}\n\nScore:"

        val messages = listOf(
            ProviderMessage(role = Role.system, content = systemPrompt),
            ProviderMessage(role = Role.user, content = userPrompt),
        )
        val options = ChatOptions(temperature = 0.0, maxTokens = 10)

        val raw = collectResponse(model, messages, options).trim()
        // Parse the first float in the response
        val match = Regex("""([0-9]*\.?[0-9]+)""").find(raw)
        return match?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
    }

    private suspend fun collectResponse(
        model: kotlin.String,
        messages: List<ProviderMessage>,
        options: ChatOptions,
    ): kotlin.String = runCatching {
        val builder = StringBuilder()
        withTimeoutOrNull(JUDGE_TIMEOUT_MS) {
            providerRegistry.chat(model, messages, options).collect { chunk ->
                chunk.text?.let { builder.append(it) }
            }
        }
        builder.toString().trim()
    }.getOrDefault("")

    companion object {
        private const val JUDGE_TIMEOUT_MS = 5_000L
    }
}
