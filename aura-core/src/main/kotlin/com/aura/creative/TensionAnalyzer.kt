package com.aura.creative

import com.aura.data.UserPreferences
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Tension heatmap analyzer. Reads the user's manuscript and analyzes
 * pacing scene-by-scene, producing a tension curve that shows where
 * the story rises, falls, or flatlines.
 *
 * The analysis produces:
 * - A 1-10 tension score per scene
 * - Pacing diagnosis: where does the story drag, rush, or flow well?
 * - Specific recommendations: "Scene 4 is flat — add a reversal or
 *   a stake-raising event" or "Scenes 7-9 are all high tension with
 *   no breathing room — add a quiet scene for contrast."
 *
 * This is a developmental editor, not just a continuity checker.
 */
@Singleton
class TensionAnalyzer @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences,
    private val brain: com.aura.agent.Brain,
) {
    /**
     * Result of a tension analysis.
     */
    data class TensionReport(
        val sceneScores: List<SceneScore>,
        val diagnosis: String,
        val recommendations: List<String>,
    )

    data class SceneScore(
        val sceneLabel: String,
        val tension: Int, // 1-10
        val note: String,
    )

    /**
     * Analyze the manuscript and produce a tension heatmap.
     *
     * @param manuscript The full manuscript text. Scenes are detected
     *   by scene breaks (*** or # or double newlines).
     * @return A tension report with per-scene scores and recommendations.
     */
    fun analyze(manuscript: String): Flow<String> = flow {
        require(manuscript.length > 500) { "Need at least 500 characters to analyze." }
        val model = resolveModel()
        val systemPrompt = """
            You are a developmental editor specializing in pacing and tension analysis.
            You read manuscripts and produce a TENSION HEATMAP — a scene-by-scene analysis
            of where the story's tension rises, falls, and flatlines.

            For each scene, assign a tension score from 1 (calm/low stakes) to 10 (peak crisis).
            Look for:
            - STAKES: What does the character stand to lose in this scene?
            - CONFLICT: Is there active opposition (internal or external)?
            - REVERSAL: Does the situation change mid-scene?
            - INFORMATION: Does the reader learn something that raises the stakes?
            - EMOTIONAL INTENSITY: How much does the character feel?

            After scoring each scene, provide:
            1. PACING DIAGNOSIS: Where does the story drag? Where does it rush?
       Which scenes are flat? Which are over too quickly?
            2. RECOMMENDATIONS: 3-5 specific, actionable fixes.
       "Scene 4 is flat — add a reversal or a stake-raising event."
       "Scenes 7-9 are all high tension with no breathing room — add a quiet scene for contrast."
       "The climax (scene 12) comes too early — move it later or add a false resolution first."

            Format:
            SCENE 1: [score]/10 — [one-line note]
            SCENE 2: [score]/10 — [one-line note]
            ...

            PACING DIAGNOSIS:
            [2-3 paragraphs]

            RECOMMENDATIONS:
            1. [specific fix]
            2. [specific fix]
            ...
        """.trimIndent()

        // Split into chunks if the manuscript is very long
        val chunks = splitManuscript(manuscript)
        val options = ChatOptions(temperature = 0.3, maxTokens = 6_000, thinkingBudget = 16_384)

        for ((index, chunk) in chunks.withIndex()) {
            val userPrompt = if (chunks.size > 1) {
                "MANUSCRIPT PART ${index + 1} of ${chunks.size}:\n\n$chunk"
            } else {
                "MANUSCRIPT:\n\n$chunk"
            }

            brain.stream(model, listOf(
                ProviderMessage(ProviderMessage.Role.system, systemPrompt),
                ProviderMessage(ProviderMessage.Role.user, userPrompt),
            ), emptyList(), options).collect { chunk2 ->
                when (chunk2) {
                    is com.aura.agent.BrainChunk.Text -> {
                        if (chunk2.text.isNotEmpty()) emit(chunk2.text)
                    }
                    is com.aura.agent.BrainChunk.Error -> throw IllegalStateException(chunk2.message)
                    else -> {}
                }
            }
        }
    }

    /**
     * Split a long manuscript into chunks that fit within context.
     * Each chunk is ~15K characters (~4K tokens).
     */
    private fun splitManuscript(text: String): List<String> {
        val maxChunkSize = 15_000
        if (text.length <= maxChunkSize) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + maxChunkSize, text.length)
            // Try to break at a scene boundary (*** or # or double newline)
            val breakPoint = if (end < text.length) {
                val searchRange = text.substring(end - 500, end)
                val sceneBreak = searchRange.lastIndexOf("***")
                val hashBreak = searchRange.lastIndexOf("\n# ")
                val newlineBreak = searchRange.lastIndexOf("\n\n")
                maxOf(sceneBreak, hashBreak, newlineBreak)
            } else { -1 }

            val actualEnd = if (breakPoint > 0) end - 500 + breakPoint + 3 else end
            chunks.add(text.substring(start, actualEnd.coerceAtMost(text.length)))
            start = actualEnd
        }
        return chunks
    }

    private suspend fun resolveModel(): String {
        userPreferences.defaultModel.first()?.takeIf(String::isNotBlank)?.let { return it }
        for (provider in providerRegistry.configured()) {
            val model = runCatching { provider.listModels().firstOrNull() }.onFailure { Log.w("TensionAnalyzer", "runCatching failed: ${it.message}", it) }.getOrNull()
            if (!model.isNullOrBlank()) return "${provider.prefix}:$model"
        }
        throw IllegalStateException("Configure an LLM provider before using tension analysis.")
    }
}