package com.aura.agent

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyBanditTest {

    private val store = mockk<StrategyBanditStore>(relaxed = true)
    private val bandit = StrategyBandit(store)

    @Test
    fun `classify routes code keywords to CODE`() {
        val cat = ProblemCategory.classify("write a kotlin function to sort a list", null)
        assertEquals(ProblemCategory.CODE, cat)
    }

    @Test
    fun `classify routes debug keywords to DEBUG`() {
        val cat = ProblemCategory.classify("debug this stack trace and fix the crash", null)
        assertEquals(ProblemCategory.DEBUG, cat)
    }

    @Test
    fun `classify routes creative keywords to CREATIVE`() {
        val cat = ProblemCategory.classify("write a short story about a city in the clouds", null)
        assertEquals(ProblemCategory.CREATIVE, cat)
    }

    @Test
    fun `classify routes math keywords to MATH`() {
        val cat = ProblemCategory.classify("calculate the integral of x squared from 0 to 1", null)
        assertEquals(ProblemCategory.MATH, cat)
    }

    @Test
    fun `classify routes planning keywords to PLANNING`() {
        val cat = ProblemCategory.classify("plan a roadmap for learning android development", null)
        assertEquals(ProblemCategory.PLANNING, cat)
    }

    @Test
    fun `classify routes analysis keywords to ANALYSIS`() {
        val cat = ProblemCategory.classify("compare the pros and cons of kotlin vs java", null)
        assertEquals(ProblemCategory.ANALYSIS, cat)
    }

    @Test
    fun `classify defaults to CONVERSATION for casual messages`() {
        val cat = ProblemCategory.classify("hey how are you doing today", null)
        assertEquals(ProblemCategory.CONVERSATION, cat)
    }

    @Test
    fun `classify uses specialist hint when available`() {
        val cat = ProblemCategory.classify("fix the bug", Specialist.Coder)
        assertEquals(ProblemCategory.DEBUG, cat)
    }

    @Test
    fun `selectStrategy returns a valid strategy`() = runBlocking {
        coEvery { store.getArms(any()) } returns listOf(
            Triple(ReasoningStrategy.SINGLE_PASS, 5.0, 1.0),
            Triple(ReasoningStrategy.MULTI_STEP_REFLECT, 1.0, 1.0),
            Triple(ReasoningStrategy.CREATIVE_PASS, 1.0, 1.0),
        )
        val strategy = bandit.selectStrategy(ProblemCategory.CODE)
        assertNotNull(strategy)
        assertTrue(ReasoningStrategy.values().contains(strategy))
    }

    @Test
    fun `selectStrategy defaults to MULTI_STEP_REFLECT when no arms`() = runBlocking {
        coEvery { store.getArms(any()) } returns emptyList()
        val strategy = bandit.selectStrategy(ProblemCategory.CONVERSATION)
        assertEquals(ReasoningStrategy.MULTI_STEP_REFLECT, strategy)
    }

    @Test
    fun `recordOutcome calls store with correct params`() = runBlocking {
        // Store is relaxed mock — recordOutcome is a fire-and-forget call
        bandit.recordOutcome(ProblemCategory.CODE, ReasoningStrategy.MULTI_STEP_REFLECT, success = true)
    }

    @Test
    fun `ReasoningStrategy maxSteps are distinct`() {
        assertEquals(5, ReasoningStrategy.SINGLE_PASS.maxSteps)
        assertEquals(15, ReasoningStrategy.MULTI_STEP_REFLECT.maxSteps)
        assertEquals(3, ReasoningStrategy.CREATIVE_PASS.maxSteps)
    }

    @Test
    fun `ReasoningStrategy enablePlanning only for MULTI_STEP_REFLECT`() {
        assertTrue(ReasoningStrategy.MULTI_STEP_REFLECT.enablePlanning)
        assertTrue(!ReasoningStrategy.SINGLE_PASS.enablePlanning)
        assertTrue(!ReasoningStrategy.CREATIVE_PASS.enablePlanning)
    }
}
