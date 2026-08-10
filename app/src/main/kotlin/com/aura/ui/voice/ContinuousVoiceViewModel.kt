package com.aura.ui.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.voice.SpeechToText
import com.aura.voice.TextToSpeech
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State machine for continuous (hands-free) voice mode.
 *
 * LISTENING → user speaks → STT FinalResult → send() → THINKING
 * THINKING → agent Done → TTS speaks response → SPEAKING
 * SPEAKING → TTS done → LISTENING (loop)
 * SPEAKING + user barges in → THINKING (interrupt)
 *
 * Exit conditions:
 * - User taps "Stop"
 * - STT timeout (no speech for 10s in LISTENING state)
 * - User says "stop listening"
 */
enum class VoiceModeState { IDLE, LISTENING, THINKING, SPEAKING }

data class ContinuousVoiceState(
    val active: Boolean = false,
    val phase: VoiceModeState = VoiceModeState.IDLE,
    val partialTranscript: String = "",
    val lastResponse: String = "",
    val error: String? = null,
)

@HiltViewModel
class ContinuousVoiceViewModel @Inject constructor(
    application: Application,
    private val speechToText: SpeechToText,
    private val textToSpeech: TextToSpeech,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ContinuousVoiceState())
    val state: StateFlow<ContinuousVoiceState> = _state.asStateFlow()

    private var silenceTimer: Job? = null

    // ── Single-owner collector jobs ──────────────────────────
    // Only one STT collector and one TTS collector may be active at
    // a time. Each cycle cancels the previous job before starting a
    // new one so stale collectors cannot fire duplicate callbacks.
    private var sttCollectorJob: Job? = null
    private var ttsCollectorJob: Job? = null
    private var responseWaitJob: Job? = null
    /** Barge-in collector — cancelled alongside the others in stopLoop(). */
    private var bargeInJob: Job? = null

    /**
     * Start the voice loop. The caller (ChatScreen) provides callbacks
     * for sending a message and listening for completion. The loop
     * runs in viewModelScope and exits when the user taps Stop or
     * the silence timer fires.
     */
    fun startLoop(
        onSend: (String) -> Unit,
        onStreamingDone: () -> Boolean,
    ) {
        _state.value = ContinuousVoiceState(active = true, phase = VoiceModeState.LISTENING)
        startListening(onSend, onStreamingDone)
    }

    fun stopLoop() {
        silenceTimer?.cancel()
        sttCollectorJob?.cancel()
        ttsCollectorJob?.cancel()
        responseWaitJob?.cancel()
        bargeInJob?.cancel()
        speechToText.cancel()
        textToSpeech.stop()
        _state.update { it.copy(active = false, phase = VoiceModeState.IDLE) }
    }

    private fun startListening(
        onSend: (String) -> Unit,
        onStreamingDone: () -> Boolean,
    ) {
        // Cancel any previous STT collector so only one exists at a time.
        sttCollectorJob?.cancel()
        responseWaitJob?.cancel()

        _state.update { it.copy(phase = VoiceModeState.LISTENING, partialTranscript = "") }
        speechToText.start()

        // Silence timeout: if no speech detected in 10s, stop the loop.
        silenceTimer?.cancel()
        silenceTimer = viewModelScope.launch {
            delay(SILENCE_TIMEOUT_MS)
            if (_state.value.phase == VoiceModeState.LISTENING) {
                stopLoop()
            }
        }

        // Watch STT state for final results — single collector.
        sttCollectorJob = viewModelScope.launch {
            speechToText.state.collect { sttState ->
                when (sttState) {
                    is SpeechToText.State.PartialResult -> {
                        silenceTimer?.cancel()
                        _state.update { it.copy(partialTranscript = sttState.text) }
                    }
                    is SpeechToText.State.FinalResult -> {
                        silenceTimer?.cancel()
                        val text = sttState.text.trim()
                        if (text.isBlank()) {
                            // Empty result — restart listening
                            if (_state.value.active) startListening(onSend, onStreamingDone)
                            return@collect
                        }
                        // Check for stop command
                        if (text.lowercase() in STOP_PHRASES) {
                            stopLoop()
                            return@collect
                        }
                        // Send the message
                        _state.update { it.copy(phase = VoiceModeState.THINKING, partialTranscript = text) }
                        onSend(text)
                        // Wait for streaming to complete, then speak
                        responseWaitJob = viewModelScope.launch responseWait@{
                            // Speak each sentence AS IT COMPLETES rather than
                            // waiting for the whole reply.
                            //
                            // The old loop polled `onStreamingDone()` and said
                            // nothing until it returned true, so every reply
                            // began with one to three seconds of silence after
                            // the user stopped talking. That silence was the
                            // largest contributor to voice mode feeling slow,
                            // and none of it was model latency.
                            //
                            // Still polling, at a shorter interval, because the
                            // response arrives through a StateFlow this VM only
                            // reads — making it event-driven means threading a
                            // token callback through ChatSendController, which
                            // is a larger change than the win justifies. The
                            // poll now looks at the TEXT rather than only at the
                            // done flag, which is where the latency was.
                            chunker.reset()
                            var spokeAnything = false
                            while (_state.value.active) {
                                val done = onStreamingDone()
                                val text = _state.value.lastResponse
                                chunker.accept(text).forEach { sentence ->
                                    speakSentence(sentence, flush = !spokeAnything)
                                    spokeAnything = true
                                }
                                if (done) {
                                    chunker.flush(text).takeIf { it.isNotBlank() }?.let {
                                        speakSentence(it, flush = !spokeAnything)
                                        spokeAnything = true
                                    }
                                    break
                                }
                                delay(STREAM_POLL_MS)
                            }
                            if (!_state.value.active) return@responseWait
                            // Hand over to the barge-in listener. Nothing is
                            // re-spoken: speakResponse now only sets up
                            // listening when sentences already went out.
                            speakResponse(onSend, onStreamingDone, alreadySpoken = spokeAnything)
                        }
                    }
                    is SpeechToText.State.Error -> {
                        silenceTimer?.cancel()
                        if (_state.value.active) {
                            _state.update { it.copy(error = sttState.message) }
                            // Retry listening after a brief pause
                            viewModelScope.launch {
                                delay(1000)
                                if (_state.value.active) startListening(onSend, onStreamingDone)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Queue one sentence for speech.
     *
     * `flush = true` only on the FIRST sentence of a reply. Flushing on every
     * one would cut off the sentence currently being spoken, which is the exact
     * stutter this change exists to avoid.
     */
    private fun speakSentence(sentence: String, flush: Boolean) {
        textToSpeech.speak(
            text = sentence,
            utteranceId = "voice-mode-${System.currentTimeMillis()}-${sentence.hashCode()}",
            flush = flush,
        )
    }

    private fun speakResponse(
        onSend: (String) -> Unit,
        onStreamingDone: () -> Boolean,
        alreadySpoken: Boolean = false,
    ) {
        // Cancel previous collectors — including the LISTENING STT
        // collector — so only the barge-in collector (assigned below)
        // watches STT during SPEAKING. Single-owner discipline prevents
        // duplicate onSend() from two live collectors on the same StateFlow.
        sttCollectorJob?.cancel()
        ttsCollectorJob?.cancel()
        bargeInJob?.cancel()

        _state.update { it.copy(phase = VoiceModeState.SPEAKING) }
        val response = _state.value.lastResponse
        if (response.isBlank()) {
            startListening(onSend, onStreamingDone)
            return
        }
        // Only speak here when the streaming path did not already. Speaking
        // again would repeat the whole reply on top of itself.
        if (!alreadySpoken) {
            textToSpeech.speak(
                text = response,
                utteranceId = "voice-mode-${System.currentTimeMillis()}",
                flush = true,
            )
        }
        // Barge-in: keep STT running during SPEAKING so the user can
        // interrupt the assistant mid-response. When PartialResult fires
        // with enough text (> 2 chars), cancel TTS and treat it as a
        // new user turn. Word-count guard raises the bar above the
        // assistant's own voice bleeding into the mic, and STOP_PHRASES
        // are checked here so the user can exit voice mode mid-reply.
        speechToText.start()
        bargeInJob = viewModelScope.launch {
            speechToText.state.collect { sttState ->
                if (_state.value.phase != VoiceModeState.SPEAKING) return@collect
                when (sttState) {
                    is SpeechToText.State.PartialResult -> {
                        val text = sttState.text.trim()
                        if (text.split(" ").size > 1 && text.length > 3) {
                            handleBargeIn(text, onSend, onStreamingDone)
                        }
                    }
                    is SpeechToText.State.FinalResult -> {
                        val text = sttState.text.trim()
                        if (text.isNotBlank()) {
                            // Check stop commands here — fixing onSend("stop")
                            // the LISTENING collector would have caught but the
                            // barge-in path must handle independently.
                            if (text.lowercase() in STOP_PHRASES) {
                                stopLoop()
                                return@collect
                            }
                            handleBargeIn(text, onSend, onStreamingDone)
                        }
                    }
                    else -> Unit
                }
            }
        }
        // Watch TTS state — when it goes back to Ready, the utterance
        // is done and we can resume listening. Single collector.
        ttsCollectorJob = viewModelScope.launch {
            textToSpeech.state.collect { ttsState ->
                if (ttsState is TextToSpeech.State.Ready && _state.value.phase == VoiceModeState.SPEAKING) {
                    bargeInJob?.cancel()
                    speechToText.cancel()
                    if (_state.value.active) {
                        startListening(onSend, onStreamingDone)
                    }
                    return@collect
                }
            }
        }
    }

    /**
     * Commit the barge-in: set phase to THINKING BEFORE stopping TTS
     * so the TTS collector's `phase == SPEAKING` guard can't win the
     * race and reset to LISTENING, silently discarding the response.
     */
    private fun handleBargeIn(
        text: String,
        onSend: (String) -> Unit,
        onStreamingDone: () -> Boolean,
    ) {
        _state.update { it.copy(phase = VoiceModeState.THINKING, partialTranscript = text) }
        textToSpeech.stop()
        bargeInJob?.cancel()
        speechToText.cancel()
        onSend(text)
        responseWaitJob = viewModelScope.launch responseWait@{
            while (_state.value.active && !onStreamingDone()) {
                delay(200)
            }
            if (!_state.value.active) return@responseWait
            speakResponse(onSend, onStreamingDone)
        }
    }

    /**
     * Called by the ChatScreen when the streaming response is complete
     * and the full assistant text is available for TTS.
     */
    fun setLastResponse(text: String) {
        _state.update { it.copy(lastResponse = text) }
    }

    override fun onCleared() {
        stopLoop()
        super.onCleared()
    }

    /** Splits the streaming reply into speakable sentences. Not thread-safe; only the response-wait coroutine touches it. */
    private val chunker = com.aura.voice.SentenceChunker()

    companion object {
        /**
         * How often the response-wait loop looks for a newly complete sentence.
         *
         * Shorter than the old 200ms because it now gates the START of speech
         * rather than only its end — at 200ms a reply whose first sentence lands
         * quickly still waits a fifth of a second for nothing.
         */
        private const val STREAM_POLL_MS = 60L

        private const val SILENCE_TIMEOUT_MS = 10_000L
        private val STOP_PHRASES = setOf("stop listening", "stop", "exit voice mode", "that's all")
    }
}
