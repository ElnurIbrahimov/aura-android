package com.aura.providers

/**
 * Picks a small/cheap model for the agentic loop's *auxiliary* calls —
 * planning, query rewriting, reranking, compaction, and the write gate.
 * None of those are generation tasks; they're short classification or
 * summarization steps where a small model is both faster and cheaper.
 *
 * ## Why not name length
 *
 * The previous heuristic picked the model with the shortest *name*, on the
 * theory that shorter names tend to be smaller models. It reliably picked
 * the wrong one: `"gpt-4o"` (6 chars) beats `"gpt-4o-mini"` (11), and
 * `"claude-opus-4"` beats `"claude-haiku-4-5-20251001"`. The size suffix
 * that marks a model as small *lengthens* the name, so ranking by length
 * systematically prefers the flagship over the mini variant — the exact
 * opposite of what the callers want.
 *
 * ## What this does instead
 *
 * Scores each model name against two marker sets:
 *
 * - **Small markers** (`mini`, `nano`, `flash`, `haiku`, `8b`, ...) pull the
 *   score down. A model carrying one of these is almost always the cheap
 *   tier of its family.
 * - **Large markers** (`opus`, `ultra`, `70b`, `405b`, ...) push the score
 *   up. These are the flagship tiers we specifically want to avoid burning
 *   on a 150-token plan.
 *
 * Lower score wins. Name length is retained only as a final tie-breaker
 * between models that carry no markers at all, where it's no worse than
 * arbitrary.
 *
 * ## Degradation
 *
 * Like [ProviderContextWindows], this is a SNAPSHOT and is expected to go
 * stale. An unrecognized model simply scores neutral (0) and competes on
 * the tie-breaker — the caller still gets *a* model, just not necessarily
 * the cheapest one. That's a cost/latency regression, never a correctness
 * bug, which is the right failure mode for an auxiliary-call optimization.
 *
 * Markers are matched against the lowercased model name with the provider
 * prefix stripped, as substrings. Parameter-count markers (`8b`, `70b`) are
 * matched with digit-boundary awareness so `"8b"` does not match inside
 * `"128b"`.
 */
internal object CheapModelHeuristic {

    /**
     * Markers indicating the small/cheap tier of a model family, keyed by
     * how strong a "this is cheap" signal each one is.
     *
     * Two tiers because families increasingly ship more than one small
     * variant: `gpt-5-nano` is cheaper than `gpt-5-mini`, and flattening
     * both to a single score leaves the choice between them to the length
     * tie-breaker, which is a coin flip when the names are the same
     * length. The stronger tier wins when a name carries markers from
     * both.
     */
    private val TINY_MARKERS = listOf("nano", "micro", "tiny")

    private val SMALL_MARKERS = listOf(
        "mini", "small", "lite", "light",
        "flash", "instant", "haiku", "turbo", "scout", "embed",
    )

    /** Markers indicating the flagship/expensive tier. */
    private val LARGE_MARKERS = listOf(
        "opus", "ultra", "max", "large", "xl", "heavy", "thinking",
        "reasoner", "deep-research", "pro",
    )

    /**
     * Parameter-count markers, e.g. `7b`, `70b`, `405b`. Matched with a
     * digit boundary so `8b` doesn't fire inside `128b`. Anything at or
     * below [SMALL_PARAM_CEILING] billion counts as small; anything at or
     * above [LARGE_PARAM_FLOOR] counts as large.
     */
    private val PARAM_PATTERN = Regex("""(?<!\d)(\d{1,3})b(?![a-z0-9])""")
    private const val SMALL_PARAM_CEILING = 9
    private const val LARGE_PARAM_FLOOR = 30

    /**
     * Score a bare model name (no provider prefix). Lower is cheaper.
     * Returns 0 for models carrying no recognizable marker.
     */
    fun score(modelName: String): Int {
        val name = modelName.lowercase()
        var score = 0
        when {
            TINY_MARKERS.any { it in name } -> score -= 3
            SMALL_MARKERS.any { it in name } -> score -= 2
        }
        if (LARGE_MARKERS.any { it in name }) score += 2
        PARAM_PATTERN.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { params ->
            when {
                params <= SMALL_PARAM_CEILING -> score -= 1
                params >= LARGE_PARAM_FLOOR -> score += 1
                else -> Unit
            }
        }
        return score
    }

    /**
     * Pick the cheapest-looking model from [candidates].
     *
     * Candidates may be either bare names (`"gpt-4o-mini"`) or fully
     * qualified ids (`"openai:gpt-4o-mini"`) — the prefix is stripped
     * before scoring, and whatever form was passed in is returned
     * unchanged.
     *
     * Returns null when [candidates] is empty.
     */
    fun pick(candidates: List<String>): String? = candidates.minWithOrNull(
        compareBy(
            { score(it.substringAfter(':')) },
            // Tie-break on name length. Only reached when both models
            // carry the same markers (usually none), where length is as
            // good a guess as any.
            { it.substringAfter(':').length },
        ),
    )
}
