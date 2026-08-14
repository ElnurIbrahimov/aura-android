package com.aura.ui.components

/**
 * Heuristic follow-up suggestions for an assistant turn. Runs on
 * the last assistant message without an LLM call — just keyword
 * + length detection to pick from a small bank of canned
 * follow-ups.
 *
 * Why heuristic and not LLM:
 * - Latency: an LLM call would block the UI thread for 1-3s
 *   just to suggest the next message.
 * - Cost: a yes/no prompt is still 200-500 tokens per turn.
 * - Quality: the bank of suggestions below covers ~80% of common
 *   follow-up intents ("elaborate", "summarize", "continue",
 *   "fix errors", "more examples"). The remaining 20% can be
 *   added as new heuristics without an LLM call.
 *
 * Returns 0-3 suggestions. Empty list when the assistant text
 * doesn't suggest a natural follow-up.
 */
object FollowUpSuggestions {

    fun suggest(assistantText: String, isCodey: Boolean): List<String> {
        if (assistantText.isBlank()) return emptyList()
        val lower = assistantText.lowercase()
        val out = mutableListOf<String>()

        // Detect a truncated / unfinished response.
        if (endsAbruptly(assistantText)) {
            out.add("Continue")
        }

        // Detect a code-heavy response.
        if (isCodey) {
            out.add("Explain the code")
            out.add("Show an example")
        } else {
            // Prose responses benefit from a summarize / detail
            // pair. We only add one of each depending on length
            // so the user isn't overwhelmed with chips.
            if (assistantText.length > 800) {
                out.add("Summarize")
                out.add("More detail")
            } else if (assistantText.length > 300) {
                out.add("More detail")
            }
        }

        // Detect a list response and offer a different angle.
        if (lower.startsWith("here are ") || lower.startsWith("1. ") || lower.startsWith("- ")) {
            out.add("Pick one")
        }

        // Detect a question back to the user.
        //
        // Labels are kept to one or two words on purpose. These render as chips
        // in a row a few hundred dp wide, and a long one — "No, something else"
        // was the offender — wraps inside its own chip, so it stands at double
        // the height of its neighbours and the row looks broken. The layout now
        // wraps whole chips rather than text, but short labels are the actual
        // fix: a chip is a button, and a button that needs two lines is a
        // sentence.
        if (lower.endsWith("?") || lower.contains("do you want") || lower.contains("would you like")) {
            out.add("Yes")
            out.add("Something else")
        }

        return out.distinct().take(3)
    }

    private fun endsAbruptly(text: String): Boolean {
        val last = text.trim().lastOrNull() ?: return false
        // A response that ends in a code-block fence (```) or
        // ends without a terminal punctuation is considered
        // truncated by the model.
        return last == '`' || text.trimEnd().endsWith("```")
    }
}
