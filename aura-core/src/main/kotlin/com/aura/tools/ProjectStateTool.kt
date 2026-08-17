package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.curiosity.OpenQuestionDao
import com.aura.curiosity.OpenQuestionEntity
import com.aura.projects.ProjectEntity
import com.aura.projects.ProjectNoteEntity
import com.aura.projects.ProjectStore
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a project stands — from rows, with no model call.
 *
 * This is the whole point of the ledger. The alternative design derived the
 * answer on demand by handing a pile of memories to a model and asking it what
 * had been decided; that costs a call per question, produces a different answer
 * each time, and can point at nothing when it is wrong. Reading `project_notes`
 * costs a query, is identical on every ask, and every line carries the date it
 * was recorded and the conversation it came from.
 *
 * `READ_ONLY` because it is: it writes nothing, touches no remote endpoint and
 * spends nothing. That keeps it available in incognito, which is correct —
 * asking where your own work stands is not a thing that should require leaving
 * a private session.
 */
@Singleton
class ProjectStateTool @Inject constructor(
    private val projectStore: ProjectStore,
    private val openQuestionDao: OpenQuestionDao? = null,
) {
    fun definition() = ToolDefinition(
        name = "project_state",
        description = "Where one of the user's projects stands: what has been decided, what is " +
            "blocking it, its current status, and what is still open. Use this whenever the user " +
            "asks about the state of a project — 'where is X', 'what did we decide about X', " +
            "'what's blocking X'. Omit the name to list every active project.",
        parameters = ToolParameters(
            properties = mapOf(
                "project" to ToolProperty(
                    "string",
                    "Project name, e.g. 'ARC-AGI-2'. Case-insensitive. Omit to list all active projects.",
                ),
            ),
            required = emptyList(),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        category = ToolCategories.MEMORY,
        execute = { call, _ ->
            val name = (call.arguments["project"] as? String)?.trim().orEmpty()
            if (name.isEmpty()) listAll() else describe(name)
        },
    )

    private suspend fun listAll(): ToolResult {
        val projects = projectStore.active()
        if (projects.isEmpty()) {
            // Named as an absence rather than an error: a user with no projects
            // has not done anything wrong, and the model should say so plainly
            // instead of reporting a failure.
            return ToolResult.Ok(
                "No projects are being tracked yet. A project starts when the user attributes a " +
                    "conversation to one from the project picker in the chat header.",
            )
        }
        return ToolResult.Ok(
            buildString {
                appendLine("Active projects (${projects.size}):")
                projects.forEach { p ->
                    append("- ${p.name}")
                    if (p.description.isNotBlank()) append(" — ${p.description}")
                    appendLine(" (${p.turnCount} conversations, last worked ${date(p.lastTurnAt)})")
                }
            }.trim(),
        )
    }

    private suspend fun describe(name: String): ToolResult {
        val project = projectStore.byName(name)
            ?: return ToolResult.Ok(
                "No project called '$name'. Active projects: " +
                    projectStore.active().joinToString(", ") { it.name }.ifBlank { "none yet" },
            )

        val notes = projectStore.activeNotes(project.id)
        val questions = openQuestionDao
            ?.let { dao ->
                runCatching { dao.byStatus(OpenQuestionEntity.STATUS_OPEN, limit = 20) }.getOrDefault(emptyList())
            }
            ?.filter {
                it.subjectKind == OpenQuestionEntity.SUBJECT_PROJECT && it.subjectId == project.id
            }
            .orEmpty()

        return ToolResult.Ok(
            buildString {
                append("# ${project.name}")
                if (project.status != ProjectEntity.STATUS_ACTIVE) append(" (${project.status})")
                appendLine()
                if (project.description.isNotBlank()) appendLine(project.description)
                appendLine("${project.turnCount} conversations, last worked ${date(project.lastTurnAt)}.")

                section(this, "Decided", notes, ProjectNoteEntity.KIND_DECISION)
                section(this, "Blocked on", notes, ProjectNoteEntity.KIND_BLOCKER)
                section(this, "Status", notes, ProjectNoteEntity.KIND_STATUS)

                if (questions.isNotEmpty()) {
                    appendLine()
                    appendLine("## Open")
                    questions.forEach { appendLine("- ${it.question}") }
                }

                if (notes.isEmpty() && questions.isEmpty()) {
                    appendLine()
                    // The honest empty state. A project with no ledger rows is
                    // new or has settled nothing yet, and saying so is a better
                    // answer than a confident summary assembled from nothing.
                    appendLine(
                        "Nothing has been recorded about this project yet. It fills in as " +
                            "decisions get made in conversations attributed to it.",
                    )
                }
            }.trim(),
        )
    }

    private fun section(
        sb: StringBuilder,
        heading: String,
        notes: List<ProjectNoteEntity>,
        kind: String,
    ) {
        val rows = notes.filter { it.kind == kind }
        if (rows.isEmpty()) return
        sb.appendLine()
        sb.appendLine("## $heading")
        // Date on every line: "we decided X" and "we decided X in June" are
        // different claims, and the second is the one the ledger can support.
        rows.forEach { sb.appendLine("- ${it.body} (${date(it.createdAt)})") }
    }

    /**
     * Built per call, not held in a field.
     *
     * `SimpleDateFormat` carries a mutable `Calendar` across calls, and
     * `ToolExecutor` runs up to eight tools at once on a bounded dispatcher — a
     * shared instance is the defect the 2026-08-16 audit measured losing 76% of
     * concurrent parses in `TimeParser`.
     */
    private fun date(at: Long): String =
        if (at <= 0L) "never" else SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(at))
}
