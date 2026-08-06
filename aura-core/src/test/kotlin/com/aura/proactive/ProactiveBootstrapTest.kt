package com.aura.proactive

import android.content.Context
import com.aura.data.UserPreferences
import com.aura.evolution.EvolutionScheduler
import com.aura.mcp.McpClientManager
import com.aura.mcp.McpToolBridge
import com.aura.memory.MemoryStore
import com.aura.security.SecureDataStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the [ProactiveBootstrap.applyGates] gating logic. The
 * bootstrap reads the morning-brief and calendar-monitor
 * preference gates and only schedules / cancels the matching
 * worker when the gate is on. When the gate is off, any
 * previously-scheduled worker is cancelled.
 *
 * The widget-refresh broadcast path in [ProactiveBootstrap.start]
 * needs a real Android Context and is not unit-tested here — it's
 * exercised in instrumented tests or on-device. The pure-Kotlin
 * gate decision is what applyGates covers.
 *
 * The start()-based tests inject `backgroundScope` so every
 * bootstrap coroutine runs on the runTest scheduler — fully
 * deterministic, no real-time polling.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProactiveBootstrapTest {

    private lateinit var context: Context
    private lateinit var scheduler: ProactiveScheduler
    private lateinit var memoryStore: MemoryStore
    private lateinit var userPreferences: UserPreferences
    private lateinit var evolutionScheduler: EvolutionScheduler
    private lateinit var mcpClientManager: McpClientManager
    private lateinit var mcpToolBridge: McpToolBridge
    private lateinit var secureDataStore: SecureDataStore
    private lateinit var agentStore: com.aura.agent.AgentStore
    private lateinit var conversationStore: com.aura.agent.ConversationStore

    @Before
    fun setUp() {
        // Context mock is required by the constructor but never
        // exercised through applyGates — relaxed so unused methods
        // don't blow up with AbstractMethodError.
        context = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        memoryStore = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)
        evolutionScheduler = mockk(relaxed = true)
        mcpClientManager = mockk(relaxed = true)
        mcpToolBridge = mockk(relaxed = true)
        secureDataStore = mockk(relaxed = true)
        agentStore = mockk(relaxed = true)
        conversationStore = mockk(relaxed = true)
        coEvery { memoryStore.runDecayPass() } returns Unit

        // Default: morning brief on, calendar monitor on, evolution off.
        // Tests override the flow per-case.
        every { userPreferences.morningBriefEnabled } returns flowOf(true)
        every { userPreferences.calendarMonitorEnabled } returns flowOf(true)
        every { userPreferences.morningBriefHour } returns flowOf(7)
        every { userPreferences.evolutionEnabled } returns flowOf(false)
        every { userPreferences.evolutionIntervalHours } returns flowOf(24)
        every { userPreferences.decayEnabled } returns flowOf(true)
        every { userPreferences.mcpServersJson } returns flowOf("")
    }

    @Test
    fun `morning brief on schedules morning brief only`() {
        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        bootstrap.applyGates(morningBriefOn = true, calendarMonitorOn = true)
        verify(exactly = 1) { scheduler.scheduleMorningBrief() }
        verify(exactly = 0) { scheduler.cancelMorningBrief() }
    }

    @Test
    fun `morning brief off cancels morning brief only`() {
        every { userPreferences.morningBriefEnabled } returns flowOf(false)
        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        bootstrap.applyGates(morningBriefOn = false, calendarMonitorOn = false)
        verify(exactly = 0) { scheduler.scheduleMorningBrief() }
        verify(exactly = 1) { scheduler.cancelMorningBrief() }
    }

    @Test
    fun `calendar gate on schedules the calendar check worker`() {
        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        bootstrap.applyGates(morningBriefOn = true, calendarMonitorOn = true)
        verify(exactly = 1) { scheduler.scheduleCalendarChecks() }
        verify(exactly = 0) { scheduler.cancelCalendarChecks() }
    }

    @Test
    fun `calendar gate off cancels the calendar check worker`() {
        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        bootstrap.applyGates(morningBriefOn = true, calendarMonitorOn = false)
        verify(exactly = 0) { scheduler.scheduleCalendarChecks() }
        verify(exactly = 1) { scheduler.cancelCalendarChecks() }
    }

    @Test
    fun `applyGates returns the morning brief decision`() {
        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        val decisions = bootstrap.applyGates(morningBriefOn = true, calendarMonitorOn = true)
        assertTrue(decisions.morningBriefScheduled)
        assertTrue(decisions.calendarMonitorShouldRun)
    }

    @Test
    fun `applyGates returns false when morning brief is off`() {
        every { userPreferences.morningBriefEnabled } returns flowOf(false)
        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        val decisions = bootstrap.applyGates(morningBriefOn = false, calendarMonitorOn = true)
        assertFalse(decisions.morningBriefScheduled)
        assertTrue(decisions.calendarMonitorShouldRun, "calendar monitor is independent of morning brief")
    }

    @Test
    fun `applyGates returns false for calendar monitor when off`() {
        every { userPreferences.calendarMonitorEnabled } returns flowOf(false)
        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        val decisions = bootstrap.applyGates(morningBriefOn = true, calendarMonitorOn = false)
        assertTrue(decisions.morningBriefScheduled, "morning brief is independent of calendar monitor")
        assertFalse(decisions.calendarMonitorShouldRun)
    }

    @Test
    fun `start reads both prefs and applies the gates`() = runTest {
        // End-to-end via start(), but with a fully-stubbed Context
        // so the broadcast / FGS calls don't trip AbstractMethodError.
        // The Throwable-catch in start() absorbs any stub blowups,
        // so the gate decision itself is what we verify. All bootstrap
        // coroutines run on the test scheduler (backgroundScope), so
        // runCurrent() is deterministic — no real-time polling.
        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        bootstrap.start(scope = backgroundScope)
        runCurrent()
        verify(exactly = 1) { scheduler.scheduleMorningBrief() }
    }

    @Test
    fun `start reacts to schedule preference changes without process restart`() = runTest {
        val morningEnabled = MutableStateFlow(true)
        val calendarEnabled = MutableStateFlow(true)
        val briefHour = MutableStateFlow(7)
        every { userPreferences.morningBriefEnabled } returns morningEnabled
        every { userPreferences.calendarMonitorEnabled } returns calendarEnabled
        every { userPreferences.morningBriefHour } returns briefHour

        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        bootstrap.start(scope = backgroundScope)
        runCurrent()
        verify(atLeast = 1) { scheduler.scheduleMorningBrief(7) }

        briefHour.value = 9
        runCurrent()
        verify(atLeast = 1) { scheduler.scheduleMorningBrief(9) }

        morningEnabled.value = false
        runCurrent()
        verify(atLeast = 1) { scheduler.cancelMorningBrief() }
    }

    @Test
    fun `evolution enabled schedules evolution worker`() = runTest {
        every { userPreferences.evolutionEnabled } returns flowOf(true)
        every { userPreferences.evolutionIntervalHours } returns flowOf(12)
        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        bootstrap.start(scope = backgroundScope)
        runCurrent()
        verify(atLeast = 1) { evolutionScheduler.schedule(12L) }
    }

    @Test
    fun `evolution disabled cancels evolution worker`() = runTest {
        every { userPreferences.evolutionEnabled } returns flowOf(false)
        val bootstrap = ProactiveBootstrap(context, scheduler, memoryStore, userPreferences, evolutionScheduler, mcpClientManager, mcpToolBridge, secureDataStore, null, null, agentStore, conversationStore)
        bootstrap.start(scope = backgroundScope)
        runCurrent()
        verify(atLeast = 1) { evolutionScheduler.cancel() }
    }
}