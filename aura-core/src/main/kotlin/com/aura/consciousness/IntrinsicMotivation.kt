package com.aura.consciousness

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intrinsic Motivation System — gives Aura genuine drives beyond user requests.
 *
 * Ported from Python Aura's `consciousness/intrinsic_motivation.py`.
 *
 * Four drives:
 * 1. **Curiosity** — seek information about gaps in knowledge graph
 * 2. **Competence** — practice skills with low confidence scores
 * 3. **Social** — maintain connection quality (check in after absence)
 * 4. **Coherence** — resolve contradictions in knowledge base
 *
 * Drives are heuristic, not LLM-driven. They compute intensity from
 * observable signals (KG gap count, last interaction time, contradiction
 * count, skill confidence). The most urgent drive is injected into the
 * system prompt so the agent can proactively pursue it.
 *
 * Drive intensity ranges from 0 (fully satisfied) to 1 (highly motivated).
 * Urgency combines intensity with time-since-last-satisfied (builds over 24h).
 */
@Singleton
class IntrinsicMotivation @Inject constructor() {

    enum class DriveType { CURIOSITY, COMPETENCE, SOCIAL, COHERENCE }

    data class DriveState(
        val drive: DriveType,
        val intensity: Float,     // 0 = satisfied, 1 = highly motivated
        val satisfaction: Float,  // 0 = unsatisfied, 1 = fully satisfied
        val lastSatisfiedAt: Long, // epoch ms
        val triggers: List<String> = emptyList(),
    ) {
        /** How urgent this drive is (0-1). Combines intensity with time pressure. */
        val urgency: Float
            get() {
                val hoursSince = (System.currentTimeMillis() - lastSatisfiedAt) / 3_600_000f
                val timePressure = minOf(1f, hoursSince / 24f)
                return minOf(1f, intensity * 0.6f + timePressure * 0.4f)
            }
    }

    data class DriveAction(
        val drive: DriveType,
        val description: String,
        val priority: Float, // 0-1
    )

    private val _drives = MutableStateFlow<Map<DriveType, DriveState>>(defaultDrives())
    val drives: StateFlow<Map<DriveType, DriveState>> = _drives.asStateFlow()

    /**
     * Assess all drives from observable signals.
     *
     * @param kgGapCount Number of unexplored topics in the knowledge graph
     * @param lowConfidenceSkillCount Skills with confidence < 0.5
     * @param hoursSinceLastInteraction Time since last user message
     * @param contradictionCount Active contradictions in dream cycle
     */
    fun assess(
        kgGapCount: Int,
        lowConfidenceSkillCount: Int,
        hoursSinceLastInteraction: Float,
        contradictionCount: Int,
    ) {
        val now = System.currentTimeMillis()

        // Curiosity: more gaps → higher intensity
        val curiosityIntensity = minOf(1f, kgGapCount / 20f)
        // Competence: more weak skills → higher intensity
        val competenceIntensity = minOf(1f, lowConfidenceSkillCount / 5f)
        // Social: longer absence → higher intensity
        val socialIntensity = minOf(1f, hoursSinceLastInteraction / 12f)
        // Coherence: more contradictions → higher intensity
        val coherenceIntensity = minOf(1f, contradictionCount / 3f)

        val current = _drives.value
        _drives.value = mapOf(
            DriveType.CURIOSITY to current[DriveType.CURIOSITY]!!.copy(
                intensity = curiosityIntensity,
                satisfaction = 1f - curiosityIntensity,
                triggers = if (kgGapCount > 0) listOf("$kgGapCount unexplored topics") else emptyList(),
            ),
            DriveType.COMPETENCE to current[DriveType.COMPETENCE]!!.copy(
                intensity = competenceIntensity,
                satisfaction = 1f - competenceIntensity,
                triggers = if (lowConfidenceSkillCount > 0) listOf("$lowConfidenceSkillCount weak skills") else emptyList(),
            ),
            DriveType.SOCIAL to current[DriveType.SOCIAL]!!.copy(
                intensity = socialIntensity,
                satisfaction = 1f - socialIntensity,
                triggers = if (hoursSinceLastInteraction > 1f) listOf("${hoursSinceLastInteraction.toInt()}h since last interaction") else emptyList(),
            ),
            DriveType.COHERENCE to current[DriveType.COHERENCE]!!.copy(
                intensity = coherenceIntensity,
                satisfaction = 1f - coherenceIntensity,
                triggers = if (contradictionCount > 0) listOf("$contradictionCount contradictions") else emptyList(),
            ),
        )
    }

    /**
     * Mark a drive as satisfied (e.g. after the agent pursued it).
     */
    fun satisfy(drive: DriveType) {
        val current = _drives.value
        _drives.value = current + (drive to current[drive]!!.copy(
            intensity = 0.1f,
            satisfaction = 1f,
            lastSatisfiedAt = System.currentTimeMillis(),
        ))
    }

    /**
     * Get the most urgent drive, or null if all are satisfied.
     */
    fun mostUrgent(): DriveState? = _drives.value.values
        .maxByOrNull { it.urgency }
        ?.takeIf { it.urgency > 0.3f }

    /**
     * Format the most urgent drive for system prompt injection.
     * Returns empty string if no drive is urgent enough.
     */
    fun toPrompt(): String {
        val urgent = mostUrgent() ?: return ""
        val driveName = when (urgent.drive) {
            DriveType.CURIOSITY -> "curiosity"
            DriveType.COMPETENCE -> "competence"
            DriveType.SOCIAL -> "social connection"
            DriveType.COHERENCE -> "coherence"
        }
        val triggers = urgent.triggers.joinToString(", ")
        return "[Intrinsic motivation] Active drive: $driveName (${(urgent.urgency * 100).toInt()}% urgency)" +
            if (triggers.isNotBlank()) " — $triggers" else ""
    }

    private fun defaultDrives(): Map<DriveType, DriveState> = DriveType.entries.associateWith { drive ->
        DriveState(
            drive = drive,
            intensity = 0.3f,
            satisfaction = 0.7f,
            lastSatisfiedAt = System.currentTimeMillis(),
        )
    }
}