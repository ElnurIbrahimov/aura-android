package com.aura.proactive

import com.aura.agent.ConversationStore
import com.aura.kg.KnowledgeGraphRepository
import com.aura.memory.MemoryDao
import com.aura.memory.MemoryEntity
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checks that decide whether a suggestion helped.
 *
 * The hard part is not the SQL, it is picking signals that are **not
 * confounded**. Two of the obvious choices are wrong in the same way: task
 * salience and memory decay score both drift downward on their own, on the very
 * same six-hourly worker this pass rides, so a subject near a threshold would
 * cross it unaided inside the horizon and be recorded as a success the
 * suggestion had nothing to do with. Those two cases are what most of this file
 * is about.
 */
class ProactiveOutcomePassTest {

    private val outcomeDao = mockk<ProactiveOutcomeDao>(relaxed = true)
    private val taskDao = mockk<TaskDao>(relaxed = true)
    private val memoryDao = mockk<MemoryDao>(relaxed = true)
    private val conversationStore = mockk<ConversationStore>(relaxed = true)
    private val kg = mockk<KnowledgeGraphRepository>(relaxed = true)
    private val pass = ProactiveOutcomePass(outcomeDao, taskDao, memoryDao, conversationStore, kg)

    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    private fun row(
        subjectKind: String,
        subjectIds: String = "[]",
        baseline: String = "{}",
        postedAt: Long = now - 3 * day,
    ) = ProactiveOutcomeEntity(
        id = 1L, eventId = 7L, findingType = "stuck_tasks", subjectKind = subjectKind,
        subjectIds = subjectIds, baselineJson = baseline, postedAt = postedAt, dueAt = postedAt + day,
    )

    private fun task(
        status: String = "pending",
        salience: Double = 1.0,
        deferCount: Int = 0,
        lastTouchedAt: Long = 0L,
    ) = TaskEntity(
        id = "t1", title = "Fix the roof", createdAt = now - 30 * day, status = status,
        salience = salience, deferCount = deferCount, lastTouchedAt = lastTouchedAt,
    )

    private fun closedAs(): Pair<String, String> {
        val outcome = slot<String>()
        val reason = slot<String>()
        coVerify { outcomeDao.close(any(), capture(outcome), any(), capture(reason)) }
        return outcome.captured to reason.captured
    }

    @Test
    fun `a task that decayed on its own is not counted as a success`() = runTest {
        // The confound that matters. TaskDecayPass runs on this same worker and
        // moves salience roughly 9% over a 72-hour horizon, so a task sitting
        // near the quiet threshold crosses it without the user doing anything.
        coEvery { outcomeDao.due(any(), any()) } returns listOf(
            row(ProactiveOutcomeEntity.SUBJECT_TASK, """["t1"]""", """{"deferCount":0,"lastTouchedAt":0}"""),
        )
        coEvery { taskDao.get("t1") } returns task(salience = 0.02, deferCount = 0, lastTouchedAt = 0L)

        pass.run(now)

        assertEquals(ProactiveOutcomeEntity.OUTCOME_IGNORED, closedAs().first)
    }

    @Test
    fun `pushing the task back counts, because only a deliberate act moves that`() = runTest {
        coEvery { outcomeDao.due(any(), any()) } returns listOf(
            row(ProactiveOutcomeEntity.SUBJECT_TASK, """["t1"]""", """{"deferCount":0,"lastTouchedAt":0}"""),
        )
        coEvery { taskDao.get("t1") } returns task(deferCount = 1)

        pass.run(now)
        assertEquals(ProactiveOutcomeEntity.OUTCOME_RESOLVED, closedAs().first)
    }

    @Test
    fun `finishing the task counts`() = runTest {
        coEvery { outcomeDao.due(any(), any()) } returns listOf(
            row(ProactiveOutcomeEntity.SUBJECT_TASK, """["t1"]""", """{"deferCount":0,"lastTouchedAt":0}"""),
        )
        coEvery { taskDao.get("t1") } returns task(status = "done")

        pass.run(now)
        val (outcome, reason) = closedAs()
        assertEquals(ProactiveOutcomeEntity.OUTCOME_RESOLVED, outcome)
        assertTrue(reason.isNotBlank(), "an outcome must be able to explain itself")
    }

