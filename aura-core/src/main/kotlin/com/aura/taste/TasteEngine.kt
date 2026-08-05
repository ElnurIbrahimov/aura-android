package com.aura.taste

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Log

/**
 * The Taste Twin — learns from user preference signals and
 * produces weighted style attributes that influence prompt
 * construction, candidate ranking, and model routing.
 *
 * Implementation is purely statistical (weighted averages over
 * structured signals) — no ML model training required.
 *
 * All signals are local, inspectable, and reversible.
 */
@Singleton
class TasteEngine @Inject constructor(
    private val signalDao: PreferenceSignalDao,
    private val profileDao: StyleProfileDao,
    private val routingDao: RoutingOutcomeDao,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()

    /**
     * Record a preference signal. Positive weight for accept/like,
     * negative for reject/dislike.
     */
    suspend fun recordSignal(
        projectId: kotlin.String = "",
        signalType: kotlin.String,
        category: kotlin.String,
        artifactId: kotlin.String? = null,
        attributes: Map<kotlin.String, kotlin.String> = emptyMap(),
        weight: Float = 1.0f,
        agentScope: kotlin.String = "general",
    ) = mutex.withLock {
        signalDao.upsert(
            PreferenceSignalEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                signalType = signalType,
                category = category,
                artifactId = artifactId,
                attributesJson = json.encodeToString(attributes),
                weight = weight,
                agentScope = agentScope,
            ),
        )
    }

    /**
     * Record a reaction (emoji or thumbs up/down).
     */
    suspend fun recordReaction(
        projectId: kotlin.String = "",
        artifactId: kotlin.String,
        reaction: kotlin.String,
        positive: kotlin.Boolean,
    ) {
        // Delete any previous reaction for this artifact so switching
        // from 👍 to 👎 replaces the signal instead of accumulating
        // contradictory rows.
        signalDao.deleteReactionsForArtifact(artifactId)
        recordSignal(
            projectId = projectId,
            signalType = "reaction",
            category = "general",
            artifactId = artifactId,
            attributes = mapOf("reaction" to reaction),
            weight = if (positive) 1.0f else -1.0f,
        )
    }

    /**
     * Record a user edit (user modified the generated output).
     * The edit implies the original was insufficient.
     */
    suspend fun recordEdit(
        projectId: kotlin.String = "",
        artifactId: kotlin.String,
        editType: kotlin.String = "text",
    ) {
        recordSignal(
            projectId = projectId,
            signalType = "edit",
            category = editType,
            artifactId = artifactId,
            weight = -0.5f,
        )
    }

    /**
     * Record a model routing outcome for learning.
     */
    suspend fun recordRoutingOutcome(
        modelRole: kotlin.String,
        modelId: kotlin.String,
        success: kotlin.Boolean,
        latencyMs: kotlin.Long = 0L,
        costClass: kotlin.String = "unknown",
        outcomeType: kotlin.String = "user_accepted",
        agentScope: kotlin.String = "general",
    ) {
        routingDao.upsert(
            RoutingOutcomeEntity(
                id = UUID.randomUUID().toString(),
                modelRole = modelRole,
                modelId = modelId,
                success = success,
                latencyMs = latencyMs,
                costClass = costClass,
                outcomeType = outcomeType,
                agentScope = agentScope,
            ),
        )
    }

    /**
     * Recompute the style profile for a project (or global if
     * projectId is empty). Aggregates all signals into weighted
     * attributes.
     */
    suspend fun recomputeProfile(projectId: kotlin.String = "") = mutex.withLock {
        val signals = if (projectId.isBlank()) {
            signalDao.global(500)
        } else {
            signalDao.forProject(projectId, 500)
        }

        if (signals.isEmpty()) return@withLock

        // Aggregate: group by category, compute weighted averages
        val byCategory = signals.groupBy { it.category }
        val attributes = mutableMapOf<kotlin.String, MutableMap<kotlin.String, Float>>()

        for ((category, categorySignals) in byCategory) {
            val attrs = mutableMapOf<kotlin.String, Float>()
            for (signal in categorySignals) {
                val parsed = runCatching {
                    json.decodeFromString<Map<kotlin.String, kotlin.String>>(signal.attributesJson)
                }.onFailure { Log.w("TasteEngine", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyMap())

                for ((key, value) in parsed) {
                    // Bucket by "<key>:<value>" so "tone:concise" and "style:concise"
                    // don't collapse into the same bucket. The previous code used
                    // `value` as the key, losing the attribute dimension entirely.
                    val bucket = "$key:$value"
                    val current = attrs.getOrDefault(bucket, 0f)
                    attrs[bucket] = current + signal.weight
                }
            }
            if (attrs.isNotEmpty()) {
                // Normalize by absolute total weight so negative-only categories
                // don't flip sign. Use a floor of 1f to avoid divide-by-zero.
                val totalWeight = categorySignals.sumOf { kotlin.math.abs(it.weight).toDouble() }.toFloat().coerceAtLeast(1f)
                attrs.forEach { (k, v) -> attrs[k] = v / totalWeight }
                attributes[category] = attrs
            }
        }

        val profileId = profileDao.forProject(projectId)?.id ?: UUID.randomUUID().toString()
        profileDao.upsert(
            StyleProfileEntity(
                id = profileId,
                projectId = projectId,
                attributesJson = json.encodeToString(attributes),
                signalCount = signals.size,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Get the current style profile for a project (or global).
     * Falls back to global profile if project profile doesn't exist.
     */
    suspend fun getProfile(projectId: kotlin.String = ""): StyleProfileEntity? {
        if (projectId.isNotBlank()) {
            profileDao.forProject(projectId)?.let { return it }
        }
        return profileDao.global()
    }

    /** Get profile filtered by agent scopes (general + agent-private). */
    suspend fun getProfileForScopes(scopes: List<kotlin.String>): StyleProfileEntity? {
        return profileDao.forScopes(scopes) ?: getProfile()
    }

    /**
     * Get the best model for a given role, based on routing outcomes.
     * Returns null if no data.
     */
    suspend fun bestModelForRole(role: kotlin.String): kotlin.String? {
        val stats = routingDao.statsForRole(role)
        if (stats.isEmpty()) return null
        return stats
            .filter { it.count >= 2 }
            .maxByOrNull { it.successes.toFloat() / it.count.toFloat() }
            ?.modelId
    }

    /**
     * Clear all signals for a project (or global).
     */
    suspend fun clearSignals(projectId: kotlin.String = "") {
        if (projectId.isBlank()) {
            signalDao.deleteGlobal()
        } else {
            signalDao.deleteForProject(projectId)
        }
    }

    /**
     * Delete a single signal by ID.
     */
    suspend fun deleteSignal(id: kotlin.String) {
        signalDao.delete(id)
    }

    /**
     * Build a taste context string for system prompt injection.
     * Reads the global style profile and returns a compact summary
     * of the user's learned preferences, or empty string if no
     * signals have been recorded yet.
     */
    suspend fun getTasteContext(scopes: List<kotlin.String> = listOf("general")): kotlin.String {
        val profile = getProfileForScopes(scopes) ?: return ""
        val attrs = runCatching {
            json.decodeFromString<Map<kotlin.String, Map<kotlin.String, Float>>>(profile.attributesJson)
        }.onFailure { Log.w("TasteEngine", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyMap())
        if (attrs.isEmpty()) return ""

        val lines = mutableListOf<kotlin.String>()
        for ((category, categoryAttrs) in attrs) {
            val top = categoryAttrs.entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString(", ") { (bucket, _) ->
                    // Buckets are stored as "key:value"; render them as
                    // "key: value" so the model sees the attribute dimension.
                    val parts = bucket.split(":", limit = 2)
                    if (parts.size == 2) "${parts[0]}: ${parts[1]}" else bucket
                }
            lines.add("- $category: prefers $top")
        }
        if (lines.isEmpty()) return ""
        return "\n\n# User taste preferences (learned from signals):\n${lines.joinToString("\n")}"
    }
}

/**
 * Serializable style profile attributes for prompt construction.
 */
@Serializable
data class StyleAttributes(
    val tone: kotlin.String = "",
    val pacing: kotlin.String = "",
    val vocabulary: kotlin.String = "",
    val palette: kotlin.String = "",
    val composition: kotlin.String = "",
    val voiceStyle: kotlin.String = "",
    val musicStyle: kotlin.String = "",
)