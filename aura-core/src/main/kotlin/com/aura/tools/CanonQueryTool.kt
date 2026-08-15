package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolCall
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.core.util.StopWords
import com.aura.creative.CanonFactDao
import com.aura.creative.CreativeBranchStore
import com.aura.creative.CreativeProjectStore
import com.aura.memory.MemoryStore
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answer a question from a creative project's recorded canon.
 *
 * This used to run `memoryStore.query("$question project:$projectId")` against
 * the user's *personal* memory store — the one holding facts about their life.
 * `project:` is not a scope filter; it is literal text inside a BM25 query, so
 * it contributed noise rather than scoping. Meanwhile the canon tables the tool
 * is named after had no writer at all, so there was nothing to read either way.
 * `SceneLedger` is now that writer.
 */
@Singleton
class CanonQueryTool @Inject constructor(
    private val memoryStore: MemoryStore,
    private val projectStore: CreativeProjectStore,
    private val branchStore: CreativeBranchStore,
    private val canonFactDao: CanonFactDao,
) {
    fun definition() = ToolDefinition(
        name = "canon_query",
        description = "Look up what is established in a creative project's canon — where a " +
            "character is, who they serve, what a rule of the world says. Returns recorded facts, " +
            "not guesses.",
        parameters = ToolParameters(
            properties = mapOf(
                "projectId" to ToolProperty("string", "Creative project ID"),
                "question" to ToolProperty("string", "Question about canon, plot, characters, or world rules"),
            ),
            required = listOf("projectId", "question"),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        category = "creative",
        execute = { call, _ ->
            val projectId = call.arguments["projectId"] as? String
                ?: return@Tool ToolResult.Error("missing 'projectId'", "bad_args")
            val question = call.arguments["question"] as? String
                ?: return@Tool ToolResult.Error("missing 'question'", "bad_args")
            projectStore.get(projectId)
                ?: return@Tool ToolResult.Error("Project not found", "not_found")

            // A pure read, deliberately not createMainBranch — that is a
            // get-or-create, and this tool is declared READ_ONLY. ToolExecutor
            // lets READ_ONLY through the incognito gate on the strength of that
            // declaration, so a hidden insert here would write local state in
            // exactly the session that promised not to.
            //
            // No main branch means no facts can exist for one, so a missing
            // branch and an empty canon are the same answer.
            val branchId = branchStore.forProject(projectId).firstOrNull { it.name == "main" }?.id
            val facts = if (branchId == null) emptyList() else canonFactDao.activeForBranch(projectId, branchId)
            // Terms rather than a ranked search: canon is tens of rows, not
            // thousands, and a subject-name match is what the question is
            // almost always about. Ranking this would be machinery over noise.
            val terms = question.lowercase()
                .split(Regex("[^a-z0-9']+"))
                .filter { it.length >= 3 && it !in StopWords.ENGLISH }
            val termMatches = facts.filter { fact ->
                terms.isEmpty() || terms.any {
                    fact.subjectId.lowercase().contains(it) ||
                        fact.predicate.lowercase().contains(it) ||
                        fact.valueJson.lowercase().contains(it)
                }
            }
            // Distinct from "terms.isEmpty()": this is specifically "we had
            // terms, and none of them matched a fact" — the caller cannot tell
            // that apart from a real match list without a marker, since both
            // render as the same fact dump under the same header.
            val noTermMatched = facts.isNotEmpty() && termMatches.isEmpty()
            val matched = termMatches.ifEmpty { facts }

            val output = buildString {
                appendLine("Canon for: $question")
                if (matched.isEmpty()) {
                    appendLine("No canon recorded for this project yet. Canon is written as scenes are drafted.")
                } else {
                    if (noTermMatched) {
                        appendLine("(No term matched; showing all recorded canon.)")
                    }
                    matched.take(MAX_FACTS).forEach {
                        appendLine("- ${it.subjectId} (${it.subjectType}) ${it.predicate}: ${it.valueJson.trim('"')}")
                    }
                }
            }
            ToolResult.Ok(output.trim())
        },
    )

    private companion object {
        /** A tool result is truncated at 4,000 chars upstream; this stays well under. */
        const val MAX_FACTS = 40
    }
}
