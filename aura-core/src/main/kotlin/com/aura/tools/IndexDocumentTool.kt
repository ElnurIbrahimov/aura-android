package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolCall
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.documents.DocumentRepository
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Index a document from raw text so it becomes retrievable in chat via canon_query / recall.
 */
@Singleton
class IndexDocumentTool @Inject constructor(
    private val documentRepository: DocumentRepository,
) {
    fun definition() = ToolDefinition(
        name = "index_document",
        description = "Index a plain-text document by id/name/text so it can be queried later.",
        parameters = ToolParameters(
            properties = mapOf(
                "id" to ToolProperty("string", "Stable document ID"),
                "name" to ToolProperty("string", "Display name"),
                "text" to ToolProperty("string", "Plain text content to chunk and index"),
            ),
            required = listOf("id", "name", "text"),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.WRITE_LOCAL,
        parameters = definition().parameters,
        category = "memory",
        execute = { call, _ ->
            val id = call.arguments["id"] as? String ?: return@Tool ToolResult.Error("missing 'id'", "bad_args")
            val name = call.arguments["name"] as? String ?: return@Tool ToolResult.Error("missing 'name'", "bad_args")
            val text = call.arguments["text"] as? String ?: return@Tool ToolResult.Error("missing 'text'", "bad_args")
            val result = documentRepository.import(
                id = id,
                name = name,
                mimeType = "text/plain",
                sourceUri = "tool://index_document/$id",
                text = text,
            )
            ToolResult.Ok("Indexed '${result.document.name}' into ${result.chunkCount} chunks.")
        },
    )
}
