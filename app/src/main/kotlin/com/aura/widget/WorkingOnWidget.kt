package com.aura.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.aura.MainActivity
import com.aura.R
import com.aura.health.WorkerRunEntity
import com.aura.health.WorkerRunRecorder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One line, on the home screen, saying what Aura is doing or last did.
 *
 * ## Why this exists
 *
 * Aura runs nine scheduled jobs — dreams, the daemon, decay, evolution,
 * backup, triggers, the calendar check, the morning brief, the place log — and
 * until now none of them were visible anywhere except a Diagnostics screen
 * nobody opens. A background process that emits **no** signal is
 * indistinguishable from a dead one: the user stops believing anything happens
 * between sessions, and the whole nightly-consolidation architecture becomes
 * something they have to take on faith.
 *
 * The obvious fix — notify when something interesting happens — is worse. It
 * converts a background process into an interruption, and an assistant that
 * interrupts to report its own housekeeping gets muted within a week.
 *
 * Weiser & Brown described the third option in 1996 (*The Coming Age of Calm
 * Technology*): a channel in the **periphery**, which is "what we are attuned
 * to without attending to explicitly". Their example was the Dangling String,
 * a piece of plastic spaghetti on a motor wired to an Ethernet cable, twitching
 * once per packet. It carried real information at essentially zero attention
 * cost. The word they used for what it gave people is *locatedness* — knowing
 * what is going on around you without checking.
 *
 * So: one line, no badge, no count, no dot, no notification, ever. The moment
 * this becomes a notification the channel is gone permanently.
 *
 * ## Every twitch is a real packet
 *
 * The Dangling String worked because each twitch *was* a packet. Nothing here
 * is generated, inferred or written for atmosphere — the line renders straight
 * from `worker_runs`, which each worker writes before and after its own pass.
 * If a worker has nothing to report the line says so, in those words.
 *
 * That constraint is why four workers had to be fixed before this could exist:
 * DecayWorker and MorningBriefWorker wrote no rows at all, and EvolutionWorker
 * and TriggerWorker recorded an empty detail on success. A status line built on
 * top of those would have been decoration, and a user discovering that is worse
 * than never having shown them anything.
 *
 * ## In-flight without a new table
 *
 * [WorkerRunRecorder.record] inserts its row *before* running the work, leaving
 * `finishedAt == 0L` until the pass returns — so "currently working" was
 * already observable and needed no schema change, only a reader that looks.
 */
class WorkingOnWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkingOnEntryPoint {
        fun workerRunRecorder(): WorkerRunRecorder
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refreshWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // The same broadcast AskAuraWidget listens for. updatePeriodMillis is
        // clamped to 30 minutes by the system, which is far too slow for a line
        // claiming to say what Aura is doing *now*; the push is what makes the
        // claim true between ticks.
        if (intent.action == com.aura.proactive.ProactiveBootstrap.ACTION_REFRESH_WIDGET) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, WorkingOnWidget::class.java))
            if (ids.isNotEmpty()) refreshWidgets(context, mgr, ids)
        }
    }

    private fun refreshWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val recorder = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WorkingOnEntryPoint::class.java,
        ).workerRunRecorder()

        // goAsync keeps the broadcast's PendingResult open until the read
        // finishes; finishing first lets the system kill the process mid-refresh
        // and drop the update.
        goAsync {
            val runs = runCatching { recorder.latestPerWorker() }.getOrDefault(emptyList())
            val state = describe(runs, System.currentTimeMillis())
            withContext(Dispatchers.Main) {
                for (id in appWidgetIds) updateOne(context, appWidgetManager, id, state)
            }
        }
    }

    private fun updateOne(
        context: Context,
        mgr: AppWidgetManager,
        widgetId: Int,
        state: State,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_working_on)
        views.setTextViewText(R.id.working_on_line, state.line)
        views.setTextViewText(R.id.working_on_source, state.source)

        // Tapping moves it from the periphery to the centre, which is the other
        // half of Weiser's point — the channel is only calm if attending to it
        // is possible and cheap.
        val open = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // Unconditional: FLAG_IMMUTABLE exists from API 23 and minSdk is 26, so
        // the version guard only ever handed a mutable PendingIntent to devices
        // below Android 12 rather than protecting anything.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        views.setOnClickPendingIntent(
            R.id.working_on_root,
            PendingIntent.getActivity(context, 0, open, flags),
        )

        mgr.updateAppWidget(widgetId, views)
    }

    /** What the widget shows: the state, and where that state came from. */
    internal data class State(val line: String, val source: String)

    companion object {
        /**
         * An unfinished row older than this means the process was killed
         * mid-run, not that the work is still going.
         *
         * That distinction is the reason the row is written before the work:
         * an orphaned row is evidence of a kill, and the common cause of a
         * worker producing nothing is being killed rather than failing. Claiming
         * "still dreaming" eleven hours later would be the widget inventing a
         * state, which is the one thing it must not do.
         */
        internal const val STALE_AFTER_MS = 30L * 60 * 1000

        /**
         * Build the line from the run log.
         *
         * Internal and pure so it can be tested without a device, a database or
         * a launcher — the logic here is all of the risk, and none of it needs
         * any of those.
         */
        internal fun describe(runs: List<WorkerRunEntity>, now: Long): State {
            val running = runs
                .filter { it.finishedAt == 0L && now - it.startedAt in 0 until STALE_AFTER_MS }
                .maxByOrNull { it.startedAt }
            if (running != null) {
                return State(presentTense(running.worker), "${humanName(running.worker)} · now")
            }

            val last = runs.filter { it.finishedAt > 0L }.maxByOrNull { it.finishedAt }
                ?: return State("Nothing has run yet.", "waiting for the first pass")

            // The detail is written by the worker in the user's terms — "3
            // summaries, 2 clusters, raised a question" — so it is quoted rather
            // than re-described. Blank should no longer happen, but a worker
            // added later could forget, and a blank line is worse than a dull
            // one.
            val line = last.detail.ifBlank { pastTense(last.worker) }
            return State(line, "${humanName(last.worker)} · ${ago(now - last.finishedAt)}")
        }

        private fun presentTense(worker: String): String = when (worker) {
            "DreamWorker" -> "Consolidating what it learned"
            "DaemonWorker" -> "Thinking things over"
            "DecayWorker" -> "Letting old memories fade"
            "EvolutionWorker" -> "Reviewing its own behaviour"
            "BackupWorker" -> "Backing up"
            "CalendarCheckWorker" -> "Checking your calendar"
            "TriggerWorker" -> "Checking your triggers"
            "MorningBriefWorker" -> "Writing your morning brief"
            "PlaceLogWorker" -> "Noting where you are"
            else -> "Working"
        }

        private fun pastTense(worker: String): String = when (worker) {
            "DreamWorker" -> "Consolidated overnight"
            "DaemonWorker" -> "Had a think"
            "DecayWorker" -> "Swept old memories"
            "EvolutionWorker" -> "Reviewed itself"
            "BackupWorker" -> "Backed up"
            "CalendarCheckWorker" -> "Checked the calendar"
            "TriggerWorker" -> "Checked triggers"
            "MorningBriefWorker" -> "Wrote the brief"
            "PlaceLogWorker" -> "Noted a place"
            else -> "Ran"
        }

        private fun humanName(worker: String): String = when (worker) {
            "DreamWorker" -> "Dreams"
            "DaemonWorker" -> "Daemon"
            "DecayWorker" -> "Memory decay"
            "EvolutionWorker" -> "Evolution"
            "BackupWorker" -> "Backup"
            "CalendarCheckWorker" -> "Calendar"
            "TriggerWorker" -> "Triggers"
            "MorningBriefWorker" -> "Morning brief"
            "PlaceLogWorker" -> "Place log"
            else -> worker.removeSuffix("Worker")
        }

        private fun ago(elapsedMs: Long): String {
            val minutes = elapsedMs / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "${minutes}m ago"
                minutes < 60 * 24 -> "${minutes / 60}h ago"
                else -> "${minutes / (60 * 24)}d ago"
            }
        }

        /** Push a refresh to every instance. No-ops cleanly when none exist. */
        fun requestRefresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, WorkingOnWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, WorkingOnWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
