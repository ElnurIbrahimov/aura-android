package com.aura.evolution

import com.aura.proactive.ActionCount
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates evolution candidates for proactive rule changes based on
 * interaction history. Only proposes candidates; apply is gated by the
 * evolution inbox + human approval.
 */
@Singleton
class EvolutionProactiveRuleGenerator @Inject constructor() {

    fun generate(
        summary: List<ActionCount>,
        existingRules: List<String>,
    ): List<EvolutionCandidateEntity> {
        val byAction = summary.associate { it.action to it.count }
        val total = byAction.values.sum().coerceAtLeast(1)
        val dismissed = byAction["dismissed"] ?: 0
        val acted = byAction["acted"] ?: 0
        val candidates = mutableListOf<EvolutionCandidateEntity>()

        if (dismissed.toFloat() / total > 0.6f) {
            candidates.add(
                EvolutionCandidateEntity(
                    id = "proactive_suppress_high_dismiss",
                    domain = EvolutionDomain.PROACTIVE.name,
                    action = EvolutionAction.NEW_PROACTIVE_RULE.name,
                    targetId = "proactive_policy",
                    rationale = "${(dismissed.toFloat()/total*100).toInt()}% of proactive events were dismissed.",
                    score = dismissed.toFloat() / total,
                )
            )
        }
        if (acted.toFloat() / total > 0.4f) {
            candidates.add(
                EvolutionCandidateEntity(
                    id = "proactive_prioritize_high_acted",
                    domain = EvolutionDomain.PROACTIVE.name,
                    action = EvolutionAction.NEW_PROACTIVE_RULE.name,
                    targetId = "proactive_policy",
                    rationale = "${(acted.toFloat()/total*100).toInt()}% of proactive events led to action.",
                    score = acted.toFloat() / total,
                )
            )
        }
        if ((byAction["snoozed"] ?: 0) > 3 && "morning_brief_time" !in existingRules) {
            candidates.add(
                EvolutionCandidateEntity(
                    id = "proactive_review_morning_brief_time",
                    domain = EvolutionDomain.PROACTIVE.name,
                    action = EvolutionAction.NEW_PROACTIVE_RULE.name,
                    targetId = "morning_brief_time",
                    rationale = "Several morning briefs were snoozed, suggesting a suboptimal time.",
                    score = 0.6f,
                )
            )
        }
        return candidates
    }
}
