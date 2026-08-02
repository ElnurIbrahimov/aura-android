package com.aura.ui.voice

import android.app.Application
import com.aura.voice.SpeechToText
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for [VoiceViewModel] — verifies start/stop/cancel/reset
 * and consumeTranscript. Does NOT test the init collector (which
 * runs forever in a test environment).
 */
class VoiceViewModelTest {

    private val app = mockk<Application>(relaxed = true)
    private val stt = mockk<SpeechToText>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { stt.state } returns MutableStateFlow(SpeechToText.State.Idle)
        every { stt.hasPermission() } returns true
        every { stt.start() } just runs
        every { stt.stop() } just runs
        every { stt.cancel() } just runs
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `start clears transcript and calls STT start`() {
        val vm = VoiceViewModel(app, stt)
        vm.start()

        assertEquals("", vm.lastTranscript.value)
        verify { stt.start() }
    }

    @Test
    fun `start without permission does nothing`() {
        every { stt.hasPermission() } returns false
        val vm = VoiceViewModel(app, stt)
        vm.start()

        verify(exactly = 0) { stt.start() }
    }

    @Test
    fun `stop calls STT stop`() {
        val vm = VoiceViewModel(app, stt)
        vm.stop()

        verify { stt.stop() }
    }

    @Test
    fun `cancel calls STT cancel and clears transcript`() {
        val vm = VoiceViewModel(app, stt)
        vm.cancel()

        verify { stt.cancel() }
        assertEquals("", vm.lastTranscript.value)
    }

    @Test
    fun `reset clears STT and transcript`() {
        val vm = VoiceViewModel(app, stt)
        vm.reset()

        verify { stt.cancel() }
        assertEquals("", vm.lastTranscript.value)
    }

    @Test
    fun `consumeTranscript returns empty when nothing was said`() {
        val vm = VoiceViewModel(app, stt)
        val result = vm.consumeTranscript()

        assertEquals("", result)
    }
}