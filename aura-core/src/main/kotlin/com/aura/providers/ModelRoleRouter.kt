package com.aura.providers

import com.aura.data.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task roles that map to user-configured models. Each role has a
 * [UserPreferences] preference key and a fallback chain.
 *
 * The router never hardcodes a model ID. If the user hasn't configured
 * a role-specific model, it falls back to the default model.
 */
enum class ModelRole(val key: kotlin.String, val displayName: kotlin.String) {
    CONVERSATION("default_model", "Conversation"),
    BACKGROUND("background_model", "Background"),
    DEEP_RESEARCH("deep_mode_model", "Deep Research"),
    CREATIVE_DRAFT("creative_draft_model", "Creative Draft"),
    CREATIVE_CRITIC("creative_critic_model", "Creative Critic"),
    PLANNER("planner_model", "Planner"),
    VERIFIER("verifier_model", "Verifier"),
    EMBEDDING("embedding_model", "Embedding"),
    FAST("fast_model", "Fast"),
    REASONING("reasoning_model", "Reasoning"),
    EVOLUTION("evolution_model", "Evolution"),
    ;

    companion object {
        /**
         * Roles the user can explicitly configure in Settings.
         * EMBEDDING is managed separately (it's a capability, not a chat model).
         */
        val configurable: List<ModelRole> get() = listOf(
            CONVERSATION, BACKGROUND, DEEP_RESEARCH, FAST, REASONING, CREATIVE_DRAFT, CREATIVE_CRITIC, PLANNER, VERIFIER, EVOLUTION,
        )
    }
}

/**
 * Resolves a model ID for a [ModelRole] from user preferences, with
 * fallback to the default conversation model. Never invents or
 * hardcodes a model ID.
 */
@Singleton
class ModelRoleRouter @Inject constructor(
    private val userPreferences: UserPreferences,
    private val providerRegistry: ProviderRegistry,
    private val tasteEngine: com.aura.taste.TasteEngine? = null,
) {
    /**
     * Returns the user's preferred model for [role], or the default
     * model if no role-specific preference is set.
     *
     * If [tasteEngine] is available and has routing data for this role,
     * the taste-recommended model is preferred over the user preference
     * — but only if the recommended model is still from a configured
     * provider.
     *
     * Returns null if no model is configured at all — the caller must
     * handle this honestly (show "configure a model" prompt).
     */
    suspend fun resolve(role: ModelRole): kotlin.String? {
        // 1. Taste-engine recommendation (if data exists)
        if (tasteEngine != null) {
            val tasteModel = tasteEngine.bestModelForRole(role.name)
            if (!tasteModel.isNullOrBlank()) {
                // Verify the taste-recommended model is from a configured provider
                val prefix = tasteModel.substringBefore(":", "")
                if (prefix.isNotBlank() && providerRegistry.all().any { it.prefix == prefix && it.isConfigured() }) {
                    return tasteModel
                }
            }
        }
        // 2. Role-specific preference
        val roleModel = userPreferences.forRole(role).first()
        if (!roleModel.isNullOrBlank()) return roleModel
        // 3. Fallback: conversation default
        if (role != ModelRole.CONVERSATION) {
            val default = userPreferences.defaultModel.first()
            if (!default.isNullOrBlank()) return default
        }
        return null
    }

    /**
     * Observable flow of the model for [role]. Emits the role-specific
     * model, or the default model if not set.
     */
    fun observe(role: ModelRole): Flow<kotlin.String?> =
        userPreferences.forRole(role).map { roleModel ->
            roleModel.takeIf { !it.isNullOrBlank() }
        }

    /**
     * Returns true if any model is configured for [role] (either
     * role-specific or the default fallback).
     */
    suspend fun isConfigured(role: ModelRole): kotlin.Boolean {
        return resolve(role) != null
    }

    /**
     * Lists all models available from configured providers that could
     * serve [role]. Uses the live provider catalog, not hardcoded lists.
     */
    suspend fun availableModels(role: ModelRole): List<kotlin.String> {
        val configured = providerRegistry.configured()
        return configured.flatMap { provider ->
            runCatching { provider.listModels() }.getOrDefault(emptyList())
        }.distinct()
    }
}