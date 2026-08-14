package com.aura.memory

/**
 * Decides whether a piece of content is worth storing as a memory.
 *
 * ## Why this is conservative
 *
 * This used to return `shouldStore = true` for every message of four characters
 * or more that did not come from the system. It was called a gate and it let
 * everything through: a real install was found holding five memories, four of
 * which were "Hey you", "Hello", "Hey how are you" and "Heyara".
 *
 * That is not a cosmetic problem. Everything in the app is built on this store
 * — retrieval ranks against it, the knowledge graph extracts entities from it,
 * dream consolidation clusters it, beliefs are promoted out of it, and the
 * consult pass reads the categories it assigns. Noise here is not inert; it
 * competes for recall slots and propagates.
 *
 * The two failure directions are not symmetric, which is what settles the
 * design:
 *
 *  - **Missing a memory is recoverable.** The user can say "remember that", and
 *    that path — `RememberTool` → `MemoryStore.storeIfAbsent` — does not pass
 *    through this class at all. Nothing here can stop a deliberate instruction
 *    from being kept.
 *  - **Storing junk is not recoverable** in any comparable way. It has to be
 *    found and corrected one row at a time, and until it is, it degrades every
 *    read.
 *
 * So this rejects unless the text carries something durable, and the cost of
 * that choice is paid by the one path that can afford it.
 *
 * ## A rejection here is not the last word
 *
 * [LlmWriteGate] asks a model about anything this class does not *hard* reject,
 * and the model's answer wins. Only the reasons in [HARD_REJECT] short-circuit
 * that — they are the cases where a model call would be spent to be told what
 * is already obvious. A [Decision] with `shouldStore = false` and any other
 * reason means "not worth storing on this evidence alone", and is the fallback
 * used when no model is available to give a better answer.
 */
class WriteGate {
    data class Decision(
        val shouldStore: Boolean,
        val category: String = "fact",
        val importance: Float = 0.5f,
        val reason: String = "",
    )

    fun evaluate(content: String, source: String): Decision {
        val text = content.trim()
        if (text.isEmpty()) return Decision(false, reason = REASON_EMPTY)
        if (source == "system") return Decision(false, reason = REASON_SYSTEM)
        if (text.length < MIN_CHARS) return Decision(false, reason = REASON_TOO_SHORT)

        val lower = text.lowercase()
        if (isPleasantry(lower)) return Decision(false, reason = REASON_PLEASANTRY)

        // Past here it is not obviously noise, so a model is worth asking and
        // this decision is only the fallback for when one cannot be reached.
        val category = categorise(lower)
            ?: return Decision(false, reason = REASON_NOTHING_DURABLE)

        return Decision(
            shouldStore = true,
            category = category,
            importance = IMPORTANCE[category] ?: 0.5f,
            reason = "classified",
        )
    }

