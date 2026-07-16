package com.aura.evolution

import com.aura.agent.Conversation
import com.aura.providers.ChatOptions
import com.aura.providers.ModelRole
import com.aura.providers.ModelRoleRouter
import com.aura.providers.ProviderRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud-based reflection executor. Takes a system prompt and a user prompt,
 * routes them through the configured [ModelRole.EVOLUTION] model, and returns
 * the raw text. All work is bounded by [REFLECTION_TIMEOUT_MS] and a small
 * token budget.
 */
@Singleton
class EvolutionReflectionExecutor @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val roleRouter: ModelRoleRouter,
) {
    suspend fun reflect(systemPrompt: kotlin.String, userPrompt: kotlin.String): Result {
        val modelId = roleRouter.resolve(ModelRole.EVOLUTION)
            ?: return Result.Error("No EVOLUTION model configured", "no_model")
        val text = StringBuilder()
        return try {
            withTimeout(REFLECTION_TIMEOUT_MS) {
                val conversation = Conversation(systemPrompt = systemPrompt).addUser(userPrompt)
                providerRegistry.chat(
                    modelId,
                    conversation.toMessages(),
                    ChatOptions(temperature = 0.2, maxTokens = MAX_TOKENS),
                    emptyList(),
                ).collect { chunk ->
                    chunk.text?.let { text.append(it) }
                }
            }
            Result.Ok(text.toString().trim())
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.Error("Reflection timed out", "timeout")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error("Reflection failed: ${e.message ?: e.javaClass.simpleName}", "reflection_error")
        }
    }

    sealed interface Result {
        data class Ok(val text: kotlin.String) : Result
        data class Error(val message: kotlin.String, val code: kotlin.String) : Result
    }

    private companion object {
        const val REFLECTION_TIMEOUT_MS = 30_000L
        const val MAX_TOKENS = 1_200
    }
}
