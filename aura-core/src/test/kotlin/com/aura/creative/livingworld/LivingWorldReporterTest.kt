package com.aura.creative.livingworld

import com.aura.proactive.ProactiveEvents
import com.aura.proactive.ProactiveNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The report's ordering law, extended to the notifier: record first, notify
 * after, and never notify a world that produced nothing.
 */
class LivingWorldReporterTest {

    private val store = mockk<LivingWorldStore>(relaxed = true)
    private val narrator = mockk<WorldNarrator>(relaxed = true)
    private val proactiveEvents = mockk<ProactiveEvents>(relaxed = true)
    private val notifier = mockk<ProactiveNotifier>(relaxed = true)

    private fun world() = LivingWorldEntity(
        id = "w1", projectId = "p1", branchId = "b1", rootSeed = 1L,
        worldEpochMs = 0L, currentTick = 10L, stateJson = "{}",
        createdAt = 0L, updatedAt = 0L,
    )

    private fun narratedEvent(now: Long) = LivingEventEntity(
        id = "w1#9.0", worldId = "w1", branchId = "b1", tickIndex = 9, seq = 0,
        kind = WorldEngine.KIND_CLAIM_WON, actorId = "f_a", summary = "s",
        narration = "Ashfall took the valley.", narratedAt = now,
    )

    @Test
    fun `nothing narrated means no card and no notification`() = runBlocking {
        coEvery { narrator.narratePending(any(), any()) } returns 0

        LivingWorldReporter(store, narrator, null, proactiveEvents, notifier).report(world(), now = 1_000L)

        coVerify(exactly = 0) { proactiveEvents.record(any()) }
        coVerify(exactly = 0) { notifier.maybeNotify(any(), any()) }
    }

    @Test
    fun `the card is recorded before the notifier is asked`() = runBlocking {
        val now = 1_000L
        coEvery { narrator.narratePending(any(), any()) } returns 1
        coEvery { store.recentEvents("w1", any()) } returns listOf(narratedEvent(now))
        coEvery { store.decode(any()) } returns WorldState()

        LivingWorldReporter(store, narrator, null, proactiveEvents, notifier).report(world(), now = now)

        coVerifyOrder {
            proactiveEvents.record(any())
            notifier.maybeNotify(any(), any())
        }
    }

    @Test
    fun `a reporter built without a notifier reports exactly as before`() = runBlocking {
        val now = 1_000L
        coEvery { narrator.narratePending(any(), any()) } returns 1
        coEvery { store.recentEvents("w1", any()) } returns listOf(narratedEvent(now))
        coEvery { store.decode(any()) } returns WorldState()

        LivingWorldReporter(store, narrator, null, proactiveEvents).report(world(), now = now)

        coVerify(exactly = 1) { proactiveEvents.record(any()) }
    }
}
