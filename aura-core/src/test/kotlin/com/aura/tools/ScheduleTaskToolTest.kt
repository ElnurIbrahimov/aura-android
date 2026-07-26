package com.aura.tools

import com.aura.tasks.ReminderScheduler
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleTaskToolTest {
    @Test
    fun `scheduleTaskTool creates task and reminder`() = runTest {
        val taskDao: TaskDao = mockk(relaxed = true)
        val reminderScheduler: ReminderScheduler = mockk(relaxed = true)
        val tool = ScheduleTaskTool(taskDao, reminderScheduler).tool

        val result = tool.execute(
            com.aura.agent.ToolCall(
                id = "tc1",
                name = "schedule_task",
                arguments = mapOf(
                    "title" to "Follow-up",
                    "due_at" to "2026-08-02T09:00:00Z",
                    "action" to "notify",
                    "prompt" to "Ask user how it went",
                    "recurrence" to "daily",
                ),
            ),
            com.aura.agent.ToolContext(conversationId = "conv-1"),
        )

        println("RESULT=$result")
        assertTrue(result is com.aura.agent.ToolResult.Ok, result.toString())
        val taskSlot = slot<TaskEntity>()
        coVerify { taskDao.insert(capture(taskSlot)) }
        assertEquals("Follow-up", taskSlot.captured.title)
        val reminderIdSlot = slot<String>()
        coVerify { reminderScheduler.create("notify: Follow-up — Ask user how it went", any(), "daily", eq(taskSlot.captured.id), capture(reminderIdSlot)) }

    }

    @Test
    fun `scheduleTaskTool invalid action returns error`() = runTest {
        val tool = ScheduleTaskTool(mockk(relaxed=true), mockk(relaxed=true)).tool
        val result = tool.execute(
            com.aura.agent.ToolCall(id = "tc2", name = "schedule_task", arguments = mapOf("title" to "X", "due_at" to "2026-08-02T09:00:00Z", "action" to "bad", "prompt" to "p")),
            com.aura.agent.ToolContext(conversationId = "conv-1"),
        )
        assertTrue(result is com.aura.agent.ToolResult.Error)
    }

    @Test
    fun `scheduleTaskTool invalid date returns error`() = runTest {
        val tool = ScheduleTaskTool(mockk(relaxed=true), mockk(relaxed=true)).tool
        val result = tool.execute(
            com.aura.agent.ToolCall(id = "tc3", name = "schedule_task", arguments = mapOf("title" to "X", "due_at" to "not-a-date", "action" to "notify", "prompt" to "p")),
            com.aura.agent.ToolContext(conversationId = "conv-1"),
        )
        assertTrue(result is com.aura.agent.ToolResult.Error)
    }
}
