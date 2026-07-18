package com.aura.emotion

/**
 * 6 adaptive response profiles inspired by Kira's IRIS system.
 * Each profile appends a tone directive to the system prompt.
 *
 * The [EmotionEngine] maps its 4D state to one of these profiles
 * via threshold rules. The mapping is intentionally simple —
 * the model interprets the tone hint naturally.
 */
enum class ResponseProfile(val promptSuffix: kotlin.String) {
    NEUTRAL(""),
    WARM("\n\nTone: Be warm and friendly. Expand on topics with enthusiasm."),
    FOCUSED("\n\nTone: Be concise and technical. Stay on point."),
    ENERGETIC("\n\nTone: Be dynamic and fast-paced. Match the user's energy."),
    CALM("\n\nTone: Be calm and gentle. Reassure and slow the pace."),
    DIRECT("\n\nTone: Be terse and direct. No pleasantries, just answers."),
    ;

    companion object {
        /**
         * Map an [EmotionEngine.EmotionSnapshot] to a [ResponseProfile].
         * Rules are evaluated in priority order (first match wins).
         */
        fun from(s: EmotionEngine.EmotionSnapshot): ResponseProfile = when {
            s.tension > 0.7f && s.connection < 0.3f -> DIRECT
            s.tension > 0.6f && s.energy > 0.6f -> ENERGETIC
            s.connection > 0.7f && s.tension < 0.3f -> WARM
            s.focus > 0.7f && s.energy < 0.4f -> FOCUSED
            s.energy < 0.3f && s.tension < 0.3f -> CALM
            else -> NEUTRAL
        }
    }
}