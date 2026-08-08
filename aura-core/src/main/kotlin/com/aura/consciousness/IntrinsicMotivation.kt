package com.aura.consciousness

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
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
 *
 * Persistence: JSON file at `files/intrinsic_motivation.json`, same shape as
 * [NarrativeSelf]. Loaded on app start by `ProactiveBootstrap`, saved after
 * each [assess] and [satisfy].
 *
 * The drives lived only in memory until 2026-08-08, which made
 * [DriveState.urgency]'s time-pressure term dead code: `lastSatisfiedAt` was
 * re-stamped to *now* on every process start, so the "builds over 24h" ramp
 * could only run while the process stayed alive — and Android reclaims it long
 * before that. Every cold start reset the drives to their 0.3 defaults. This is
 * the same defect ENGINEERING_HISTORY §2.4 records fixing for `EmotionEngine`;
 * it survived here and in [TheoryOfMind].
 */
@Singleton
class IntrinsicMotivation @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(context.filesDir, "intrinsic_motivation.json")

    @Serializable
    enum class DriveType { CURIOSITY, COMPETENCE, SOCIAL, COHERENCE }

    @Serializable
    data class DriveState(
        val drive: DriveType = DriveType.CURIOSITY,
        val intensity: Float = 0.3f,     // 0 = satisfied, 1 = highly motivated
        val satisfaction: Float = 0.7f,  // 0 = unsatisfied, 1 = fully satisfied
        val lastSatisfiedAt: Long = 0L,  // epoch ms
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

    private val _drives = MutableStateFlow<Map<DriveType, DriveState>>(defaultDrives())
    val drives: StateFlow<Map<DriveType, DriveState>> = _drives.asStateFlow()

    /**
     * Load the persisted drives. Called from `ProactiveBootstrap` on app start.
     *
     * Stored as a list rather than a `Map<DriveType, DriveState>`: each element
     * already carries its own `drive`, and a list avoids relying on enum-keyed
     * map encoding. Any drive missing from the file keeps its default, so a
     * future new [DriveType] loads cleanly against an older file.
     *
     * A missing or corrupt file leaves the in-memory drives untouched rather
     * than resetting them — bootstrap runs concurrently with the first turn's
     * [assess], and clobbering that would reintroduce the reset this exists to
     * prevent.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching
            val stored = json.decodeFromString(ListSerializer(DriveState.serializer()), file.readText())
            if (stored.isEmpty()) return@runCatching
            _drives.value = defaultDrives() + stored.associateBy { it.drive }
        }.onFailure { Log.w("IntrinsicMotivation", "load failed: ${it.message}", it) }
        Unit
    }

    /** Persist the current drives. Called after each [assess] and [satisfy]. */
    suspend fun save() = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(json.encodeToString(ListSerializer(DriveState.serializer()), _drives.value.values.toList()))
        }.onFailure { Log.w("IntrinsicMotivation", "save failed: ${it.message}", it) }
        Unit
    }

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