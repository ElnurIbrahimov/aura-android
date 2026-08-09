package com.aura.agent

/**
 * Decides the `max_tokens` and thinking budget for one model call.
 *
 * This used to live inline in [Brain.stream], where it had no test coverage at
 * all, and it conflated two things that only look alike:
 *
 *  - **What the caller asked for.** `ChatOptions.maxTokens` set by a call site
 *    means "this is how much output I want" — 150 for a reflection, 28,672 for
 *    a creative draft.
 *  - **What the model will accept.** [ContextBudgetResolver] returns a ceiling
 *    derived from the context window.
 *
 * Treating both as the same number produced two opposite failures:
 *
 *  1. **Thinking ate the output.** With a caller-set budget the thinking budget
 *     was clamped to `maxTokens - 1`, so a 28,672-token creative draft went out
 *     as `max_tokens=28672, budget_tokens=28671` — the model was free to spend
 *     the entire response allowance on thinking, under a prompt that asks for
 *     12,000-16,000 words.
 *  2. **Callers who set a thinking budget were skipped entirely.** The old code
 *     only ran when `thinkingBudget == null`, so four Creative Studio callers
 *     that set both — `ProseCraftTools` (8192/8192), `TensionAnalyzer`
 *     (6000/16384), `VoiceCalibration.calibrate` (1500/8192) and
 *     `CharacterProgressionTracker` (2000/4096) — reached the wire with
 *     `budget_tokens >= max_tokens`, which Anthropic rejects with a
 *     non-retryable 400. Those four features were simply broken there.
 *
 * The rule here is that **thinking is additive to the requested output, never
 * subtracted from it** on the caller path, and subtractive on the resolver path
 * where the number is already a ceiling. On the caller path the worst case is
 * therefore exactly 2x what the call site asked for — a number that can be
 * reasoned about, unlike the old `budget + 24_576` inflation, which pushed a
 * 32K-context model to 56,576 and exceeded its own window.
 */
internal object TokenBudgetPolicy {

    /**
     * Below this, a thinking budget is dropped rather than sent.
     *
     * 1,024 is Anthropic's documented floor for `budget_tokens`. It also
     * replaces the old auxiliary-call gate, which used a bare `1000` — close
     * enough to look deliberate, but it left 1000..1023 in a dead zone where a
     * budget was injected and then clamped to something the API rejects.
     */
    const val MIN_THINKING_TOKENS = 1_024

    /**
     * How much of a resolver-derived ceiling is reserved for actual output
     * before thinking may claim the rest.
     *
     * This is the same 24,576 the old code added to the thinking budget. It was
     * written as an inflation ("make max_tokens at least budget + 24,576"),
     * which could push the request above the model's own context window. It is
     * a floor, and expressing it as one is the whole difference.
     */
    const val OUTPUT_FLOOR_TOKENS = 24_576

    /** The two numbers that go on the wire. Either may be null (omit the field). */
    data class Budget(val maxTokens: Int?, val thinkingBudget: Int?)

