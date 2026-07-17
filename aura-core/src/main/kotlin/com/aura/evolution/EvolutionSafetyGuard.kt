package com.aura.evolution

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mandatory safety gate before any evolution proposal is created or applied.
 *
 * Rules:
 * 1. Only memory and proactive policy changes may auto-apply. Skill code
 *    changes always require explicit user approval.
 * 2. API keys / OAuth tokens / credentials must never appear in proposal
 *    patchJson or rationale.
 * 3. A proposal cannot target a blocked domain (e.g. security settings).
 */
@Singleton
class EvolutionSafetyGuard @Inject constructor() {
    private val blockedDomains = setOf(
        "security",
        "credentials",
        "auth",
    )

    private val credentialPatterns = listOf(
        // OpenAI: sk-...
        Regex("sk-[a-zA-Z0-9]{20,}", RegexOption.IGNORE_CASE),
        // Anthropic: sk-ant-...
        Regex("sk-ant-[a-zA-Z0-9_-]{20,}", RegexOption.IGNORE_CASE),
        // Google/Gemini: AIza...
        Regex("AIza[a-zA-Z0-9_-]{35}", RegexOption.IGNORE_CASE),
        // DeepSeek: sk-... (covered by OpenAI pattern, but explicit for clarity)
        // Groq: gsk_...
        Regex("gsk_[a-zA-Z0-9]{20,}", RegexOption.IGNORE_CASE),
        // OpenRouter: sk-or-...
        Regex("sk-or-[a-zA-Z0-9_-]{20,}", RegexOption.IGNORE_CASE),
        // Together AI: ... (varies, but often a long hex token)
        Regex("[a-f0-9]{40,}", RegexOption.IGNORE_CASE),
        // Brave: BSA... (Brave Search API keys)
        Regex("BSA[a-zA-Z0-9]{30,}", RegexOption.IGNORE_CASE),
        // Tavily: tvly-...
        Regex("tvly-[a-zA-Z0-9_-]{20,}", RegexOption.IGNORE_CASE),
        // ElevenLabs: ...
        Regex("[a-f0-9]{32}", RegexOption.IGNORE_CASE),
        // Generic Bearer token patterns
        Regex("Bearer\\s+[a-zA-Z0-9._-]{20,}", RegexOption.IGNORE_CASE),
    )

    fun canAutoApply(domain: String): Boolean {
        return domain == EvolutionDomain.MEMORY.name || domain == EvolutionDomain.PROACTIVE.name
    }

    fun isBlockedDomain(domain: String): Boolean {
        return domain.lowercase() in blockedDomains
    }

    fun containsCredentialLeak(text: String): Boolean {
        return credentialPatterns.any { it.containsMatchIn(text) }
    }

    fun validateProposal(candidate: EvolutionCandidateEntity): Result<Unit> {
        if (isBlockedDomain(candidate.domain)) {
            return Result.failure(IllegalArgumentException("Domain ${candidate.domain} is blocked"))
        }
        if (containsCredentialLeak(candidate.argsJson) || containsCredentialLeak(candidate.rationale)) {
            return Result.failure(SecurityException("Credential leak detected in candidate"))
        }
        return Result.success(Unit)
    }
}