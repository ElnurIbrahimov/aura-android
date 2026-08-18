package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolCall
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.documents.DocumentChunkRetrieval
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search imported documents and get back passages that can be quoted.
 *
 * `index_document` has existed without a counterpart: documents went in, and
 * the only way back out was general memory recall, which returns a passage as
 * an undifferentiated memory with the document name glued onto the front of its
 * text. There was no way to ask "what do my documents say about X" as distinct
 * from "what do I remember about X", and no way to cite an answer.
 *
 * This reads `document_chunks`, which is where the chunks now live, with corpus
 * statistics taken over documents alone — so a term that is common in a book
 * stops discriminating between that book's passages, without also flattening
 * IDF for the user's own memories.
 */
@Singleton
class SearchDocumentsTool @Inject constructor(
    private val retrieval: DocumentChunkRetrieval,
) {
    fun definition() = ToolDefinition(
        name = "search_documents",
        description = "Search imported documents and return the best-matching passages with citations. " +
            "Use for questions about a document the user has imported, rather than about what Aura remembers.",
        parameters = ToolParameters(
            properties = mapOf(
                "query" to ToolProperty("string", "What to look for in the documents"),
                "limit" to ToolProperty("integer", "How many passages to return (default 5, max 20)"),
            ),
            required = listOf("query"),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        category = "memory",
        execute = { call, _ ->
            val query = (call.arguments["query"] as? String)?.trim()
            if (query.isNullOrBlank()) return@Tool ToolResult.Error("missing 'query'", "bad_args")
            val limit = ((call.arguments["limit"] as? Number)?.toInt() ?: DEFAULT_LIMIT)
                .coerceIn(1, MAX_LIMIT)

            val passages = retrieval.search(query, limit)
            if (passages.isEmpty()) {
                // Said plainly rather than returning the least-bad passage. A
                // ranker with no floor always has a top result, and "here is
                // the closest thing in your documents" reads as an answer.
                return@Tool ToolResult.Ok("No imported document matches \"$query\".")
            }

            ToolResult.Ok(
                passages.joinToString("\n\n") { passage ->
                    "[${passage.citation}] (chars ${passage.charStart}-${passage.charEnd})\n${passage.text}"
                }
            )
        },
    )

    private companion object {
        const val DEFAULT_LIMIT = 5

        /**
         * A passage is up to 1,800 characters, so twenty of them is already
         * more context than most models should be handed from one tool call.
         */
        const val MAX_LIMIT = 20
    }
}
