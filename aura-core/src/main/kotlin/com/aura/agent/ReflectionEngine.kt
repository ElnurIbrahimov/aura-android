package com.aura.agent

import android.util.Log
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderMessage.Role
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reflection engine for the agentic loop.
 *
 * After a failed run (max_steps_exceeded or multiple tool errors), the
 * model writes a short reflection on what went wrong and what to try
 * differently. The reflection is stored on the conversation and injected
 * into the system prompt on the next run for the same conversation.
 *
 * This turns blind retries into self-correcting retries: instead of
 * the model making the same mistake again, it sees "last time I tried
 * X and it failed because Y; this time I should try Z."
 *
 * Non-blocking: if the LLM call fails or times out, the loop continues
 * without a reflection. The reflection is a quality enhancement, not
 * a correctness requirement.
 */
@Singleton
class ReflectionEngine @Inject constructor(
    private val brain: Brain,
) {
    /**
     * Generate a reflection on a failed agentic loop run.
     *
     * @param userMessage The user's original request
     * @param toolErrors List of (toolName, errorMessage) pairs for tools that failed
     * @param maxSteps The max steps the loop was allowed
     * @param model The model to use for reflection (should be cheap)
     * @return A 1-2 sentence reflection string, or null on failure/timeout
     */
    suspend fun reflect(
        userMessage: kotlin.String,
        toolErrors: List<Pair<kotlin.String, kotlin.String>>,
        maxSteps: Int,
        model: kotlin.String,
    ): kotlin.String? {
        val errorSummary = if (toolErrors.isEmpty()) {
            "Ran out of steps ($maxSteps) without completing the task."
        } else {
            val errors = toolErrors.take(5).joinToString("\n") { (name, err) ->
                "- Tool '$name' failed: $err"
            }
            "Encountered tool errors:\n$errors\n\nRan out of steps ($maxSteps)."
        }

        val systemPrompt = buildString {
            append("You are a reflection assistant. The user asked a question and the AI assistant ")
            append("tried to answer it using tools, but failed. Analyze what went wrong and what ")
            append("should be done differently next time. Be specific and actionable. ")
            append("Answer in 1-2 sentences. Do not include any other text.\n\n")
        }

        val userPrompt = buildString {
            append("User request: ${userMessage.take(500)}\n\n")
            append("What happened:\n$errorSummary\n\n")
            append("What should the assistant do differently next time?")
        }

        val messages = listOf(
            ProviderMessage(role = Role.system, content = systemPrompt),
            ProviderMessage(role = Role.user, content = userPrompt),
        )

        return try {
            val builder = StringBuilder()
            withTimeoutOrNull(REFLECTION_TIMEOUT_MS) {
                brain.stream(
                    model, messages,
                    options = ChatOptions(temperature = 0.3, maxTokens = 150),
                ).collect { chunk ->
                    if (chunk is BrainChunk.Text) builder.append(chunk.text)
                }
            }
            val result = builder.toString().trim()
            if (result.isNotBlank()) result else null
        } catch (e: Exception) {
            Log.w("ReflectionEngine", "reflection failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val REFLECTION_TIMEOUT_MS = 10_000L
    }
}
