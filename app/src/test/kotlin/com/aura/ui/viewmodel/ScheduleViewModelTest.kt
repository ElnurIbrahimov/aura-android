package com.aura.ui.viewmodel

import com.aura.tasks.ReminderDao
import com.aura.tasks.ReminderEntity
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import com.aura.tasks.TaskScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class ScheduleViewModelTest {

    private val taskDao = mockk<TaskDao>(relaxed = true)
    private val reminderDao = mockk<ReminderDao>(relaxed = true)
    private val taskScheduler = mockk<TaskScheduler>(relaxed = true)

    @Before
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(): ScheduleViewModel {
        every { taskDao.observeAll() } returns flowOf(emptyList())
        every { reminderDao.observeUpcoming(any()) } returns flowOf(emptyList())
        return ScheduleViewModel(taskDao, reminderDao, taskScheduler)
    }

    @Test
    fun `toggleTask marks pending as done`() = runTest {
        val task = TaskEntity(id = "t1", title = "Test", createdAt = 1000L, status = "pending")
        coEvery { taskDao.get("t1") } returns task
        val vm = makeVm()

        vm.toggleTask("t1")

        coVerify {
            taskDao.insert(match { it.status == "done" && it.completedAt != null })
        }
    }

    @Test
    fun `toggleTask marks done as pending`() = runTest {
        val task = TaskEntity(id = "t1", title = "Test", createdAt = 1000L, status = "done", completedAt = 1000L)
        coEvery { taskDao.get("t1") } returns task
        val vm = makeVm()

        vm.toggleTask("t1")

        coVerify {
            taskDao.insert(match { it.status == "pending" && it.completedAt == null })
        }
    }

    @Test
    fun `toggleTask with unknown id is no-op`() = runTest {
        coEvery { taskDao.get("unknown") } returns null
        val vm = makeVm()

        vm.toggleTask("unknown")

        coVerify(exactly = 0) { taskDao.insert(any()) }
    }

    @Test
    fun `deleteTask cancels scheduler and deletes`() = runTest {
        val vm = makeVm()

        vm.deleteTask("t1")

        coVerify { taskScheduler.cancel("t1") }
        coVerify { taskDao.delete("t1") }
    }

    @Test
    fun `cancelReminder marks as cancelled`() = runTest {
        val reminder = ReminderEntity(id = "r1", workId = "w1", message = "Test", triggerAt = 0L, status = "scheduled")
        coEvery { reminderDao.get("r1") } returns reminder
        val vm = makeVm()

        vm.cancelReminder("r1")

        coVerify { reminderDao.insert(match { it.status == "cancelled" }) }
    }

    @Test
    fun `cancelReminder with unknown id is no-op`() = runTest {
        coEvery { reminderDao.get("unknown") } returns null
        val vm = makeVm()

        vm.cancelReminder("unknown")

        coVerify(exactly = 0) { reminderDao.insert(any()) }
    }

    @Test
    fun `deleteReminder calls dao delete`() = runTest {
        val vm = makeVm()

        vm.deleteReminder("r1")

        coVerify { reminderDao.delete("r1") }
    }
}