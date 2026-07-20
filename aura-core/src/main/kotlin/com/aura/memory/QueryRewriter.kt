package com.aura.memory

import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites deictic queries into self-contained retrieval queries before
 * embedding and BM25 scoring.
 *
 * "What about that thing we discussed" -> "database migration strategy
 * we discussed on Tuesday"
 *
 * "Remind me what she said" -> "what Helene said about the AI lab"
 *
 * Uses a cheap LLM call with the recent conversation context (last 3
 * turns) to resolve references. Falls back to the original query on
 * any error — the rewrite is best-effort.
 *
 * Only rewrites queries that need it. "What is Kotlin coroutines" is
 * already self-contained — the rewriter returns it unchanged.
 */
@Singleton
class QueryRewriter @Inject constructor(
    private val brain: Brain,
) {
    /**
     * Rewrite [query] using [recentContext] (last few conversation turns)
     * to resolve deictic references. Returns the rewritten query, or
     * the original if rewriting fails or is unnecessary.
     *
     * @param query The raw user message
     * @param recentContext Last 3-5 conversation turns for reference resolution
     * @param model Small/fast model for the rewrite call
     */
    suspend fun rewrite(
        query: String,
        recentContext: String,
        model: String,
    ): String {
        if (query.isBlank()) return query

        // Heuristic: skip rewrite for queries that are already self-contained.
        // Short queries with specific nouns don't need rewriting. Only
        // rewrite when deictic markers are present.
        if (!needsRewrite(query)) return query

        return try {
            withTimeout(REWRITE_TIMEOUT_MS) {
                val systemPrompt = buildString {
                    append("You are a search query rewriter. Rewrite the user's message into a self-contained ")
                    append("search query that resolves any references to prior conversation. ")
                    append("Rules:\n")
                    append("- Resolve pronouns: 'that thing' -> 'the database migration strategy'\n")
                    append("- Resolve deictics: 'what we discussed' -> 'the quantum computing paper from Tuesday'\n")
                    append("- Keep it concise — this is a search query, not an essay\n")
                    append("- If the message is already self-contained, return it unchanged\n")
                    append("- Return ONLY the rewritten query, no explanation\n")
                }

                val userPrompt = buildString {
                    append("Recent conversation:\n")
                    append(recentContext.take(2000))
                    append("\n\nUser message: $query\n")
                    append("Rewritten search query:")
                }

                val messages = listOf(
                    ProviderMessage(role = ProviderMessage.Role.system, content = systemPrompt),
                    ProviderMessage(role = ProviderMessage.Role.user, content = userPrompt),
                )

                val options = ChatOptions(temperature = 0.0, maxTokens = 100)
                val response = StringBuilder()

                brain.stream(model, messages, options = options).collect { chunk ->
                    if (chunk is BrainChunk.Text) {
                        response.append(chunk.text)
                    }
                }

                val rewritten = response.toString().trim()
                // If the model returned the same thing or empty, use original
                if (rewritten.isBlank() || rewritten.equals(query, ignoreCase = true)) {
                    query
                } else {
                    rewritten
                }
            }
        } catch (e: Exception) {
            query // best-effort — fall back to original
        }
    }

    /**
     * Heuristic: does this query contain deictic references that need
     * rewriting? Checks for common markers:
     * - "that thing" / "that stuff" / "that idea"
     * - "what we discussed" / "what I mentioned" / "what she said"
     * - "it" / "this" / "that" as sentence starters
     * - "remind me" / "what was" / "earlier" / "before"
     */
    private fun needsRewrite(query: String): Boolean {
        val lower = query.lowercase().trim()
        if (lower.length < 3) return false

        // Deictic phrases — strong signals
        val deicticPhrases = listOf(
            "that thing", "that stuff", "that idea", "that topic",
            "what we discussed", "what i mentioned", "what she said",
            "what he said", "what they said", "what you said",
            "remind me", "what was that", "what was the",
            "earlier", "before about", "again about",
            "tell me more about it", "tell me more about that",
            "continue from", "pick up where",
        )
        if (deicticPhrases.any { lower.contains(it) }) return true

        // Single deictic pronouns at sentence start
        val deicticStarters = listOf("it ", "this ", "that ", "the same ")
        if (deicticStarters.any { lower.startsWith(it) }) return true

        return false
    }

    companion object {
        const val REWRITE_TIMEOUT_MS = 5_000L
    }
}