    @Test
    fun `a memory whose decay score moved but was never read does not count`() = runTest {
        // Same confound in the memory table: runDecayPass moves decayScore,
        // and only an actual read moves accessCount.
        coEvery { outcomeDao.due(any(), any()) } returns listOf(
            row(ProactiveOutcomeEntity.SUBJECT_MEMORY_SET, """["m1"]""", """{"count":4}"""),
        )
        coEvery { memoryDao.getById("m1") } returns MemoryEntity(
            id = "m1", content = "x", source = "user", category = "fact",
            createdAt = now - 40 * day, accessedAt = now,
            accessCount = 0, decayScore = 0.1f,
        )

        pass.run(now)
        assertEquals(ProactiveOutcomeEntity.OUTCOME_IGNORED, closedAs().first)
    }

    @Test
    fun `a memory that was actually read counts`() = runTest {
        coEvery { outcomeDao.due(any(), any()) } returns listOf(
            row(ProactiveOutcomeEntity.SUBJECT_MEMORY_SET, """["m1"]""", """{"count":4}"""),
        )
        coEvery { memoryDao.getById("m1") } returns MemoryEntity(
            id = "m1", content = "x", source = "user", category = "fact",
            createdAt = now - 40 * day, accessedAt = now,
            accessCount = 3, decayScore = 0.9f,
        )

        pass.run(now)
        assertEquals(ProactiveOutcomeEntity.OUTCOME_RESOLVED, closedAs().first)
    }

    @Test
    fun `a conversation started after the nudge counts`() = runTest {
        coEvery { outcomeDao.due(any(), any()) } returns listOf(
            row(ProactiveOutcomeEntity.SUBJECT_CONVERSATION, baseline = """{"lastActivityAt":${now - 10 * day}}"""),
        )
        coEvery { conversationStore.recent(1) } returns listOf(
            mockk(relaxed = true) { coEvery { updatedAt } returns now - day },
        )

        pass.run(now)
        assertEquals(ProactiveOutcomeEntity.OUTCOME_RESOLVED, closedAs().first)
    }

    @Test
    fun `nothing stays pending forever`() = runTest {
        coEvery { outcomeDao.due(any(), any()) } returns emptyList()
        coEvery { outcomeDao.staleOpen(any(), any()) } returns listOf(row(ProactiveOutcomeEntity.SUBJECT_TASK))

        assertEquals(1, pass.run(now))
        val (outcome, reason) = closedAs()
        assertEquals(ProactiveOutcomeEntity.OUTCOME_IGNORED, outcome)
        assertTrue(reason.contains("two weeks"))
    }

    @Test
    fun `an empty queue costs nothing`() = runTest {
        coEvery { outcomeDao.due(any(), any()) } returns emptyList()
        coEvery { outcomeDao.staleOpen(any(), any()) } returns emptyList()
        assertEquals(0, pass.run(now))
        coVerify(exactly = 0) { outcomeDao.close(any(), any(), any(), any()) }
    }

    @Test
    fun `the three unmeasurable finding types get no horizon`() {
        // Inventing a checker for these would mean measuring something adjacent
        // and calling it success — tension decays toward a baseline on its own,
        // the calendar cannot report attendance, and a pattern alert proposes
        // no resolution to observe.
        for (type in listOf(
            ProactiveFindingType.DEADLINE_APPROACHING,
            ProactiveFindingType.STRESS_CORRELATION,
            ProactiveFindingType.PATTERN_ALERT,
        )) {
            assertEquals(0L, ProactiveOutcomePass.horizonFor(type), "${type.wire} should be unobservable")
        }
        for (type in listOf(
            ProactiveFindingType.STUCK_TASKS,
            ProactiveFindingType.STALE_MEMORIES,
            ProactiveFindingType.RELATIONSHIP_GAP,
            ProactiveFindingType.CONTRADICTION_ALERT,
            ProactiveFindingType.PRIORITY_SHIFT,
        )) {
            assertTrue(ProactiveOutcomePass.horizonFor(type) > 0L, "${type.wire} should be checkable")
        }
    }
}
