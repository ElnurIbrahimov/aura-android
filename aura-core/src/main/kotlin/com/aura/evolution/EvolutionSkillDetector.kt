package com.aura.evolution

import com.aura.skills.Skill
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic and LLM-assisted detectors for skill-related evolution
 * candidates. Heuristics are cheap and offline; the reflection prompt is
 * only invoked when reflection is enabled for the SKILL domain.
 */
@Singleton
class EvolutionSkillDetector @Inject constructor() {

    /**
     * Detect likely missing skill from a repeated request pattern. Returns
     * a candidate only when the same imperative phrase appears at least
     * [threshold] times and no enabled skill has a matching name or body.
     */
    fun detectMissingSkillFromRequests(
        requests: List<kotlin.String>,
        skills: List<Skill>,
        threshold: Int = 3,
    ): EvolutionCandidateEntity? {
        val normalized = requests.map { normalizeImperative(it) }
        val freq = normalized.groupingBy { it }.eachCount().filter { it.value >= threshold }
        if (freq.isEmpty()) return null
        val best = freq.maxBy { it.value }
        val stopWords = setOf("please", "can", "you", "me", "my", "the", "a", "an", "for", "to", "of", "in")
        val verbs = best.key.split(" ").filter { it !in stopWords }
        val alreadyCovered = skills.any { skill ->
            verbs.any { skill.name.contains(it, ignoreCase = true) || skill.body.contains(it, ignoreCase = true) }
        }
        if (alreadyCovered) return null
        return EvolutionCandidateEntity(
            id = "skill_missing_${best.key}",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.CREATE_SKILL.name,
            targetId = "skill_${best.key}",
            score = minOf(0.5f + 0.1f * best.value, 0.95f),
            rationale = "User asked to '${best.key}' ${best.value} times but no skill covers it.",
            argsJson = "{}",
        )
    }

    /**
     * Build the reflection prompt for a high-confidence skill candidate.
     * The LLM receives redacted context and returns a JSON-like verdict.
     */
    fun buildReflectionPrompt(candidate: EvolutionCandidateEntity, recentRequests: List<kotlin.String>): kotlin.String = """
        You are reviewing a candidate skill for Aura.

        Candidate action: ${candidate.action}
        Target id: ${candidate.targetId}
        Trigger: ${candidate.rationale}

        Recent user requests (redacted to first 10):
        ${recentRequests.take(10).joinToString("\n") { "- $it" }}

        Instructions:
        1. Return ONLY a single line: VERDICT: approve | reject
        2. If you reject, add REASON: <one sentence>
        3. Approve only if the candidate would automate a clear, repeated task
           that does not involve secrets, payment, or irreversible actions.
    """.trimIndent()

    private fun normalizeImperative(text: kotlin.String): kotlin.String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
            .split(Regex("\\s+"))
            .take(2)
            .joinToString(" ")
    }
}
