package com.aura.proactive

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotivationAccumulatorTest {

    private val proactiveInteractionDao = mockk<ProactiveInteractionDao>(relaxed = true)
    private val userPreferences = mockk<com.aura.data.UserPreferences>(relaxed = true)
    private val accumulator = MotivationAccumulator(proactiveInteractionDao, userPreferences)

    @Test
    fun `score returns weighted sum of 5 factors`() {
        val msg = MotivationAccumulator.PotentialMessage(
            content = "test",
            source = "test",
            relevanceToUser = 1.0f,
            timeSinceSimilar = 0.0f,
            emotionalUrgency = 1.0f,
            curiosityDrive = 0.0f,
            userReceptivity = 0.0f,
        )
        // 1.0*0.30 + 0.0*0.20 + 1.0*0.20 + 0.0*0.15 + 0.0*0.15 = 0.50
        val score = accumulator.score(msg)
        assertEquals(0.50f, score, 0.001f)
    }

    @Test
    fun `score with all factors at 1 returns 1`() {
        val msg = MotivationAccumulator.PotentialMessage(
            content = "test",
            source = "test",
            relevanceToUser = 1.0f,
            timeSinceSimilar = 1.0f,
            emotionalUrgency = 1.0f,
            curiosityDrive = 1.0f,
            userReceptivity = 1.0f,
        )
        assertEquals(1.0f, accumulator.score(msg), 0.001f)
    }

    @Test
    fun `score with all factors at 0 returns 0`() {
        val msg = MotivationAccumulator.PotentialMessage(
            content = "test",
            source = "test",
            relevanceToUser = 0.0f,
            timeSinceSimilar = 0.0f,
            emotionalUrgency = 0.0f,
            curiosityDrive = 0.0f,
            userReceptivity = 0.0f,
        )
        assertEquals(0.0f, accumulator.score(msg), 0.001f)
    }

    @Test
    fun `threshold returns base when no interaction history`() = runBlocking {
        coEvery { proactiveInteractionDao.recent(20) } returns emptyList()
        assertEquals(0.5f, accumulator.currentThreshold(), 0.001f)
    }

    @Test
    fun `threshold lowers when engagement is high`() = runBlocking {
        val interactions = listOf(
            ProactiveInteractionEntity(eventId = 0L, action = "tapped", timestamp = 1000L),
            ProactiveInteractionEntity(eventId = 0L, action = "acted", timestamp = 2000L),
            ProactiveInteractionEntity(eventId = 0L, action = "tapped", timestamp = 3000L),
            ProactiveInteractionEntity(eventId = 0L, action = "tapped", timestamp = 4000L),
        )
        coEvery { proactiveInteractionDao.recent(20) } returns interactions
        // engagementRatio = 4/4 = 1.0, dismissalRatio = 0
        // threshold = 0.5 - 1.0*0.2 + 0 = 0.3
        assertEquals(0.3f, accumulator.currentThreshold(), 0.001f)
    }

    @Test
    fun `threshold raises when dismissal is high`() = runBlocking {
        val interactions = listOf(
            ProactiveInteractionEntity(eventId = 0L, action = "dismissed", timestamp = 1000L),
            ProactiveInteractionEntity(eventId = 0L, action = "snoozed", timestamp = 2000L),
            ProactiveInteractionEntity(eventId = 0L, action = "dismissed", timestamp = 3000L),
            ProactiveInteractionEntity(eventId = 0L, action = "dismissed", timestamp = 4000L),
        )
        coEvery { proactiveInteractionDao.recent(20) } returns interactions
        // engagementRatio = 0, dismissalRatio = 4/4 = 1.0
        // threshold = 0.5 + 1.0*0.2 = 0.7
        assertEquals(0.7f, accumulator.currentThreshold(), 0.001f)
    }

    @Test
    fun `shouldDeliver is true when score exceeds threshold`() = runBlocking {
        coEvery { proactiveInteractionDao.recent(20) } returns emptyList()
        val msg = MotivationAccumulator.PotentialMessage(
            content = "test",
            source = "test",
            relevanceToUser = 0.8f,
            timeSinceSimilar = 0.8f,
            emotionalUrgency = 0.5f,
            curiosityDrive = 0.5f,
            userReceptivity = 0.5f,
        )
        // score = 0.8*0.3 + 0.8*0.2 + 0.5*0.2 + 0.5*0.15 + 0.5*0.15 = 0.64
        // threshold = 0.5
        val result = accumulator.evaluate(msg)
        assertTrue(result.shouldDeliver)
        assertTrue(result.score > result.threshold)
    }

    @Test
    fun `shouldDeliver is false when score below threshold`() = runBlocking {
        val interactions = listOf(
            ProactiveInteractionEntity(eventId = 0L, action = "dismissed", timestamp = 1000L),
            ProactiveInteractionEntity(eventId = 0L, action = "dismissed", timestamp = 2000L),
            ProactiveInteractionEntity(eventId = 0L, action = "dismissed", timestamp = 3000L),
        )
        coEvery { proactiveInteractionDao.recent(20) } returns interactions
        // threshold = 0.5 + (3/3)*0.2 = 0.7
        val msg = MotivationAccumulator.PotentialMessage(
            content = "test",
            source = "test",
            relevanceToUser = 0.5f,
            timeSinceSimilar = 0.5f,
            emotionalUrgency = 0.5f,
            curiosityDrive = 0.5f,
            userReceptivity = 0.5f,
        )
        // score = 0.5*0.3 + 0.5*0.2 + 0.5*0.2 + 0.5*0.15 + 0.5*0.15 = 0.5
        val result = accumulator.evaluate(msg)
        assertFalse(result.shouldDeliver)
    }
}