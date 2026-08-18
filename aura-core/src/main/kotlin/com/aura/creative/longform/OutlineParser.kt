package com.aura.creative.longform

import com.aura.creative.StoryBeat

/**
 * Turns an OUTLINE generation into machine-readable [StoryBeat]s.
 *
 * A long-form run needs a list it can iterate, not prose. The obvious way to get
 * one would be structured output, and when this was written it was not
 * available: no provider serialised `ChatOptions.responseFormat`, and there was
 * no `tool_choice`, so even forcing a tool call was impossible. Both are now
 * wired — see `ChatOptions.responseSchema` — so that reasoning no longer holds.
 *
 * This parser stays anyway, and not only for inertia. Structured output is not
 * universal: `custom` points at a user-supplied endpoint and `moa` fans out to
 * whatever the aggregator is, so a fallback parser is still needed for exactly
 * the models most likely to answer in prose. The pipe grammar also streams —
 * beats can be read as they arrive, one line at a time — where a JSON array
 * cannot be parsed until its closing bracket. For a generation measured in
 * minutes that is a real difference, not a stylistic one.
 *
 * Worth revisiting if long-form ever stops streaming its outline.
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

        BEAT <n> | <title> | <one-sentence summary> | POV: <character> | SETTING: <place> | TARGET: <word count> | REQUIRES: <name> @ <fact> = <value> | EFFECT: <name> @ <fact> = <value>

        Everything from POV onward is optional. REQUIRES names what must already
        be true entering the scene and EFFECT what the scene makes true; both
        may hold several clauses separated by ";". Write nothing else — no
        preamble, no numbering other than the BEAT prefix, no closing
        commentary.
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
    private val LABELLED =
        Regex("""^\s*(POV|SETTING|TARGET|REQUIRES|EFFECT)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

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
        val preconditions = mutableListOf<com.aura.creative.BeatAssertion>()
        val effects = mutableListOf<com.aura.creative.BeatAssertion>()

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
                "REQUIRES" -> preconditions += parseAssertions(value)
                "EFFECT" -> effects += parseAssertions(value)
            }
        }

        return StoryBeat(
            title = title.take(MAX_TITLE_CHARS),
            summary = summary.take(MAX_SUMMARY_CHARS),
            status = "planned",
            pov = pov.take(MAX_FIELD_CHARS),
            setting = setting.take(MAX_FIELD_CHARS),
            targetWords = targetWords.coerceIn(0, MAX_TARGET_WORDS),
            preconditions = preconditions.take(MAX_ASSERTIONS),
            effects = effects.take(MAX_ASSERTIONS),
        )
    }

    /**
     * `name @ fact = value` clauses, ";"-separated, with an optional
     * `type:` prefix on the name (one of [SUBJECT_TYPE_PREFIXES]). A clause
     * missing its "@" or "=" is skipped, never guessed — the same law the
     * beat line itself follows.
     */
    private fun parseAssertions(value: String): List<com.aura.creative.BeatAssertion> =
        value.split(';').mapNotNull { clause ->
            val at = clause.indexOf('@')
            if (at < 0) return@mapNotNull null
            val eq = clause.indexOf('=', startIndex = at + 1)
            if (eq < 0) return@mapNotNull null
            var subject = clause.substring(0, at).trim()
            val predicate = clause.substring(at + 1, eq).trim().lowercase()
            val assertionValue = clause.substring(eq + 1).trim()
            if (predicate.isBlank() || assertionValue.isBlank()) return@mapNotNull null
            var subjectType = "character"
            val colon = subject.indexOf(':')
            if (colon > 0) {
                val maybeType = subject.substring(0, colon).trim().lowercase()
                if (maybeType in SUBJECT_TYPE_PREFIXES) {
                    subjectType = maybeType
                    subject = subject.substring(colon + 1).trim()
                }
            }
            if (subject.isBlank()) return@mapNotNull null
            com.aura.creative.BeatAssertion(
                subjectType = subjectType,
                subjectId = subject.take(MAX_FIELD_CHARS),
                predicate = predicate.take(MAX_FIELD_CHARS),
                value = assertionValue.take(MAX_SUMMARY_CHARS),
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

    /** Mirrors `SceneLedger.SUBJECT_TYPES`; kept literal so the parser stays pure. */
    private val SUBJECT_TYPE_PREFIXES =
        setOf("character", "location", "faction", "object", "rule", "timeline", "relationship")

    /** Per label, per beat. A model that emits fifty clauses is not outlining. */
    private const val MAX_ASSERTIONS = 8

    private const val MAX_TITLE_CHARS = 120
    private const val MAX_SUMMARY_CHARS = 400
    private const val MAX_FIELD_CHARS = 80

    /**
     * A per-beat ceiling. A model that writes TARGET: 100000 would otherwise
     * turn one beat into a run that never finishes.
     */
    private const val MAX_TARGET_WORDS = 5_000
}
