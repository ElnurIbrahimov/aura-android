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
        Regex("sk-[a-zA-Z0-9]{20,}", RegexOption.IGNORE_CASE),
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