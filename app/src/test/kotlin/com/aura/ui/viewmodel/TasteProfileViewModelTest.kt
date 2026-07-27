package com.aura.ui.viewmodel

import com.aura.taste.PreferenceSignalDao
import com.aura.taste.StyleProfileDao
import com.aura.taste.TasteEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TasteProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val signalDao = mockk<PreferenceSignalDao>(relaxed = true)
    private val profileDao = mockk<StyleProfileDao>(relaxed = true)
    private val tasteEngine = mockk<TasteEngine>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { signalDao.forScopes(any(), any()) } returns emptyList()
        coEvery { profileDao.forScopes(any()) } returns null
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(): TasteProfileViewModel = TasteProfileViewModel(signalDao, profileDao, tasteEngine)

    @Test
    fun `clear all signals delegates to engine`() = runTest(dispatcher) {
        vm().clearAllSignals()
        advanceUntilIdle()
        coVerify { tasteEngine.clearSignals("") }
    }

    @Test
    fun `recompute triggers engine`() = runTest(dispatcher) {
        vm().recompute()
        advanceUntilIdle()
        coVerify { tasteEngine.recomputeProfile("") }
    }
}
