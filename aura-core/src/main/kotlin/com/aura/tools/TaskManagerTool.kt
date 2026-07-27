package com.aura.tools

import android.content.Context
import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import com.aura.tasks.TaskEntity
import com.aura.tasks.TaskDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manage tasks: create one (optionally with a reminder), list, complete, or delete.
 * Mirrors aura/tools/task_manager.py + aura/tools/task_scheduler.py.
 * Risk: WRITE_LOCAL.
 */
@Singleton
class TaskManagerTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: TaskDao,
    private val taskScheduler: com.aura.tasks.TaskScheduler,
) {
    fun definition() = ToolDefinition(
        name = "manage_tasks",
        description = "Create, list, complete, or delete tasks. Action is 'create'|'list'|'complete'|'delete'. For 'create', 'title' is required, 'description', 'priority' (0-3), 'tags' (comma separated), and 'when' (HH:mm reminder) are optional.",
        parameters = ToolParameters(
            properties = mapOf(
                "action" to ToolProperty(type = "string", description = "create|list|complete|delete"),
                "title" to ToolProperty(type = "string", description = "Task title (for create)"),
                "description" to ToolProperty(type = "string", description = "Optional task description"),
                "id" to ToolProperty(type = "string", description = "Task id (for complete/delete)"),
                "priority" to ToolProperty(type = "integer", description = "Optional priority 0-3 (3=high)"),
                "tags" to ToolProperty(type = "string", description = "Optional comma separated tags"),
                "when" to ToolProperty(type = "string", description = "Optional reminder time HH:mm"),
            ),
            required = listOf("action"),
        ),
    )

    val tool = Tool(
        name = "manage_tasks",
        description = definition().description,
        risk = ToolRisk.WRITE_LOCAL,
        parameters = definition().parameters,
        execute = { call, ctx ->
            when (val action = (call.arguments["action"] as? String)?.lowercase()) {
                "create" -> {
                    val title = call.arguments["title"] as? String ?: return@Tool ToolResult.Error("missing 'title'", "bad_args")
                    val whenStr = call.arguments["when"] as? String
                    val description = (call.arguments["description"] as? String)?.trim() ?: ""
                    val priority = (call.arguments["priority"] as? Number)?.toInt()?.coerceIn(0, 3) ?: 0
                    val tags = (call.arguments["tags"] as? String) ?: ""
                    try {
                        val id = java.util.UUID.randomUUID().toString()
                        val triggerAt = whenStr?.let { TimeParser.parse(it) }
                        taskDao.insert(
                            TaskEntity(
                                id = id,
                                title = title,
                                createdAt = System.currentTimeMillis(),
                                description = description,
                                dueAt = triggerAt,
                                status = "pending",
                                priority = priority,
                                tags = tags,
                            )
                        )
                        if (triggerAt != null && triggerAt > System.currentTimeMillis()) {
                            taskScheduler.schedule(
                                TaskEntity(
                                    id = id,
                                    title = title,
                                    createdAt = System.currentTimeMillis(),
                                    description = description,
                                    dueAt = triggerAt,
                                    status = "pending",
                                    priority = priority,
                                    tags = tags,
                                )
                            )
                        }
                        val whenDisplay = triggerAt?.let { TimeParser.format(it) } ?: "(no reminder)"
                        ToolResult.Ok("Task created (id $id): $title — $whenDisplay")
                    } catch (e: Exception) {
                        ToolResult.Error("create failed: ${e.message}", "exception")
                    }
                }
                "list" -> {
                    try {
                        // Filter to non-completed tasks so "what are my tasks"
                        // doesn't surface a graveyard. The model can call
                        // the data layer directly for completed-task history.
                        val all = taskDao.allPending()
                        if (all.isEmpty()) return@Tool ToolResult.Ok("No open tasks.")
                        val text = all.take(20).mapIndexed { i, t ->
                            val due = t.dueAt?.let { TimeParser.format(it) } ?: "—"
                            "${i + 1}. ${t.title} (due: $due)"
                        }.joinToString("\n")
                        ToolResult.Ok(text)
                    } catch (e: Exception) {
                        ToolResult.Error("list failed: ${e.message}", "exception")
                    }
                }
                "complete" -> {
                    val id = call.arguments["id"] as? String ?: return@Tool ToolResult.Error("missing 'id'", "bad_args")
                    try {
                        taskScheduler.cancel(id)
                        taskDao.markComplete(id, System.currentTimeMillis())
                        ToolResult.Ok("Task $id marked complete.")
                    } catch (e: Exception) {
                        ToolResult.Error("complete failed: ${e.message}", "exception")
                    }
                }
                "delete" -> {
                    val id = call.arguments["id"] as? String ?: return@Tool ToolResult.Error("missing 'id'", "bad_args")
                    try {
                        taskScheduler.cancel(id)
                        taskDao.delete(id)
                        ToolResult.Ok("Task $id deleted.")
                    } catch (e: Exception) {
                        ToolResult.Error("delete failed: ${e.message}", "exception")
                    }
                }
                else -> ToolResult.Error("unknown action: $action", "bad_args")
            }
        },
    category = "productivity")
}
