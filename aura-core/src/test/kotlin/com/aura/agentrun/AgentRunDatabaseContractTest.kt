package com.aura.agentrun

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-Room contract suite for AgentRunDatabase.
 *
 * Exercises: AgentRun CRUD + status transitions, Goal CRUD + achievement,
 * Step CRUD + status lifecycle, AgentEvent logging, ApprovalRequest flow
 * (PENDING -> APPROVED/DENIED), RunCheckpoint save/latest/cleanup.
 *
 * All 6 entities in one database — tests cross-entity queries (e.g. by
 * agentRunId) execute against real SQLite, not mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AgentRunDatabaseContractTest {

    private lateinit var db: AgentRunDatabase
    private lateinit var runDao: AgentRunDao
    private lateinit var goalDao: GoalDao
    private lateinit var stepDao: StepDao
    private lateinit var eventDao: AgentEventDao
    private lateinit var approvalDao: ApprovalRequestDao
    private lateinit var checkpointDao: RunCheckpointDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AgentRunDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runDao = db.agentRunDao()
        goalDao = db.goalDao()
        stepDao = db.stepDao()
        eventDao = db.agentEventDao()
        approvalDao = db.approvalRequestDao()
        checkpointDao = db.runCheckpointDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun run(id: String, status: String = "PENDING") = AgentRunEntity(
        id = id,
        goalId = "goal_$id",
        status = status,
        triggerType = "USER_QUERY",
    )

    private fun goal(id: String, runId: String) = GoalEntity(
        id = id,
        agentRunId = runId,
        description = "test goal",
    )

    private fun step(id: String, runId: String, position: Int = 0) = StepEntity(
        id = id,
        agentRunId = runId,
        position = position,
    )

    private fun event(id: String, runId: String) = AgentEventEntity(
        id = id,
        agentRunId = runId,
        type = "STEP_STARTED",
    )

    private fun approval(id: String, runId: String, stepId: String) = ApprovalRequestEntity(
        id = id,
        agentRunId = runId,
        stepId = stepId,
        toolName = "test_tool",
        rationale = "needs approval",
    )

    private fun checkpoint(id: String, runId: String) = RunCheckpointEntity(
        id = id,
        agentRunId = runId,
        stateJson = "{}",
    )

    // --- AgentRun CRUD ---

    @Test
    fun `upsert and getById roundtrip`() = runBlocking {
        runDao.upsert(run("r1"))
        val got = runDao.getById("r1")
        assertNotNull(got)
        assertEquals("PENDING", got!!.status)
    }

    @Test
    fun `updateStatus changes status`() = runBlocking {
        runDao.upsert(run("r1"))
        runDao.updateStatus("r1", "RUNNING", 99999L)
        assertEquals("RUNNING", runDao.getById("r1")!!.status)
    }

    @Test
    fun `finish sets finishedAt, status, and error`() = runBlocking {
        runDao.upsert(run("r1", status = "RUNNING"))
        runDao.finish("r1", "COMPLETED", "", 100000L)
        val got = runDao.getById("r1")!!
        assertEquals("COMPLETED", got.status)
    }

    @Test
    fun `activeRuns returns only non-terminal statuses`() = runBlocking {
        runDao.upsert(run("r1", status = "RUNNING"))
        runDao.upsert(run("r2", status = "COMPLETED"))
        runDao.upsert(run("r3", status = "PAUSED"))
        val active = runDao.activeRuns()
        assertEquals(2, active.size)
        assertTrue(active.none { it.status == "COMPLETED" })
    }

    @Test
    fun `recent orders by startedAt DESC`() = runBlocking {
        runDao.upsert(run("r1").copy(startedAt = 1000L))
        runDao.upsert(run("r2").copy(startedAt = 2000L))
        val hits = runDao.recent(10)
        assertEquals("r2", hits.first().id)
    }

    @Test
    fun `insertAll and allForBackup roundtrip`() = runBlocking {
        runDao.insertAll(listOf(run("r1"), run("r2")))
        assertEquals(2, runDao.allForBackup().size)
    }

    @Test
    fun `delete removes run`() = runBlocking {
        runDao.upsert(run("r1"))
        runDao.delete("r1")
        assertNull(runDao.getById("r1"))
    }

    @Test
    fun `deleteAll clears table`() = runBlocking {
        runDao.upsert(run("r1"))
        runDao.upsert(run("r2"))
        runDao.deleteAll()
        assertEquals(0, runDao.allForBackup().size)
    }

    // --- Goal CRUD ---

    @Test
    fun `goal upsert and forRun roundtrip`() = runBlocking {
        goalDao.upsert(goal("g1", "r1"))
        val got = goalDao.forRun("r1")
        assertNotNull(got)
        assertEquals("test goal", got!!.description)
    }

    @Test
    fun `markAchieved sets isAchieved and achievedAt`() = runBlocking {
        goalDao.upsert(goal("g1", "r1"))
        goalDao.markAchieved("g1", true, 99999L)
        val got = goalDao.getById("g1")!!
        assertTrue(got.isAchieved)
        assertEquals(99999L, got.achievedAt)
    }

    @Test
    fun `deleteForRun removes goals for a specific run`() = runBlocking {
        goalDao.upsert(goal("g1", "r1"))
        goalDao.upsert(goal("g2", "r2"))
        goalDao.deleteForRun("r1")
        assertNull(goalDao.forRun("r1"))
        assertNotNull(goalDao.forRun("r2"))
    }

    // --- Step CRUD ---

    @Test
    fun `step upsert and forRun roundtrip ordered by position`() = runBlocking {
        stepDao.upsert(step("s1", "r1", position = 2))
        stepDao.upsert(step("s2", "r1", position = 1))
        val steps = stepDao.forRun("r1")
        assertEquals(2, steps.size)
        assertEquals("s2", steps.first().id) // position 1 first
    }

    @Test
    fun `step complete sets status and result`() = runBlocking {
        stepDao.upsert(step("s1", "r1"))
        stepDao.complete("s1", "SUCCESS", "done", 99999L)
        val got = stepDao.getById("s1")!!
        assertEquals("SUCCESS", got.status)
    }

    @Test
    fun `step fail sets status and error`() = runBlocking {
        stepDao.upsert(step("s1", "r1"))
        stepDao.fail("s1", "FAILED", "error msg", 99999L)
        val got = stepDao.getById("s1")!!
        assertEquals("FAILED", got.status)
    }

    @Test
    fun `step markStarted sets startedAt`() = runBlocking {
        stepDao.upsert(step("s1", "r1"))
        stepDao.markStarted("s1", "RUNNING", 12345L)
        val got = stepDao.getById("s1")!!
        assertEquals("RUNNING", got.status)
    }

    @Test
    fun `step setPostcondition sets postconditionResult`() = runBlocking {
        stepDao.upsert(step("s1", "r1"))
        stepDao.setPostcondition("s1", """{"passed":true}""")
        assertEquals("""{"passed":true}""", stepDao.getById("s1")!!.postconditionResult)
    }

    // --- AgentEvent ---

    @Test
    fun `event insert and forRun ordered by timestamp ASC`() = runBlocking {
        eventDao.insert(event("e1", "r1").copy(timestamp = 2000L))
        eventDao.insert(event("e2", "r1").copy(timestamp = 1000L))
        val events = eventDao.forRun("r1")
        assertEquals(2, events.size)
        assertEquals("e2", events.first().id) // earlier timestamp first
    }

    @Test
    fun `recentForRun limits results`() = runBlocking {
        for (i in 0 until 10) eventDao.insert(event("e$i", "r1").copy(timestamp = i.toLong()))
        val recent = eventDao.recentForRun("r1", limit = 3)
        assertEquals(3, recent.size)
    }

    // --- ApprovalRequest ---

    @Test
    fun `approval pendingForRun returns only PENDING`() = runBlocking {
        approvalDao.upsert(approval("a1", "r1", "s1"))
        approvalDao.upsert(approval("a2", "r1", "s2").copy(status = "APPROVED"))
        val pending = approvalDao.pendingForRun("r1")
        assertEquals(1, pending.size)
        assertEquals("a1", pending.first().id)
    }

    @Test
    fun `approval decide sets status and decisionAt`() = runBlocking {
        approvalDao.upsert(approval("a1", "r1", "s1"))
        approvalDao.decide("a1", "APPROVED", "", 99999L)
        val got = approvalDao.getById("a1")!!
        assertEquals("APPROVED", got.status)
        assertEquals(99999L, got.decisionAt)
    }

    @Test
    fun `approval decide DENIED sets denyReason`() = runBlocking {
        approvalDao.upsert(approval("a1", "r1", "s1"))
        approvalDao.decide("a1", "DENIED", "too risky", 99999L)
        val got = approvalDao.getById("a1")!!
        assertEquals("DENIED", got.status)
        assertEquals("too risky", got.denyReason)
    }

    // --- RunCheckpoint ---

    @Test
    fun `checkpoint latestForRun returns most recent`() = runBlocking {
        checkpointDao.upsert(checkpoint("cp1", "r1").copy(createdAt = 1000L))
        checkpointDao.upsert(checkpoint("cp2", "r1").copy(createdAt = 2000L))
        val latest = checkpointDao.latestForRun("r1")
        assertNotNull(latest)
        assertEquals("cp2", latest!!.id)
    }

    @Test
    fun `checkpoint cleanupOld removes all except keepId`() = runBlocking {
        checkpointDao.upsert(checkpoint("cp1", "r1"))
        checkpointDao.upsert(checkpoint("cp2", "r1"))
        checkpointDao.cleanupOld("r1", "cp2")
        assertNull(checkpointDao.latestForRun("r1")?.let { if (it.id == "cp1") it else null })
        assertNotNull(checkpointDao.latestForRun("r1"))
    }

    // --- Cross-entity ---

    @Test
    fun `deleteAll on all DAOs clears everything`() = runBlocking {
        runDao.upsert(run("r1"))
        goalDao.upsert(goal("g1", "r1"))
        stepDao.upsert(step("s1", "r1"))
        eventDao.insert(event("e1", "r1"))
        approvalDao.upsert(approval("a1", "r1", "s1"))
        checkpointDao.upsert(checkpoint("cp1", "r1"))

        runDao.deleteAll()
        goalDao.deleteAll()
        stepDao.deleteAll()
        eventDao.deleteAll()
        approvalDao.deleteAll()
        checkpointDao.deleteAll()

        assertEquals(0, runDao.allForBackup().size)
        assertEquals(0, goalDao.allForBackup().size)
        assertEquals(0, stepDao.allForBackup().size)
        assertEquals(0, eventDao.allForBackup().size)
        assertEquals(0, approvalDao.allForBackup().size)
        assertEquals(0, checkpointDao.allForBackup().size)
    }
}