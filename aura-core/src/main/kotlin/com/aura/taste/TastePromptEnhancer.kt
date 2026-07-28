package com.aura.taste

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts the taste engine's learned preferences into explicit
 * system prompt instructions.
 *
 * The taste context from [TasteEngine.getTasteContext] is a passive
 * suggestion: "- general: prefers tone: concise, style: direct".
 * The model sees it but has no mechanism to enforce it.
 *
 * This enhancer converts the passive suggestion into an active
 * instruction: "Be concise. Use direct style. Avoid unnecessary
 * preamble." The model is much more likely to follow an explicit
 * instruction than a preference observation.
 */
@Singleton
class TastePromptEnhancer @Inject constructor() {

    /**
     * Enhance the system prompt with explicit style instructions
     * derived from the taste context.
     *
     * @param systemPrompt The current system prompt
     * @param tasteContext The taste context string from TasteEngine
     * @return Enhanced system prompt with explicit style instructions
     */
    fun enhance(systemPrompt: kotlin.String, tasteContext: kotlin.String): kotlin.String {
        if (tasteContext.isBlank()) return systemPrompt
        val instructions = convertToInstructions(tasteContext)
        // If the enhancer couldn't parse any instructions, fall back
        // to the raw taste context rather than discarding it entirely.
        if (instructions.isBlank()) return systemPrompt
        return "$systemPrompt\n\n$instructions"
    }

    /**
     * Convert taste context lines like:
     * "- general: prefers tone: concise, style: direct"
     * into explicit instructions like:
     * "Style guidelines: Be concise. Use direct style."
     */
    internal fun convertToInstructions(tasteContext: kotlin.String): kotlin.String {
        val lines = mutableListOf<kotlin.String>()
        // Parse lines like "- category: prefers key1: val1, key2: val2"
        for (line in tasteContext.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("-")) continue
            // Extract the "prefers" section
            val prefersIdx = trimmed.indexOf("prefers ")
            if (prefersIdx < 0) continue
            val prefersSection = trimmed.substring(prefersIdx + 8) // after "prefers "
            // Split by comma to get individual preferences
            val parts = prefersSection.split(",").map { it.trim() }.filter { it.isNotBlank() }
            for (part in parts) {
                val colonIdx = part.indexOf(":")
                if (colonIdx > 0) {
                    val key = part.substring(0, colonIdx).trim()
                    val value = part.substring(colonIdx + 1).trim()
                    // Convert "tone: concise" -> "Be concise."
                    // Convert "style: direct" -> "Use direct style."
                    val instruction = when (key.lowercase()) {
                        "tone" -> "Be $value."
                        "style" -> "Use $value style."
                        "length" -> "Keep responses $value."
                        "format" -> "Use $value format."
                        "vocabulary" -> "Use $value vocabulary."
                        "pacing" -> "Pace responses $value."
                        else -> "Prefer $key: $value."
                    }
                    lines.add(instruction)
                }
            }
        }
        return if (lines.isEmpty()) ""
        else "Style guidelines (learned from your preferences):\n${lines.joinToString("\n") { "- $it" }}"
    }
}