    /**
     * @param callerMaxTokens `ChatOptions.maxTokens` as the call site set it, or null.
     * @param callerThinkingBudget `ChatOptions.thinkingBudget` as the call site set it, or null.
     * @param resolverMaxTokens [ContextBudgetResolver]'s context-derived ceiling. Only
     *   consulted when [callerMaxTokens] is null.
     * @param reasoningEnabled the user's global extended-thinking toggle.
     * @param reasoningBudget the user's global thinking budget, used only when the
     *   call site did not state one of its own.
     * @param outputCeiling the model's maximum output tokens, when known. **Null means
     *   "do not clamp"** — an unknown model keeps whatever the rest of the rule produced.
     */
    fun resolve(
        callerMaxTokens: Int?,
        callerThinkingBudget: Int?,
        resolverMaxTokens: Int?,
        reasoningEnabled: Boolean,
        reasoningBudget: Int,
        outputCeiling: Int?,
    ): Budget {
        val callerOutput = callerMaxTokens?.takeIf { it > 0 }
        val resolverCeiling = resolverMaxTokens?.takeIf { it > 0 }
        val explicitThinking = callerThinkingBudget?.takeIf { it > 0 }
        // A caller passing 0 is saying "no thinking on this call", which is not
        // the same as saying nothing. Creative Studio's own thinking toggle
        // resolves to 0, so collapsing the two would turn that switch into a
        // placebo — it would fall through and get the global budget injected.
        val explicitlyDisabled = callerThinkingBudget != null && callerThinkingBudget <= 0

        // A caller-set thinking budget is honoured verbatim, not coerced against
        // the caller's own output number. Both were written by the same call
        // site for the same task: TensionAnalyzer asking for 16,384 thinking on
        // a 6,000-token answer is a deliberate "analyse hard, report briefly",
        // and bounding it by the output number would quietly undo that.
        //
        // The global preference gets no such trust below — it knows nothing
        // about the call site, so it is bounded by what the call site asked for.
        // `reasoningEnabled` is checked first, above the caller's own budget. A
        // user who turns extended thinking off is asking not to be billed for
        // it, and the four hardcoded creative budgets would otherwise ignore
        // that entirely. Creative Studio's own toggle already resolves to a
        // budget of 0 before it reaches here, so the two do not fight.
        val thinkingRequest: Int? = when {
            !reasoningEnabled -> null
            explicitlyDisabled -> null
            explicitThinking != null -> explicitThinking
            callerOutput != null -> reasoningBudget.coerceAtMost(callerOutput)
            else -> reasoningBudget
        }

        var total: Int?
        var thinking: Int?

        if (callerOutput != null) {
            // Additive: the caller stated an output size, so thinking is extra.
            thinking = thinkingRequest?.takeIf { it >= MIN_THINKING_TOKENS }
            total = callerOutput + (thinking ?: 0)
        } else if (resolverCeiling != null) {
            // Subtractive: the resolver's number is already a ceiling, so
            // thinking has to fit inside it and leave room to answer.
            thinking = fitInside(thinkingRequest, resolverCeiling)
            total = resolverCeiling
        } else {
            // Neither the caller nor the resolver produced a number — an
            // unresolvable model id, typically. A null max_tokens is fine on its
            // own (providers apply their own default; Anthropic substitutes
            // 4096), but it is *not* fine alongside a thinking budget: 32,000
            // thinking against an implicit 4,096 ceiling is exactly the
            // rejection this class exists to prevent. So when thinking survives
            // here, derive a total big enough to hold it plus an answer.
            //
            // This is the old `budget + 24_576` inflation, and in this one case
            // it was always right. Its bug was being applied on top of a
            // resolver ceiling it could exceed — which the branch above now
            // handles subtractively instead.
            thinking = thinkingRequest?.takeIf { it >= MIN_THINKING_TOKENS }
            total = thinking?.let { it + OUTPUT_FLOOR_TOKENS }
        }

        // The model's own output ceiling wins over everything above. Clamping
        // down can invalidate the thinking budget we just chose, so re-derive it
        // subtractively against the new, smaller total.
        if (outputCeiling != null && outputCeiling > 0 && total != null && total > outputCeiling) {
            total = outputCeiling
            thinking = fitInside(thinking, outputCeiling)
        }

        return Budget(maxTokens = total, thinkingBudget = thinking)
    }

    /**
     * Largest thinking budget that fits inside [total] while leaving enough
     * behind to actually answer. Returns null when what is left is below
     * [MIN_THINKING_TOKENS], because a budget under the floor is worse than
     * none: providers reject it, and it buys nothing.
     */
    private fun fitInside(request: Int?, total: Int): Int? {
        if (request == null) return null
        val floor = minOf(OUTPUT_FLOOR_TOKENS, total / 2)
        val room = total - floor
        return request.coerceAtMost(room).takeIf { it >= MIN_THINKING_TOKENS }
    }
}
