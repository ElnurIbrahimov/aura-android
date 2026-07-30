package com.aura.consciousness

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AffinityTrackerTest {

    private lateinit var tracker: AffinityTracker

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        tracker = AffinityTracker(context)
    }

    @After
    fun tearDown() = runTest {
        // No clear method — DataStore persists across tests.
        // Each test uses fresh keys via recordTurn increments.
    }

    @Test
    fun `initial load returns a valid affinity level`() = runTest {
        val state = tracker.load()
        // DataStore may have residual state from prior tests in the same JVM.
        // Just verify the level is a valid enum value and score is in range.
        assertTrue("Score should be in range 0-100: ${state.score}", state.score in 0f..100f)
        assertTrue("Level should be a valid enum: ${state.level}", state.level in AffinityTracker.AffinityLevel.entries)
    }

    @Test
    fun `recordTurn increases score`() = runTest {
        val before = tracker.load().score
        tracker.recordTurn()
        val after = tracker.load().score
        assertTrue("Score should increase: before=$before after=$after", after > before)
    }

    @Test
    fun `score_increases_by_0_5_per_turn`() = runTest {
        tracker.recordTurn()
        val first = tracker.load().score
        tracker.recordTurn()
        val second = tracker.load().score
        assertEquals(0.5f, second - first, 0.01f)
    }

    @Test
    fun `level transitions from Acquaintance to Familiar after enough turns`() = runTest {
        // 11 / 0.5 = 22 turns needed
        repeat(22) { tracker.recordTurn() }
        val state = tracker.load()
        assertTrue("Expected Familiar or higher, got ${state.level}", state.level.ordinal >= AffinityTracker.AffinityLevel.FAMILIAR.ordinal)
    }

    @Test
    fun `getDirective returns non-empty string`() = runTest {
        val directive = tracker.getDirective()
        assertTrue("Directive should not be blank", directive.isNotBlank())
        assertTrue("Directive should contain relationship guidance", directive.contains("user", ignoreCase = true))
    }

    @Test
    fun `AffinityLevel fromScore maps correctly`() {
        assertEquals(AffinityTracker.AffinityLevel.ACQUAINTANCE, AffinityTracker.AffinityLevel.fromScore(0f))
        assertEquals(AffinityTracker.AffinityLevel.ACQUAINTANCE, AffinityTracker.AffinityLevel.fromScore(10f))
        assertEquals(AffinityTracker.AffinityLevel.FAMILIAR, AffinityTracker.AffinityLevel.fromScore(15f))
        assertEquals(AffinityTracker.AffinityLevel.CONNECTED, AffinityTracker.AffinityLevel.fromScore(30f))
        assertEquals(AffinityTracker.AffinityLevel.TRUSTED, AffinityTracker.AffinityLevel.fromScore(60f))
        assertEquals(AffinityTracker.AffinityLevel.CLOSE, AffinityTracker.AffinityLevel.fromScore(80f))
        assertEquals(AffinityTracker.AffinityLevel.CLOSE, AffinityTracker.AffinityLevel.fromScore(100f))
    }

    @Test
    fun `each level has a unique directive`() {
        val directives = AffinityTracker.AffinityLevel.entries.map { it.directive }.toSet()
        assertEquals("Each level should have a unique directive",
            AffinityTracker.AffinityLevel.entries.size, directives.size)
    }
}