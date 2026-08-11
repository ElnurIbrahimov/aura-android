package com.aura.memory

import android.util.Log
import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
        } catch (e: TimeoutCancellationException) {
            // Expected under a slow model, and falling back to the raw query is
            // correct — but it must be visible. A rewriter that has timed out on
            // every deictic query for a month is indistinguishable from one that
            // works, because the output is always a plausible query.
            Log.w(TAG, "query rewrite timed out after ${REWRITE_TIMEOUT_MS}ms; using the original query", e)
            query
        } catch (e: CancellationException) {
            // The CALLER gave up: a cancelled recall, a closed conversation.
            // Swallowing it breaks cooperative cancellation and leaves the model
            // call running for a result nobody is waiting for. The previous
            // `catch (e: Exception)` caught this, because CancellationException
            // is an Exception — the same defect just fixed in MemoryReranker,
            // one file away, on this same recall path.
            throw e
        } catch (e: Exception) {
            // Anything else is a real failure: no model configured, a provider
            // error, a malformed response. Silent, this made a permanently
            // broken rewriter look exactly like a working one.
            Log.w(TAG, "query rewrite failed (${e.javaClass.simpleName}); using the original query: ${e.message}", e)
            query
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

        // Strong deictic phrases — always trigger rewrite
        val deicticPhrases = listOf(
            "that thing", "that stuff", "that idea", "that topic",
            "what we discussed", "what i mentioned", "what she said",
            "what he said", "what they said", "what you said",
            "remind me", "what was that", "what was the",
            "tell me more about it", "tell me more about that",
            "continue from", "pick up where",
        )
        if (deicticPhrases.any { lower.contains(it) }) return true

        // Weak deictic starters (single words like "it", "this", "that")
        // require a second signal — a past-tense verb or temporal marker —
        // to avoid false positives like "it is a good idea to use Kotlin"
        // where "it" is a dummy pronoun, not a referential one.
        val weakStarters = listOf("it ", "this ", "that ", "the same ")
        if (weakStarters.any { lower.startsWith(it) }) {
            // Second signal: past tense verb or temporal reference
            val pastTenseMarkers = listOf(
                " was ", " were ", " had ", " said ", " told ", " mentioned",
                " discussed", " talked about", " showed", " gave",
                " earlier", " before", " yesterday", " today",
                " again", " still", " also",
            )
            if (pastTenseMarkers.any { lower.contains(it) }) return true
        }

        // Standalone temporal deictics — only trigger with a reference
        val temporalDeictics = listOf("earlier", "before about", "again about")
        if (temporalDeictics.any { lower.contains(it) }) return true

        return false
    }

    companion object {
        private const val TAG = "QueryRewriter"
        const val REWRITE_TIMEOUT_MS = 5_000L
    }
}