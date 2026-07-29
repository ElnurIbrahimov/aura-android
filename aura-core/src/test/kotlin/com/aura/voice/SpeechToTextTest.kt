package com.aura.voice

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechToTextTest {

    @Test
    fun `initial state is Idle`() {
        val context = mockk<Context>(relaxed = true)
        val stt = SpeechToText(context)
        assertTrue(stt.state.value is SpeechToText.State.Idle)
    }

    @Test
    fun `state flow is hot and starts with Idle`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val stt = SpeechToText(context)
        assertEquals(SpeechToText.State.Idle, stt.state.first())
    }

    @Test
    fun `partialTranscript is empty initially`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val stt = SpeechToText(context)
        assertEquals("", stt.partialTranscript.first())
    }
}
