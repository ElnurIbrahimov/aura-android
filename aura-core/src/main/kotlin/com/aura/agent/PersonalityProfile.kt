package com.aura.agent

import kotlinx.serialization.Serializable

/**
 * 6-dimension personality profile for an agent. Injected into the
 * system prompt as tone directives so the model adapts its style
 * per-agent, not just per-mood.
 *
 * All dimensions are 0..1 where 0.5 is neutral.
 */
@Serializable
data class PersonalityProfile(
    val warmth: Float = 0.5f,
    val formality: Float = 0.5f,
    val verbosity: Float = 0.5f,
    val humor: Float = 0.3f,
    val proactivity: Float = 0.5f,
    val riskTolerance: Float = 0.5f,
) {
    /**
     * Convert the profile into a tone directive string appended
     * to the system prompt. Uses thresholds (>0.7, <0.3) so midrange
     * values produce no directive (the model uses its default style).
     */
    fun toPromptDirective(): String = buildString {
        if (warmth > 0.7f) append("Be warm and friendly. ")
        else if (warmth < 0.3f) append("Be direct and businesslike. ")
        if (formality > 0.7f) append("Use formal language. ")
        else if (formality < 0.3f) append("Be casual. ")
        if (verbosity > 0.7f) append("Be thorough and detailed. ")
        else if (verbosity < 0.3f) append("Be concise. ")
        if (humor > 0.7f) append("Use humor where appropriate. ")
        else if (humor < 0.3f) append("Stay serious. ")
        if (proactivity > 0.7f) append("Anticipate follow-up needs. ")
        if (riskTolerance > 0.7f) append("Suggest creative alternatives. ")
        else if (riskTolerance < 0.3f) append("Prefer proven approaches. ")
    }.trim().let { if (it.isBlank()) "" else "\n\nTone: $it" }

    companion object {
        val General = PersonalityProfile(0.6f, 0.4f, 0.5f, 0.5f, 0.5f, 0.5f)
        val Coder = PersonalityProfile(0.3f, 0.7f, 0.3f, 0.2f, 0.7f, 0.3f)
        val Researcher = PersonalityProfile(0.5f, 0.7f, 0.7f, 0.2f, 0.7f, 0.5f)
        val Writer = PersonalityProfile(0.7f, 0.3f, 0.6f, 0.6f, 0.5f, 0.7f)
        val Creative = PersonalityProfile(0.7f, 0.2f, 0.5f, 0.7f, 0.5f, 0.8f)
        val Executive = PersonalityProfile(0.3f, 0.7f, 0.2f, 0.2f, 0.6f, 0.3f)
        val PhoneNative = PersonalityProfile(0.4f, 0.3f, 0.2f, 0.4f, 0.6f, 0.5f)
    }
}