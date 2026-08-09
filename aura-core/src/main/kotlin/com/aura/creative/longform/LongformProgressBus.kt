package com.aura.creative.longform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The scene currently being written, streamed live to whatever screen is open.
 *
 * @param text accumulated so far — not the whole manuscript, just this scene.
 */
data class LiveScene(
    val jobId: String,
    val beatIndex: Int,
    val totalBeats: Int,
    val beatTitle: String,
    val text: String,
)

/**
 * In-memory channel for the tokens of the scene being written right now.
 *
 * Deliberately not persisted. Durable state — which beats are drafted, and the
 * text of each — is written to Room as each scene completes; this carries only
 * the half-finished paragraph on screen. On a hard process kill the user loses
 * the live *view* and not the *work*: the run resumes at the beat it was on and
 * redrafts it.
 *
 * Persisting every token would be write amplification measured in thousands of
 * transactions per scene, in exchange for saving a partial paragraph that the
 * model is about to rewrite anyway.
 */
@Singleton
class LongformProgressBus @Inject constructor() {

    private val _live = MutableStateFlow<LiveScene?>(null)

    /** Null when no scene is streaming — between scenes, or between runs. */
    val live: StateFlow<LiveScene?> = _live.asStateFlow()

    fun beginScene(jobId: String, beatIndex: Int, totalBeats: Int, beatTitle: String) {
        _live.value = LiveScene(jobId, beatIndex, totalBeats, beatTitle, text = "")
    }

    fun appendToScene(chunk: String) {
        val current = _live.value ?: return
        _live.value = current.copy(text = current.text + chunk)
    }

    /**
     * Clear the live view.
     *
     * Called when a scene is committed and when a run ends. Not calling it would
     * leave the last scene's text on screen looking like it was still being
     * written.
     */
    fun clear() {
        _live.value = null
    }
}
