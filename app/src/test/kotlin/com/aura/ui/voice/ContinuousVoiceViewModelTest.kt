package com.aura.ui.voice

import android.app.Application
import com.aura.voice.SpeechToText
import com.aura.voice.TextToSpeech
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
import kotlin.test.assertFalse

/**
 * Tests for [ContinuousVoiceViewModel] — verifies start/stop and
 * setLastResponse. Does NOT test the state machine transitions that
 * require the init collector (which runs forever in tests).
 */
class ContinuousVoiceViewModelTest {

    private val app = mockk<Application>(relaxed = true)
    private val stt = mockk<SpeechToText>(relaxed = true)
    private val tts = mockk<TextToSpeech>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { stt.state } returns MutableStateFlow(SpeechToText.State.Idle)
        every { tts.state } returns MutableStateFlow(TextToSpeech.State.Idle)
        every { stt.start() } just runs
        every { stt.cancel() } just runs
        every { tts.stop() } just runs
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(): ContinuousVoiceViewModel =
        ContinuousVoiceViewModel(app, stt, tts)

    @Test
    fun `initial state is idle`() {
        val vm = makeVm()

        assertFalse(vm.state.value.active)
        assertEquals(VoiceModeState.IDLE, vm.state.value.phase)
    }

    @Test
    fun `startLoop sets active and listening`() {
        val vm = makeVm()

        vm.startLoop(onSend = {}, onStreamingDone = { true })

        assertEquals(VoiceModeState.LISTENING, vm.state.value.phase)
    }

    @Test
    fun `stopLoop sets inactive and idle`() {
        val vm = makeVm()
        vm.startLoop(onSend = {}, onStreamingDone = { true })

        vm.stopLoop()

        assertFalse(vm.state.value.active)
        assertEquals(VoiceModeState.IDLE, vm.state.value.phase)
        verify { stt.cancel() }
        verify { tts.stop() }
    }

    @Test
    fun `setLastResponse updates state`() {
        val vm = makeVm()

        vm.setLastResponse("Hello world")

        assertEquals("Hello world", vm.state.value.lastResponse)
    }
}