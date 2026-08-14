package com.aura.ui.components

/**
 * Splits a thinking stream into the steps the model already wrote.
 *
 * ## Why the labels are quotes, not summaries
 *
 * Other clients render segmented thinking by asking a model to *summarise*
 * each stretch of reasoning into a title. That reads well and it is not what
 * happens here, for two reasons.
 *
 * The first is cost and latency: a summary pass is an extra call on every turn
 * that produced thinking, paid to describe text the user can already see.
 *
 * The second matters more. A generated title is a second author's account of
 * the reasoning, and it can be wrong about it — flattering, tidier than the
 * real thing, or simply describing a step the model did not take. Anthropic's
 * own faithfulness work found models mention a hint they actually used only
 * 25–39% of the time, so a *description* of reasoning is exactly the artifact
 * least worth trusting. Quoting the model's own opening clause cannot drift
 * from what it said, because it **is** what it said.
 *
 * So the segmentation is structural: reasoning models already write in
 * paragraphs, and those paragraph breaks are where the model itself changed
 * subject. This finds them and does nothing cleverer.
 */
object ThinkingSteps {

    /**
     * @param label the step's own opening clause, verbatim and trimmed.
     * @param body the rest of the step, or blank when the label was the whole
     *   of it — so the UI never prints the same sentence twice.
     */
    data class Step(val label: String, val body: String)

    fun segment(thinking: String): List<Step> {
        if (thinking.isBlank()) return emptyList()

        // Paragraph breaks are the model's own structure. Splitting on single
        // newlines would cut mid-thought on any wrapped line.
        val paragraphs = thinking.trim()
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (paragraphs.isEmpty()) return emptyList()

        // A stray line — "Wait.", "Hmm." — is a beat inside a thought, not a
        // step of its own. Attaching it to the paragraph it belongs with keeps
        // the timeline from filling up with fragments.
        val merged = mutableListOf<String>()
        for (p in paragraphs) {
            if (p.length < MIN_STEP_CHARS && merged.isNotEmpty()) {
                merged[merged.lastIndex] = merged.last() + "\n\n" + p
            } else {
                merged += p
            }
        }

        // Long deliberations run to dozens of paragraphs, and a forty-step
        // timeline is as unreadable as the single blob it replaced. Fold from
        // the front, so the later steps — where the model reaches its
        // conclusion — stay separate.
        while (merged.size > MAX_STEPS) {
            merged[1] = merged[0] + "\n\n" + merged[1]
            merged.removeAt(0)
        }

        return merged.map { block ->
            val label = labelFor(block)
            // Only carry a body when it says something the label did not.
            val body = block.removePrefix(label).trim().removePrefix("—").trim()
            Step(label = label, body = body)
        }
    }

    /**
     * The step's opening clause.
     *
     * Sentence-terminal punctuation first, because a model's first sentence is
     * almost always its statement of what it is about to do. Falls back to a
     * word boundary so a label never ends mid-word.
     */
    private fun labelFor(block: String): String {
        val flat = block.replace(Regex("\\s+"), " ").trim()
            // Leading list and heading markers are formatting, not content.
            .removePrefix("#").removePrefix("#").removePrefix("#")
            .removePrefix("-").removePrefix("*").trim()

        val sentenceEnd = flat.indexOfFirst { it == '.' || it == '?' || it == '!' }
        if (sentenceEnd in 1 until MAX_LABEL_CHARS) {
            return flat.substring(0, sentenceEnd + 1).trim()
        }
        if (flat.length <= MAX_LABEL_CHARS) return flat

        val cut = flat.lastIndexOf(' ', MAX_LABEL_CHARS)
        return flat.substring(0, if (cut > MIN_STEP_CHARS) cut else MAX_LABEL_CHARS).trim() + "…"
    }

    /** Below this a paragraph is a beat, not a step. */
    internal const val MIN_STEP_CHARS = 24

    /** Beyond this the timeline is as unreadable as the blob it replaced. */
    internal const val MAX_STEPS = 12

    /** One line on a phone. */
    internal const val MAX_LABEL_CHARS = 80
}
