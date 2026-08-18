package com.aura.ui.viewmodel

import com.aura.agent.Reaction
import com.aura.agent.Turn
import com.aura.taste.TasteEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * A thumbs-up inside an agent conversation must reach a profile.
 *
 * `preference_signals` carries a `projectId` *and* an `agentScope`, and they are
 * different columns answering different questions. `recordTasteSignalFromReaction`
 * wrote the signal with `agentScope = "agent:<id>"` and the default
 * `projectId = ""`, then called `recomputeProfile("agent:<id>")` — whose only
 * parameter is the **project** id. `forProject("agent:<id>")` therefore matched
 * zero rows, `recomputeProfile` returned at its `signals.isEmpty()` guard, and
 * the reaction did nothing at all.
 *
 * Nothing could have noticed. The signal row was written correctly, the UI
 * showed the thumb as selected, and the only observable difference was a style
 * profile that never moved for anyone who talks to Aura through an agent —
 * which is the normal way to use the app.
 */
class TasteReactionScopeTest {

    private fun turn() = Turn(user = "q", assistant = "a", timestamp = 1_000L)

    @Test
    fun `a reaction inside an agent conversation recomputes a profile that exists`() = runTest {
        val engine = mockk<TasteEngine>(relaxed = true)
        val recomputedFor = mutableListOf<String>()
        coEvery { engine.recomputeProfile(any()) } coAnswers { recomputedFor += firstArg<String>() }

        recordTasteSignalFromReaction(
            tasteEngine = engine,
            turn = turn(),
            reaction = Reaction.Up,
            modelId = "ollama:test",
            specialistName = "writer",
            agentId = "researcher",
        )

        assertEquals(
            listOf(""),
            recomputedFor,
            "an agent scope is not a project id — passing one selects no signals and the reaction is lost",
        )
    }

    @Test
    fun `a reaction outside an agent conversation still recomputes the global profile`() = runTest {
        val engine = mockk<TasteEngine>(relaxed = true)
        val recomputedFor = mutableListOf<String>()
        coEvery { engine.recomputeProfile(any()) } coAnswers { recomputedFor += firstArg<String>() }

        recordTasteSignalFromReaction(
            tasteEngine = engine,
            turn = turn(),
            reaction = Reaction.Down,
            modelId = "ollama:test",
            specialistName = null,
            agentId = null,
        )

        assertEquals(listOf(""), recomputedFor)
    }

    @Test
    fun `the agent scope is still recorded on the signal itself`() = runTest {
        // The scope is not wrong, only the place it was being used. It stays on
        // the row so a future per-agent aggregation has the data; there is no
        // scoped profile *writer* yet, which is exactly why routing the
        // recompute through it silently dropped the signal.
        val engine = mockk<TasteEngine>(relaxed = true)
        val scopes = mutableListOf<String>()
        coEvery {
            engine.recordSignal(any(), any(), any(), any(), any(), any(), any())
        } coAnswers { scopes += arg<String>(6) }

        recordTasteSignalFromReaction(
            tasteEngine = engine,
            turn = turn(),
            reaction = Reaction.Up,
            modelId = "ollama:test",
            specialistName = "writer",
            agentId = "researcher",
        )

        assertEquals(listOf("agent:researcher"), scopes)
    }
}
