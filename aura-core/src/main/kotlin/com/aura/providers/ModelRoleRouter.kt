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

    /**
     * Reserved. Nothing reads this role — there is no verification pass in the
     * app for it to route.
     *
     * Kept as a constant rather than deleted so that the `verifier_model`
     * preference key, its `AuraBackup` field and any value a user already saved
     * all keep round-tripping, and the backup schema does not need a version
     * bump to remove a field. It is excluded from [configurable] so Settings
     * stops offering a row that changes nothing. Building the verification pass
     * is a feature; removing a control that does nothing is a fix, and this is
     * the fix.
     */
    VERIFIER("verifier_model", "Verifier"),
    EMBEDDING("embedding_model", "Embedding"),
    FAST("fast_model", "Fast"),
    REASONING("reasoning_model", "Reasoning"),
    EVOLUTION("evolution_model", "Evolution"),
    ;

    companion object {
        /**
         * Roles the user can configure in Settings.
         *
         * The bar for membership is that **something reads it**. A row that
         * persists a value, survives backup and restore, and routes nothing is
         * worse than no row: it looks like a working control.
         *
         * Excluded: EMBEDDING, managed separately as a capability rather than a
         * chat model; and VERIFIER, which has no consumer — see its KDoc.
         */
        val configurable: List<ModelRole> get() = listOf(
            CONVERSATION, BACKGROUND, DEEP_RESEARCH, FAST, REASONING, CREATIVE_DRAFT, CREATIVE_CRITIC, PLANNER, EVOLUTION,
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
     * The model the user explicitly chose for [role], or null if they have not
     * chosen one. No taste override, no fallback to the conversation default.
     *
     * This is the distinction [resolve] cannot express, and callers need both.
     * Settings needs it to tell "you picked this" apart from "this is what you'd
     * get" — without it, an unset Planner row displayed the conversation default
     * and looked configured. And a caller picking a cheap auxiliary model needs
     * it because falling back to the conversation default means running a
     * 50-token rerank on the user's flagship, which is the exact failure
     * [CheapModelHeuristic] exists to prevent.
     */
    suspend fun explicit(role: ModelRole): kotlin.String? =
        userPreferences.forRole(role).first()?.takeIf { it.isNotBlank() }

    /**
     * The model that will actually be used for [role]: the explicit choice, then
     * a taste-engine recommendation, then the conversation default.
     *
     * Returns null if no model is configured at all — the caller must handle
     * that honestly (show "configure a model" rather than invent one).
     *
     * The taste tier sits **below** the explicit preference, not above it. It
     * used to be first, which meant a learned recommendation could silently
     * override a model the user had deliberately pinned.
     *
     * Until the agentic loop was fixed to record under
     * [ModelRole.CONVERSATION]`.name`, this tier was dead: `resolve` queried
     * `bestModelForRole(role.name)` — "PLANNER", "CONVERSATION" — while the only
     * production writer recorded under "general" and "agent:<id>", so no key
     * matched. It is live now, which is why the ordering matters: a learned
     * recommendation informs an *unset* role and never overrules a set one.
     * Note that only CONVERSATION accumulates outcomes today, and
     * `UserPreferences.forRole(CONVERSATION)` is `defaultModel` — which almost
     * every install has set — so in practice the tier still resolves ahead of
     * nothing. The other roles fall straight through to the conversation
     * default, as they always have.
     */
    suspend fun resolve(role: ModelRole): kotlin.String? {
        // 1. What the user chose.
        explicit(role)?.let { return it }
        // 2. What the taste engine has learned, if the model is still reachable.
        if (tasteEngine != null) {
            val tasteModel = tasteEngine.bestModelForRole(role.name)
            if (!tasteModel.isNullOrBlank()) {
                val prefix = tasteModel.substringBefore(":", "")
                if (prefix.isNotBlank() && providerRegistry.all().any { it.prefix == prefix && it.isConfigured() }) {
                    return tasteModel
                }
            }
        }
        // 3. The conversation default.
        if (role != ModelRole.CONVERSATION) {
            val default = userPreferences.defaultModel.first()
            if (!default.isNullOrBlank()) return default
        }
        return null
    }

    /**
     * Observable form of [explicit] — emits the user's own choice for [role],
     * or null when they have not made one.
     *
     * Named for what it does. It was `observe`, which read as the observable
     * counterpart of [resolve] but shared none of its fallbacks, so a subscriber
     * saw null for a role that [resolve] would happily answer.
     */
    fun observeExplicit(role: ModelRole): Flow<kotlin.String?> =
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
            runCatching { provider.listModels() }
                .onFailure { android.util.Log.w("ModelRoleRouter", "listModels failed for ${provider.prefix}", it) }
                .getOrDefault(emptyList())
        }.distinct()
    }
}