    /**
     * A whole message that is only social noise.
     *
     * Matched against the *entire* normalised message rather than with
     * `contains`, because "thanks" inside "thanks — my flight is on Thursday"
     * is not a pleasantry, it is politeness attached to a fact worth keeping.
     */
    private fun isPleasantry(lower: String): Boolean {
        val normalised = lower
            // Strip punctuation and emoji so "hey!" and "hey 😊" match "hey".
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it == '\'' }
            .replace(Regex("\\s+"), " ")
            .trim()
            // "hey aura" and "thanks aura" are the same message as "hey".
            .removeSuffix(" aura")
            .removeSuffix(" you")
            .removeSuffix(" there")
            .trim()
        if (normalised in PLEASANTRIES) return true
        // "good morning", "good night" and friends, with or without a greeting.
        return normalised.removePrefix("good ").trim() in TIMES_OF_DAY
    }

    /**
     * The kind of durable thing this is, or null when there is none.
     *
     * Null is the common case for chat and is the point: a question, a request,
     * or an observation with no first-person claim and no explicit instruction
     * to remember is not a memory. It is a turn.
     */
    private fun categorise(lower: String): String? = when {
        lower.hasAny(PREFERENCE) -> "preference"
        lower.hasAny(TASK) -> "task"
        lower.hasAny(PERSON) -> "person"
        lower.hasAny(EXPLICIT) -> "fact"
        lower.hasAny(EPISODE) -> "episode"
        lower.hasAny(IDEA) -> "idea"
        // A first-person claim about the speaker, with no more specific shape.
        lower.hasAny(SELF) -> "fact"
        else -> null
    }

    /**
     * Marker match, anchored to a word boundary at the front.
     *
     * A plain `contains` is wrong for markers this short: "im " appears inside
     * "trim ", "aim " and "claim ", so "the aim is to cut latency" would be
     * filed as a first-person claim about the user. Padding both sides and
     * requiring a leading space makes every pattern start a word.
     */
    private fun String.hasAny(patterns: List<String>): Boolean {
        val padded = " $this "
        return patterns.any { padded.contains(" $it") }
    }

    companion object {
        const val REASON_EMPTY = "empty"
        const val REASON_SYSTEM = "system_msg"
        const val REASON_TOO_SHORT = "too_short"
        const val REASON_PLEASANTRY = "pleasantry"
        const val REASON_NOTHING_DURABLE = "nothing_durable"

        /**
         * Reasons so certain that asking a model would be spending a call to be
         * told what is already known.
         *
         * [REASON_NOTHING_DURABLE] is deliberately absent: it is a judgement
         * about weak evidence, and it is exactly the case a model should be
         * allowed to overturn. "The ARC deadline moved to April" carries no
         * first-person marker and no keyword this class knows, and it is worth
         * remembering.
         */
        val HARD_REJECT = setOf(REASON_EMPTY, REASON_SYSTEM, REASON_TOO_SHORT, REASON_PLEASANTRY)

        /** Below this there is no room for a durable claim. */
        const val MIN_CHARS = 4

        private val IMPORTANCE = mapOf(
            "preference" to 0.8f,
            "person" to 0.7f,
            "task" to 0.6f,
            "fact" to 0.5f,
            "idea" to 0.5f,
            "episode" to 0.4f,
        )

        private val PLEASANTRIES = setOf(
            "hi", "hey", "hello", "yo", "hiya", "sup", "heya", "howdy",
            "thanks", "thank you", "ty", "cheers", "much appreciated", "appreciated",
            "ok", "okay", "k", "kk", "sure", "alright", "right",
            "yes", "yep", "yeah", "yup", "no", "nope", "nah",
            "cool", "nice", "great", "awesome", "perfect", "lovely", "good", "fine", "excellent",
            "lol", "haha", "hah", "hmm", "huh", "oh", "ah", "wow", "ha",
            "bye", "goodbye", "see you", "see ya", "later", "gn", "night",
            "please", "sorry", "np", "no problem", "youre welcome", "welcome",
            "test", "testing", "ping", "hey how are you", "how are you", "hows it going",
            "whats up", "what's up", "you there", "u there",
        )

        private val TIMES_OF_DAY = setOf("morning", "afternoon", "evening", "night")

        private val PREFERENCE = listOf(
            "i prefer", "i like", "i love", "i hate", "i dislike", "i enjoy",
            "i'd rather", "i would rather", "i always", "i never", "i usually",
            "my favourite", "my favorite", "i use ", "i don't like", "i dont like",
        )

        private val TASK = listOf(
            "remind me", "todo", "to-do", "i need to", "i have to", "i must ",
            "deadline", "due on", "due by", "schedule", "book me", "don't let me forget",
        )

        private val PERSON = listOf(
            "my wife", "my husband", "my partner", "my girlfriend", "my boyfriend",
            "my friend", "my colleague", "my boss", "my manager", "my team",
            "my mother", "my father", "my mum", "my mom", "my dad",
            "my brother", "my sister", "my son", "my daughter", "my family",
            // Bare family references, which arrive as instructions rather than
            // as claims — "call mom tomorrow at 5" names a person and a
            // commitment and would otherwise match nothing at all.
            "call mom", "call mum", "call dad", "call grandma", "call grandad",
        )

        /** The user telling it, in so many words, to keep something. */
        private val EXPLICIT = listOf(
            "remember", "don't forget", "dont forget", "keep in mind",
            "note that", "for future reference", "for the record", "my name is",
        )

        private val EPISODE = listOf(
            "today i", "yesterday i", "this morning", "last night", "this week i",
            "happened", "i went", "we went", "i met", "i finished",
        )

        private val IDEA = listOf(
            "what if we", "we could", "we should", "maybe we", "an idea",
            "i'm thinking of", "im thinking of", "i've been thinking",
        )

        /** A first-person claim about the speaker, of no more specific shape. */
        private val SELF = listOf(
            "i am ", "i'm ", "im ", "i live", "i work", "i study", "i have ", "i've ",
            "i speak", "i own", "i drive", "i'm building", "i am building",
            "call me ", "my ",
        )
    }
}
