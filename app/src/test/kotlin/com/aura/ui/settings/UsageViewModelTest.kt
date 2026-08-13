package com.aura.ui.settings

import com.aura.usage.UsageSnapshot
import com.aura.usage.UsageTracker
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for [UsageViewModel] — verifies it exposes the tracker
 * snapshot and delegates reset.
 */
class UsageViewModelTest {

    @Test
    fun `usage exposes tracker snapshot`() {
        val snapshot = UsageSnapshot(calls = 5, toolResultChars = 10)
        val tracker = mockk<UsageTracker>(relaxed = true)
        every { tracker.snapshot } returns MutableStateFlow(snapshot)

        val vm = UsageViewModel(tracker, com.aura.usage.BackgroundBudget { System.currentTimeMillis() })

        assertEquals(5, vm.usage.value.calls)
        assertEquals(10, vm.usage.value.toolResultChars)
    }

    @Test
    fun `reset delegates to tracker`() {
        val tracker = mockk<UsageTracker>(relaxed = true)
        every { tracker.snapshot } returns MutableStateFlow(UsageSnapshot())
        every { tracker.reset() } just runs

        val vm = UsageViewModel(tracker, com.aura.usage.BackgroundBudget { System.currentTimeMillis() })
        vm.reset()

        verify { tracker.reset() }
    }
}