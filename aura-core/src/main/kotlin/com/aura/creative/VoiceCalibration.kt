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

/**
 * Voice calibration system. Learns the user's prose style and produces
 * a voice profile that other creative tools can reference to match
 * the user's voice in generated content.
 *
 * The profile captures:
 * - Sentence rhythm (average length, variation, fragment frequency)
 * - Vocabulary patterns (favorite words, formality level, sensory preferences)
 * - Dialogue style (how characters speak: interruptions, trailing off, formality)
 * - Narrative distance (close POV vs. omniscient, internal monologue frequency)
 * - Pacing (action-dominant vs. introspection-dominant)
 *
 * The profile is stored as a string that gets injected into system
 * prompts of other tools (CreativeEngine, ProseCraftTools) so they
 * can mirror the user's voice.
 */
@Singleton
class VoiceCalibration @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences,
    private val brain: com.aura.agent.Brain,
) {
    /**
     * Analyze a sample of the user's prose and produce a voice profile.
     *
     * @param sample 500-5000 words of the user's writing. The more, the better.
     * @return A voice profile string that can be injected into system prompts.
     */
    fun calibrate(sample: String): Flow<String> = flow {
        require(sample.length > 200) { "Need at least 200 characters of sample text." }
        val model = resolveModel()
        val systemPrompt = """
            You are a literary analyst. You analyze prose style with the precision of a writing professor.
            Your job: read the user's writing sample and produce a VOICE PROFILE that another AI can use to match their style.

            Analyze:
            1. SENTENCE RHYTHM: Average sentence length. Short vs. long ratio. Fragment frequency. How does the author use sentence length to create tension or flow?
            2. VOCABULARY: Formality level (1-10). Favorite sensory words. Abstract vs. concrete noun ratio. Any signature words or phrases?
            3. DIALOGUE STYLE: Do characters interrupt? Trail off? Use contractions? Speak formally or casually? How much subtext vs. directness?
            4. NARRATIVE DISTANCE: Close POV (inside the character's head) or distant (observing from outside)? How much internal monologue? How much description?
            5. PACING: Action-dominant or introspection-dominant? Scene-to-summary ratio. How quickly does the author move through time?
            6. SIGNATURE MOVES: 2-3 things this author does that are distinctive — techniques that make their writing recognizably THEIRS.

            Format the output as a concise reference guide (max 300 words) that an AI can follow to write in this voice. Be specific, not vague.
            "Short sentences for tension, long flowing sentences for description" is vague.
            "Average sentence: 12 words. Uses 2-3 word fragments for impact in action scenes. Dialogue is clipped — characters rarely finish sentences." is useful.
        """.trimIndent()

        val options = ChatOptions(temperature = 0.3, maxTokens = 1_500, thinkingBudget = 8_192)

        brain.stream(model, listOf(
            ProviderMessage(ProviderMessage.Role.system, systemPrompt),
            ProviderMessage(ProviderMessage.Role.user, "WRITING SAMPLE:\n\n${sample.take(8000)}"),
        ), emptyList(), options).collect { chunk ->
            when (chunk) {
                is com.aura.agent.BrainChunk.Text -> {
                    if (chunk.text.isNotEmpty()) emit(chunk.text)
                }
                is com.aura.agent.BrainChunk.Error -> throw IllegalStateException(chunk.message)
                else -> {}
            }
        }
    }

    /**
     * Apply a voice profile to a text transformation. This is the
     * "mirror" function — given a voice profile and a draft, rewrite
     * the draft in that voice.
     */
    fun mirror(
        text: String,
        voiceProfile: String,
    ): Flow<String> = flow {
        require(text.isNotBlank()) { "Text is required." }
        require(voiceProfile.isNotBlank()) { "Voice profile is required." }
        val model = resolveModel()
        val systemPrompt = """
            You are a voice matching engine. You rewrite text so it matches a specific voice profile exactly.
            Do not change the content, plot, or meaning. Change ONLY the prose style — sentence rhythm, vocabulary, dialogue patterns, narrative distance, and pacing.
            The voice profile below describes the target voice. Match it precisely.
            """.trimIndent()

        val options = ChatOptions(temperature = 0.5, maxTokens = 16_384, thinkingBudget = 8_192)

        brain.stream(model, listOf(
            ProviderMessage(ProviderMessage.Role.system, "$systemPrompt\n\n== VOICE PROFILE ==\n$voiceProfile"),
            ProviderMessage(ProviderMessage.Role.user, "TEXT TO REWRITE:\n\n$text"),
        ), emptyList(), options).collect { chunk ->
            when (chunk) {
                is com.aura.agent.BrainChunk.Text -> {
                    if (chunk.text.isNotEmpty()) emit(chunk.text)
                }
                is com.aura.agent.BrainChunk.Error -> throw IllegalStateException(chunk.message)
                else -> {}
            }
        }
    }

    private suspend fun resolveModel(): String {
        userPreferences.defaultModel.first()?.takeIf(String::isNotBlank)?.let { return it }
        for (provider in providerRegistry.configured()) {
            val model = runCatching { provider.listModels().firstOrNull() }.getOrNull()
            if (!model.isNullOrBlank()) return "${provider.prefix}:$model"
        }
        throw IllegalStateException("Configure an LLM provider before using voice calibration.")
    }
}