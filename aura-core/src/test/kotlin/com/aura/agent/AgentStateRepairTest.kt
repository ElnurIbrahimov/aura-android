package com.aura.agent

import com.aura.agent.state.AgentStateEntity
import com.aura.agent.state.AgentStateStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `agent_state` rows must come back after the cascade that deleted them.
 *
 * `agents` was written with `INSERT OR REPLACE` while five tables cascade off
 * it, so every re-save — `refreshBuiltinDescriptions` on each launch, and the
 * agent editor on each save — deleted that agent's state, relationships,
 * observations, forum posts and votes. The REPLACE is fixed
 * ([CascadeParentReplaceAuditTest]), but installs carrying the damage cannot
 * recover on their own: `ensureState` was only reachable inside `seedBuiltins`,
 * which returns early once any agent exists.
 *
 * The loss was invisible from both sides. Every `AgentStateDao` writer is an
 * `UPDATE … WHERE agentId = :agentId`, so it matched zero rows and reported
 * success; and the council screens substitute a transient default when
 * `getState` returns null, so they rendered plausible moods over an empty
 * table.
 */
class AgentStateRepairTest {

    private val dao: AgentDao = mockk(relaxed = true)
    private val stateStore: AgentStateStore = mockk(relaxed = true)
    private val store = AgentStore(dao, stateStore = stateStore)

    private fun agent(id: String) = AgentEntity(
        id = id,
        name = id,
        icon = "",
        description = "",
        identity = "",
        toolsAllowed = "",
    )

    @Test
    fun `recreates state only for agents missing it`() = runTest {
        coEvery { dao.allOnce() } returns listOf(agent("a1"), agent("a2"), agent("a3"))
        coEvery { stateStore.getState("a1") } returns AgentStateEntity(agentId = "a1")
        coEvery { stateStore.getState("a2") } returns null
        coEvery { stateStore.getState("a3") } returns null

        store.ensureAllAgentStates()

        coVerify(exactly = 0) { stateStore.ensureState("a1") }
        coVerify(exactly = 1) { stateStore.ensureState("a2") }
        coVerify(exactly = 1) { stateStore.ensureState("a3") }
    }

    /**
     * The repair is a startup path, so one unreadable row must not stop the
     * rest. Without this, a single failure would leave every later agent
     * unrepaired for as long as that row kept failing.
     */
    @Test
    fun `one failing agent does not abort the repair of the others`() = runTest {
        coEvery { dao.allOnce() } returns listOf(agent("a1"), agent("a2"))
        coEvery { stateStore.getState("a1") } throws IllegalStateException("db busy")
        coEvery { stateStore.getState("a2") } returns null

        store.ensureAllAgentStates()

        coVerify(exactly = 1) { stateStore.ensureState("a2") }
    }

    @Test
    fun `is a no-op when no state store is wired`() = runTest {
        val storeWithoutState = AgentStore(dao)
        storeWithoutState.ensureAllAgentStates()
        coVerify(exactly = 0) { dao.allOnce() }
    }

    /**
     * `create()` never called `ensureState`, so user-created agents have never
     * had a state row — independent of the cascade. The repair covers them
     * because it walks every agent, not just the builtins.
     */
    @Test
    fun `covers user-created agents, not only builtins`() = runTest {
        val custom = agent("agent_custom_1").copy(isBuiltin = false)
        coEvery { dao.allOnce() } returns listOf(custom)
        coEvery { stateStore.getState(custom.id) } returns null

        store.ensureAllAgentStates()

        coVerify(exactly = 1) { stateStore.ensureState(custom.id) }
    }
}
