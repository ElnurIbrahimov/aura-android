package com.aura.agent.state

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.agent.AgentDatabase
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AgentStateStoreTest {

    private lateinit var db: AgentDatabase
    private lateinit var store: AgentStateStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Insert minimal agent rows so FK constraints on state/relationship/observation tables pass.
        runBlocking {
            db.agentDao().insertAll(listOf(
                com.aura.agent.AgentEntity(
                    id = "agent_general", name = "general", icon = "i",
                    description = "d", identity = "id", toolsAllowed = "",
                    isBuiltin = true, isDefault = true,
                ),
                com.aura.agent.AgentEntity(
                    id = "agent_researcher", name = "researcher", icon = "i",
                    description = "d", identity = "id", toolsAllowed = "",
                    isBuiltin = true,
                ),
                com.aura.agent.AgentEntity(
                    id = "agent_executive", name = "executive", icon = "i",
                    description = "d", identity = "id", toolsAllowed = "",
                    isBuiltin = true,
                ),
                com.aura.agent.AgentEntity(
                    id = "a", name = "a", icon = "i",
                    description = "d", identity = "id", toolsAllowed = "",
                    isBuiltin = false,
                ),
                com.aura.agent.AgentEntity(
                    id = "b", name = "b", icon = "i",
                    description = "d", identity = "id", toolsAllowed = "",
                    isBuiltin = false,
                ),
            ))
        }
        store = AgentStateStore(
            db.agentStateDao(),
            db.agentRelationshipDao(),
            db.agentObservationDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── State ──

    @Test
    fun ensureState_createsRowWithDefaults() = runBlocking {
        store.ensureState("agent_general")
        val state = store.getState("agent_general")
        assertNotNull(state)
        assertEquals(65f, state!!.mood, 0.1f)
        assertEquals(80f, state.energy, 0.1f)
        assertEquals(0f, state.stanceOnUser, 0.1f)
    }

    @Test
    fun setMoodEnergy_clampsToRange() = runBlocking {
        store.ensureState("agent_general")
        store.setMoodEnergy("agent_general", 150f, -20f)
        val state = store.getState("agent_general")!!
        assertEquals(100f, state.mood, 0.1f)
        assertEquals(0f, state.energy, 0.1f)
    }

    @Test
    fun recordParticipation_incrementsCount() = runBlocking {
        store.ensureState("agent_general")
        store.recordParticipation("agent_general")
        store.recordParticipation("agent_general")
        val state = store.getState("agent_general")!!
        assertEquals(2, state.participationCount)
    }

    // ── Relationships ──

    @Test
    fun recordInteraction_createsNewRelationship() = runBlocking {
        store.recordInteraction("agent_general", "agent_researcher", 10f)
        val rel = store.getRelationship("agent_general", "agent_researcher")
        assertNotNull(rel)
        assertEquals(10f, rel!!.affinity, 0.1f)
        assertEquals(1, rel.collaborationCount)
    }

    @Test
    fun recordInteraction_accumulatesAffinity() = runBlocking {
        store.recordInteraction("agent_general", "agent_researcher", 10f)
        store.recordInteraction("agent_general", "agent_researcher", 5f)
        val rel = store.getRelationship("agent_general", "agent_researcher")!!
        assertEquals(15f, rel.affinity, 0.1f)
        assertEquals(2, rel.collaborationCount)
    }

    @Test
    fun recordInteraction_negativeDelta_incrementsConflict() = runBlocking {
        store.recordInteraction("agent_general", "agent_executive", -15f)
        val rel = store.getRelationship("agent_general", "agent_executive")!!
        assertEquals(-15f, rel.affinity, 0.1f)
        assertEquals(1, rel.conflictCount)
        assertEquals(0, rel.collaborationCount)
    }

    @Test
    fun recordInteraction_clampsAt100() = runBlocking {
        repeat(20) { store.recordInteraction("a", "b", 10f) }
        val rel = store.getRelationship("a", "b")!!
        assertEquals(100f, rel.affinity, 0.1f)
    }

    @Test
    fun recordInteraction_clampsAtMinus100() = runBlocking {
        repeat(20) { store.recordInteraction("a", "b", -10f) }
        val rel = store.getRelationship("a", "b")!!
        assertEquals(-100f, rel.affinity, 0.1f)
    }

    @Test
    fun getRelationshipsFor_returnsBothDirections() = runBlocking {
        store.recordInteraction("agent_general", "agent_researcher", 10f)
        val rels = store.getRelationshipsFor("agent_researcher")
        assertEquals(1, rels.size)
    }

    // ── Observations ──

    @Test
    fun resolveAllForAgent_resolvesByTargetType() = runBlocking {
        store.addObservation("agent_general", "user", content = "User obs 1")
        store.addObservation("agent_general", "agent", content = "Agent obs 1")
        store.resolveAllForAgent("agent_general", "user")
        val userObs = store.unresolvedObservations("agent_general")
        assertEquals(1, userObs.size)
        assertEquals("agent", userObs[0].targetType)
    }

}