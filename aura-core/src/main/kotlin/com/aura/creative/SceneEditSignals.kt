package com.aura.creative

import android.util.Log
import com.aura.evolution.EvolutionHooks
import com.aura.skills.SkillsStore
import com.aura.taste.TasteEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How much of a scene survived its author.
 *
 * Kept ratio in [0,1]: twice the common lines over the total, a multiset
 * intersection over trimmed non-blank lines, with a sentence-split fallback
 * for prose that barely breaks lines. Cheap on purpose: no model, and no
 * O(n*m) edit distance over seven-thousand-character scenes.
 */
object EditDistanceLite {
    fun keepRatio(before: String, after: String): Float {
        val a = units(before)
        val b = units(after)
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val counts = HashMap<String, Int>(a.size * 2)
        for (unit in a) counts[unit] = (counts[unit] ?: 0) + 1
        var common = 0
        for (unit in b) {
            val remaining = counts[unit] ?: 0
            if (remaining > 0) {
                counts[unit] = remaining - 1
                common++
            }
        }
        return (2f * common / (a.size + b.size)).coerceIn(0f, 1f)
    }

    private fun units(text: String): List<String> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.size >= 3) return lines
        return text.split(Regex("[.!?]+")).map { it.trim() }.filter { it.isNotBlank() }
    }
}

/**
 * The taste loop's writer: every save of a scene teaches the profile, and a
 * rewrite teaches evolution that the craft guidance failed its author.
 *
 * Weights are priors to be tuned by reading real profiles, not argued over:
 * keeping a scene untouched is mild approval (+0.5, weaker than a thumbs-up),
 * a touch-up is mild dissatisfaction, recordEdit's designed -0.5 covers the
 * middle, and a rewrite is the full -1.0 — symmetric with accept. Every
 * signal recomputes both the project profile and the global one.
 *
 * The craft bridge fires only on rewrites, as `skill_failed` evidence against
 * the craft skill's id — the resolvable-id lesson — and touches none of
 * evolution's gates: evidence is off unless evolutionEnabled, the detector
 * needs three failures in fourteen days, and SKILL never auto-applies.
 */
@Singleton
class SceneEditSignals @Inject constructor(
    private val tasteEngine: TasteEngine,
    private val skillsStore: SkillsStore? = null,
    private val evolutionHooks: EvolutionHooks? = null,
) {
    suspend fun onSceneKept(projectId: String, artifactId: String) {
        runCatching {
            tasteEngine.recordSignal(
                projectId = projectId,
                signalType = "accept",
                category = CATEGORY,
                artifactId = artifactId,
                weight = 0.5f,
            )
            tasteEngine.recomputeProfile(projectId)
            tasteEngine.recomputeProfile("")
        }.onFailure { Log.w(TAG, "keep signal failed: ${it.message}", it) }
    }

    suspend fun onSceneEdited(
        projectId: String,
        templateId: String,
        artifactId: String,
        beforeText: String,
        afterText: String,
    ) {
        val kept = EditDistanceLite.keepRatio(beforeText, afterText)
        runCatching {
            when {
                kept >= LIGHT_EDIT_FLOOR -> tasteEngine.recordSignal(
                    projectId = projectId,
                    signalType = "edit",
                    category = CATEGORY,
                    artifactId = artifactId,
                    attributes = mapOf("keepRatio" to "high"),
                    weight = -0.25f,
                )
                kept >= REWRITE_CEILING -> tasteEngine.recordEdit(
                    projectId = projectId,
                    artifactId = artifactId,
                    editType = CATEGORY,
                )
                else -> tasteEngine.recordSignal(
                    projectId = projectId,
                    signalType = "rewrite",
                    category = CATEGORY,
                    artifactId = artifactId,
                    weight = -1.0f,
                )
            }
            tasteEngine.recomputeProfile(projectId)
            tasteEngine.recomputeProfile("")
        }.onFailure { Log.w(TAG, "edit signal failed: ${it.message}", it) }
        if (kept < REWRITE_CEILING) {
            runCatching {
                val skillId = skillsStore
                    ?.findByName(CraftSkills.templateSkillName(templateId))
                    ?.id ?: return
                evolutionHooks?.onSkillFailed(skillId, errorCode = "author_rewrote")
            }.onFailure { Log.w(TAG, "craft evidence failed: ${it.message}", it) }
        }
    }

    companion object {
        private const val TAG = "SceneEditSignals"

        const val CATEGORY = "scene_prose"

        /** At or above this, the author barely touched it. */
        const val LIGHT_EDIT_FLOOR = 0.8f

        /** Below this, the author rewrote the scene. */
        const val REWRITE_CEILING = 0.5f
    }
}
