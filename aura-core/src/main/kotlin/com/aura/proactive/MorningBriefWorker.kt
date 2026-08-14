package com.aura.proactive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager job that runs at ~7am local time. The actual
 * brief-building logic lives in [MorningBriefBuilder] so the
 * ProactiveRunner can also call it on demand ("fire now"
 * button on the Proactive history screen).
 *
 * Notification actions (Tell me more, Snooze 1h) are wired
 * by [MorningBriefBuilder] when it posts the notification.
 */
@HiltWorker
class MorningBriefWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val builder: MorningBriefBuilder,
    private val recorder: com.aura.health.WorkerRunRecorder? = null,
) : CoroutineWorker(appContext, params) {

    // The daily brief is the most visible thing Aura does unprompted, and it
    // was the one scheduled job that left no trace in the run log — so a
    // morning with no brief gave the user nothing to distinguish "there was
    // nothing to say" from "it never ran".
    //
    // if/else rather than the elvis form; see DaemonWorker.doWork for why.
    override suspend fun doWork(): Result {
        if (recorder == null) return builder.runNow()
        return recorder.record(WORKER_NAME) { builder.runNow() to builder.lastOutcome }
            ?: Result.success()
    }

    companion object {
        const val MORNING_BRIEF_ID = 1001
        const val UNIQUE_NAME = "morning-brief-daily"

        /** Class name, matching every other worker's key in the run log. */
        const val WORKER_NAME = "MorningBriefWorker"
        const val ACTION_SNOOZE = "com.aura.MORNING_BRIEF_SNOOZE"
        const val REQUEST_CODE_CHAT = 2001
        const val REQUEST_CODE_SNOOZE = 2002

        /**
         * Intent extra carrying the persisted proactive-event id of the
         * brief. MainActivity turns it into a nav argument and the chat
         * screen loads the body from [ProactiveEventDao] — the full brief
         * text never travels through intent/nav-route extras
         * (TransactionTooLargeException risk on long briefs).
         */
        const val EXTRA_MORNING_BRIEF_ID = "morningBriefId"
    }
}
