package com.aura.creative.longform

import com.aura.creative.StoryBeat

/**
 * Turns an OUTLINE generation into machine-readable [StoryBeat]s.
 *
 * A long-form run needs a list it can iterate, not prose. The obvious way to get
 * one would be structured output, and it is not available here: no provider in
 * this codebase serialises `ChatOptions.responseFormat` — the field exists and
 * one caller sets it, but nothing puts it on the wire — and there is no
 * `tool_choice`, so even forcing a tool call is not possible. Wiring either
 * across nine providers is real work, and would still need a fallback parser for
 * models that answer in prose anyway. So: build the parser, skip the provider
 * work.
 *
 * The grammar is a single line per beat, pipe-separated, appended to the OUTLINE
 * mode instruction:
 *
 * ```
 * BEAT 1 | The lighthouse goes dark | Mara wakes to silence and no beam | POV: Mara | SETTING: The lighthouse | TARGET: 1200
 * ```
 *
 * Deliberately forgiving. Everything after the summary is optional, unlabelled
 * lines are ignored rather than treated as errors, and code fences are stripped —
 * models wrap things in them unprompted. What it will not do is guess: a line
 * with no title produces no beat.
 */
object OutlineParser {

    /** Appended to the OUTLINE prompt. Kept next to the parser so the two cannot drift. */
    val FORMAT_INSTRUCTION: String = """
        Output the outline as one line per beat, in exactly this format:

        BEAT <n> | <title> | <one-sentence summary> | POV: <character> | SETTING: <place> | TARGET: <word count>

        Everything from POV onward is optional. Write nothing else — no preamble,
        no numbering other than the BEAT prefix, no closing commentary.
    """.trimIndent()

    /** Terser retry, used once when the first attempt produced too few beats. */
    val RETRY_INSTRUCTION: String = """
        Output ONLY lines starting with "BEAT ". One per beat. No prose, no
        headings, no explanation. Format:

        BEAT 1 | Title | One-sentence summary
    """.trimIndent()

    /** Below this, the outline is not worth drafting from and we ask again. */
    const val MIN_BEATS = 3

    /**
     * The separator after the `BEAT n` prefix is **required**.
     *
     * It was optional, which let the regex backtrack: on the bare line `BEAT 1`,
     * `\d*` would match zero digits so that `(.+)` could capture `"1"`, and the
     * beat number became the title. Requiring a separator costs nothing — the
     * format specifies one, and `|`, `:`, `-` and `.` are all accepted — and
     * removes a whole class of half-parsed line.
     */
    private val BEAT_LINE = Regex("""^\s*BEAT\s*\d*\s*[|:.\-]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val LABELLED = Regex("""^\s*(POV|SETTING|TARGET)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

    /**
     * Parse [raw] into beats. Returns an empty list when nothing parsed, which
     * the caller must treat as "ask again", never as "an outline with no beats".
     */
    fun parse(raw: String): List<StoryBeat> =
        stripFences(raw)
            .lineSequence()
            .mapNotNull { line -> BEAT_LINE.find(line)?.groupValues?.get(1) }
            .mapNotNull { body -> beatFrom(body) }
            .toList()

    private fun beatFrom(body: String): StoryBeat? {
        // Split positionally and do NOT drop empties before taking the title.
        // Filtering first meant `BEAT 1 |  | a summary` promoted the summary
        // into the title slot — the parser guessing at a malformed line, which
        // is the one thing it should not do. Blanks are skipped below, where
        // they belong: among the optional trailing fields.
        val fields = body.split('|').map { it.trim() }
        val title = fields.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null

        var summary = ""
        var pov = ""
        var setting = ""
        var targetWords = 0

        for (field in fields.drop(1).filter { it.isNotEmpty() }) {
            val labelled = LABELLED.find(field)
            if (labelled == null) {
                // The first unlabelled field after the title is the summary;
                // later ones are almost always a model continuing the sentence,
                // so they join it rather than being dropped.
                summary = if (summary.isBlank()) field else "$summary $field"
                continue
            }
            val value = labelled.groupValues[2].trim()
            when (labelled.groupValues[1].uppercase()) {
                "POV" -> pov = value
                "SETTING" -> setting = value
                "TARGET" -> targetWords = value.filter { it.isDigit() }.toIntOrNull() ?: 0
            }
        }

        return StoryBeat(
            title = title.take(MAX_TITLE_CHARS),
            summary = summary.take(MAX_SUMMARY_CHARS),
            status = "planned",
            pov = pov.take(MAX_FIELD_CHARS),
            setting = setting.take(MAX_FIELD_CHARS),
            targetWords = targetWords.coerceIn(0, MAX_TARGET_WORDS),
        )
    }

    /**
     * Drop markdown code fences. Models wrap structured output in them without
     * being asked, and a leading ``` would otherwise hide the first beat.
     */
    private fun stripFences(raw: String): String =
        raw.lineSequence()
            .filterNot { it.trimStart().startsWith("```") }
            .joinToString("\n")

    private const val MAX_TITLE_CHARS = 120
    private const val MAX_SUMMARY_CHARS = 400
    private const val MAX_FIELD_CHARS = 80

    /**
     * A per-beat ceiling. A model that writes TARGET: 100000 would otherwise
     * turn one beat into a run that never finishes.
     */
    private const val MAX_TARGET_WORDS = 5_000
}
