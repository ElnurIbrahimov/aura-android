package com.aura.ui.viewmodel

import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [SkillsViewModel] — verifies add/update/remove
 * operations and selection state.
 */
class SkillsViewModelTest {

    private fun makeStore(skills: List<Skill> = emptyList()): SkillsStore {
        val store = mockk<SkillsStore>(relaxed = true)
        every { store.skills } returns MutableStateFlow(skills)
        coEvery { store.awaitLoaded() } returns Unit
        return store
    }

    @Before
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `add with valid name calls store add`() = runTest {
        val store = makeStore()
        val vm = SkillsViewModel(store)

        vm.add("My Skill", "description", "body text")

        coVerify { store.add(any()) }
    }

    @Test
    fun `add with blank name does nothing`() = runTest {
        val store = makeStore()
        val vm = SkillsViewModel(store)

        vm.add("   ", "desc", "body")

        coVerify(exactly = 0) { store.add(any()) }
    }

    @Test
    fun `add trims name before storing`() = runTest {
        val store = makeStore()
        val skillSlot = slot<Skill>()
        coEvery { store.add(capture(skillSlot)) } returns Unit
        val vm = SkillsViewModel(store)

        vm.add("  My Skill  ", "desc", "body")

        assertEquals("My Skill", skillSlot.captured.name)
    }

    @Test
    fun `remove clears selection if removed skill was selected`() = runTest {
        val store = makeStore()
        val vm = SkillsViewModel(store)

        vm.select("skill-1")
        assertEquals("skill-1", vm.selectedId.value)

        vm.remove("skill-1")

        assertNull(vm.selectedId.value)
    }

    @Test
    fun `remove does not clear selection if different skill removed`() = runTest {
        val store = makeStore()
        val vm = SkillsViewModel(store)

        vm.select("skill-1")
        vm.remove("skill-2")

        assertEquals("skill-1", vm.selectedId.value)
    }

    @Test
    fun `select null clears selection`() {
        val store = makeStore()
        val vm = SkillsViewModel(store)

        vm.select("skill-1")
        vm.select(null)

        assertNull(vm.selectedId.value)
    }

    @Test
    fun `skills flow mirrors store`() {
        val testSkills = listOf(
            Skill(name = "A", description = "desc", body = "body"),
            Skill(name = "B", description = "desc", body = "body"),
        )
        val store = makeStore(testSkills)
        val vm = SkillsViewModel(store)

        assertEquals(2, vm.skills.value.size)
        assertEquals("A", vm.skills.value[0].name)
    }
}