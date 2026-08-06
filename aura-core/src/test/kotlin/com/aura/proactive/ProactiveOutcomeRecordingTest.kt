package com.aura.proactive

import com.aura.data.UserPreferences
import com.aura.evolution.EvolutionEvidenceDao
import com.aura.evolution.EvolutionEvidenceEntity
import com.aura.evolution.EvolutionEvidenceRecorder
import com.aura.evolution.EvolutionHooks
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@ExperimentalCoroutinesApi
class ProactiveOutcomeRecordingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val bus = ProactiveEventBus()
    private lateinit var dao: ProactiveEventDao
    private lateinit var interactionDao: ProactiveInteractionDao
    private lateinit var userPreferences: UserPreferences
    private lateinit var evidenceDao: EvolutionEvidenceDao
    private lateinit var hooks: EvolutionHooks

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.resetMain()
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        dao = mockk(relaxed = true)
        coEvery { dao.insert(any()) } returns 1L
        interactionDao = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)
        coEvery { userPreferences.lastSeenProactiveAt } returns MutableStateFlow(0L)
        coEvery { userPreferences.setLastSeenProactiveAt(any()) } just Runs
        evidenceDao = mockk(relaxed = true)
        hooks = EvolutionHooks(EvolutionEvidenceRecorder(evidenceDao))
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `recordInteraction writes interaction row and emits proactive_dismissed evidence`() = runTest(testDispatcher, timeout = 10.seconds) {
        val captured = mutableListOf<EvolutionEvidenceEntity>()
        coEvery { evidenceDao.upsert(capture(captured)) } returns Unit

        val events = ProactiveEvents(
            bus = bus,
            dao = dao,
            interactionDao = interactionDao,
            evolutionHooks = hooks,
            userPreferences = userPreferences,
            scope = CoroutineScope(testDispatcher + kotlinx.coroutines.Job()),
        )
        try {
            advanceUntilIdle()

            events.recordInteraction(eventId = 42L, eventType = "MorningBriefReady", action = "dismissed")
            advanceUntilIdle()

            coVerify { interactionDao.insert(match { it.eventId == 42L && it.action == "dismissed" }) }
            assertEquals(1, captured.size)
            assertEquals("proactive_dismissed", captured[0].kind)
        } finally {
            events.cancel()
        }
    }
}