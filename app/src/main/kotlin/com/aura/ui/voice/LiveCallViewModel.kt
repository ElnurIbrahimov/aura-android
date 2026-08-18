package com.aura.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.ToolContext
import com.aura.realtime.RealtimeAvailability
import com.aura.realtime.RealtimeCallController
import com.aura.realtime.RealtimeVoiceService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The seam between the live-call UI and [RealtimeCallController].
 *
 * This class is the thing that did not exist. `RealtimeCallController` (1,476
 * lines with ~60 tests), `RealtimeVoiceService`, `RealtimeAvailability` and
 * `LiveCallSheet` were all written, tested and documented, and **nothing
 * constructed any of them** — `LiveCallSheet` had no caller either, so the whole
 * stack was reachable only from its own test suite.
 * `ProjectSpineIsWiredTest` names it as "this repo's most expensive recurring
 * defect" and uses it as the standing example. It stayed the standing example.
 *
 * Deliberately thin: the orchestration is already in the controller, which is
 * where the tests are. All this owns is the availability read, the foreground
 * service's lifetime, and the fact that a call must be ended when the screen
 * goes away.
 */
@HiltViewModel
class LiveCallViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val availability: RealtimeAvailability,
    private val controller: RealtimeCallController,
) : ViewModel() {

    private val _availability =
        MutableStateFlow<RealtimeAvailability.Availability>(
            RealtimeAvailability.Availability.Unavailable("Checking…"),
        )
    val availabilityState: StateFlow<RealtimeAvailability.Availability> = _availability.asStateFlow()

    /** The live call's own state — phase, transcript, remaining budget. */
    val callState: StateFlow<RealtimeCallController.State> = controller.state

    /**
     * Resolve whether a call is possible for the model the chat is currently on.
     *
     * Read per open rather than cached: the answer depends on the chat's model
     * and on whether an OpenAI key exists, and both change while the app is
     * running.
     */
    fun refresh(chatModel: String) {
        viewModelScope.launch {
            _availability.value = runCatching { availability.forChatModel(chatModel) }
                .getOrElse {
                    RealtimeAvailability.Availability.Unavailable(
                        it.message ?: "Could not check live calling",
                    )
                }
        }
    }

    /**
     * Start a call on [model].
     *
     * The foreground service starts **first**. It is what keeps the process
     * alive with the microphone once the user leaves the screen, and a call
     * opened before it exists is a call the system may kill mid-sentence.
     *
     * `seedContext` is empty on purpose. A realtime session is a different model
     * with no access to this conversation's memory, and `LiveCallSheet` already
     * tells the user that before they tap — pretending otherwise here would make
     * the warning false rather than making the call better.
     */
    fun startCall(model: String, conversationId: String) {
        viewModelScope.launch {
            RealtimeVoiceService.start(appContext)
            runCatching {
                controller.start(
                    scope = viewModelScope,
                    model = model,
                    instructions = CALL_INSTRUCTIONS,
                    seedContext = "",
                    toolContext = ToolContext(conversationId = conversationId),
                )
            }.onFailure {
                android.util.Log.w(TAG, "live call failed to start: ${it.message}", it)
                RealtimeVoiceService.stop(appContext)
            }
        }
    }

    /**
     * End the call and stop the service.
     *
     * Both, in that order, and never only the service: stopping the service
     * leaves the socket open, which is the shape `RealtimeVoiceService`'s own
     * End action was written to avoid.
     */
    fun endCall(reason: String = "user ended the call") {
        viewModelScope.launch {
            runCatching { controller.end(reason) }
                .onFailure { android.util.Log.w(TAG, "ending the call failed: ${it.message}", it) }
            RealtimeVoiceService.stop(appContext)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // A call outlives its composable only through the foreground service,
        // and the service is ended explicitly. If this ViewModel is going away
        // the screen is gone, so the socket must not be left holding a mic.
        if (controller.state.value.phase != RealtimeCallController.Phase.IDLE &&
            controller.state.value.phase != RealtimeCallController.Phase.ENDED
        ) {
            RealtimeVoiceService.stop(appContext)
        }
    }

    private companion object {
        const val TAG = "LiveCallVM"

        const val CALL_INSTRUCTIONS =
            "You are Aura, speaking with the user by voice. Keep replies short and " +
                "conversational — one or two sentences unless asked for more. You are on a " +
                "live call, so the user can interrupt you at any time."
    }
}
