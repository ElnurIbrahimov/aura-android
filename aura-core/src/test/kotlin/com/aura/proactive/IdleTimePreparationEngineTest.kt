package com.aura.proactive

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Idle-time preparation delivery contract.
 *
 * The engine writes a PreparedAnswer to `prepared`; ChatViewModel
 * surfaces `predictedQuestion` as a chip and `consume()` returns the
 * full answer exactly once (fast-path context for sendPrepared()).
 * This test pins the consume-once semantics that the UI depends on.
 */
class IdleTimePreparationEngineTest {

    @Test
    fun `consume returns the prepared answer exactly once`() = runTest {
        val engine = IdleTimePreparationEngine(
            providerRegistry = mockk(relaxed = true),
            userPreferences = mockk(relaxed = true),
            conversationStore = mockk(relaxed = true),
            memoryStore = mockk(relaxed = true),
            taskDao = mockk(relaxed = true),
        )
        val answer = IdleTimePreparationEngine.PreparedAnswer(
            predictedQuestion = "What's my next meeting?",
            answer = "Your next meeting is at 14:00 with the design team.",
            confidence = 0.7f,
            createdAt = System.currentTimeMillis(),
        )
        engine.setForTest(answer)

        val first = engine.consume()
        assertNotNull(first, "consume() should return the prepared answer once")
        assertEquals("What's my next meeting?", first!!.predictedQuestion)
        assertEquals(
            "Your next meeting is at 14:00 with the design team.",
            first.answer,
        )

        val second = engine.consume()
        assertNull(second, "consume() must be one-shot — a second call returns null")
    }

    @Test
    fun `prepared flow emits the predicted question for the UI chip`() = runTest {
        val engine = IdleTimePreparationEngine(
            providerRegistry = mockk(relaxed = true),
            userPreferences = mockk(relaxed = true),
            conversationStore = mockk(relaxed = true),
            memoryStore = mockk(relaxed = true),
            taskDao = mockk(relaxed = true),
        )
        val answer = IdleTimePreparationEngine.PreparedAnswer(
            predictedQuestion = "Summarize my day",
            answer = "You had 3 meetings and finished the report.",
            confidence = 0.6f,
            createdAt = System.currentTimeMillis(),
        )
        engine.setForTest(answer)

        val emitted = engine.prepared.first()
        assertNotNull(emitted)
        assertEquals("Summarize my day", emitted!!.predictedQuestion)
    }
}
