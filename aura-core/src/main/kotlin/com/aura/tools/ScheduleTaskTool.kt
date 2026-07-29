package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import com.aura.tasks.TaskScheduler
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import java.time.Instant
import com.aura.tools.TimeParser
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Let the agent schedule a future task (notification or chat start) for itself. */
@Singleton
class ScheduleTaskTool @Inject constructor(
    private val taskDao: TaskDao,
    private val taskScheduler: TaskScheduler,
) {
    fun definition() = ToolDefinition(
        name = "schedule_task",
        description = "Schedule a future task or reminder. due_at is ISO 8601. action is notify or start_chat. recurrence is optional none, daily, weekdays, weekly, or monthly.",
        parameters = ToolParameters(
            properties = mapOf(
                "title" to ToolProperty(type = "string", description = "Short task title"),
                "due_at" to ToolProperty(type = "string", description = "ISO 8601 timestamp"),
                "action" to ToolProperty(type = "string", description = "notify | start_chat"),
                "prompt" to ToolProperty(type = "string", description = "Message for notify, or opening prompt for start_chat"),
                "recurrence" to ToolProperty(type = "string", description = "Optional: none, daily, weekdays, weekly, monthly"),
            ),
            required = listOf("title", "due_at", "action", "prompt"),
        ),
    )

    val tool = Tool(
        name = "schedule_task",
        description = definition().description,
        risk = ToolRisk.WRITE_LOCAL,
        parameters = definition().parameters,
        execute = { call, _ ->
            val title = call.arguments["title"] as? String
                ?: return@Tool ToolResult.Error("missing 'title'", "bad_args")
            val dueAtStr = call.arguments["due_at"] as? String
                ?: return@Tool ToolResult.Error("missing 'due_at'", "bad_args")
            val action = (call.arguments["action"] as? String)?.lowercase()
                ?: return@Tool ToolResult.Error("missing 'action'", "bad_args")
            val prompt = call.arguments["prompt"] as? String
                ?: return@Tool ToolResult.Error("missing 'prompt'", "bad_args")
            val recurrence = call.arguments["recurrence"] as? String ?: "none"
            if (action !in setOf("notify", "start_chat")) {
                return@Tool ToolResult.Error("action must be notify or start_chat", "bad_args")
            }
            val dueAt = runCatching { Instant.parse(dueAtStr).toEpochMilli() }.getOrNull()
                ?: return@Tool ToolResult.Error("could not parse due_at: $dueAtStr", "bad_args")
            val id = UUID.randomUUID().toString()
            val task = TaskEntity(
                id = id,
                title = title,
                description = prompt,
                createdAt = System.currentTimeMillis(),
                dueAt = dueAt,
                recurrence = recurrence,
                status = "pending",
            )
            taskDao.insert(task)
            taskScheduler.schedule(task)
            ToolResult.Ok("Scheduled '$title' for ${TimeParser.format(dueAt)} ($action, $recurrence)")
        },
        category = "productivity",
    )
}
