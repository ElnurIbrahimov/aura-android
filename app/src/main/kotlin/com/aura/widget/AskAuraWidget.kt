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
import com.aura.data.UserPreferences
import com.aura.memory.MemoryStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home-screen widget. Shows a quick-ask button + the most recent
 * memory. Tapping the widget body opens Aura; tapping the Ask
 * button does the same (the current ChatScreen handles a fresh
 * conversation flow).
 *
 * Update cadence: 30 minutes (set in widget_ask_aura_info.xml's
 * updatePeriodMillis). On every update we read the most-recent
 * memory and update the body text. We also update on
 * APPWIDGET_ENABLED (when the user adds the widget) and on
 * APPWIDGET_UPDATE broadcasts.
 *
 * Hilt injection: the AppWidgetProvider is created by the system,
 * not Hilt, so we use [EntryPointAccessors] to pull [MemoryStore]
 * and [UserPreferences] out of the SingletonComponent.
 */
class AskAuraWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun memoryStore(): MemoryStore
        fun userPreferences(): UserPreferences
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Called from within super.onReceive's dispatch, so goAsync()
        // (inside refreshWidgets) is still valid here.
        refreshWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Listen for the broadcast the [ProactiveBootstrap] sends on
        // cold start so the widget body stays current even if the
        // system hasn't ticked over 30 minutes yet.
        if (intent.action == com.aura.proactive.ProactiveBootstrap.ACTION_REFRESH_WIDGET) {
            val mgr = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AskAuraWidget::class.java)
            val ids = mgr.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                refreshWidgets(context, mgr, ids)
            }
        }
    }

    private fun refreshWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Refresh every widget instance. The goAsync helper keeps the
        // broadcast's PendingResult open until the IO work completes —
        // finishing first (the old pattern) let the system kill the
        // process mid-refresh and drop the update.
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        )
        val memoryStore = entry.memoryStore()
        val userPreferences = entry.userPreferences()

        goAsync {
            // The lock check the widget never made. This surface renders the
            // most recent memory *verbatim* on the home screen, refreshed every
            // 30 minutes, where it is readable by anyone holding the phone —
            // including over a lock screen, which is exactly the state app lock
            // exists for. `userPreferences` was already wired into this entry
            // point and never called.
            //
            // The memory is not read at all when locked, rather than read and
            // hidden: nothing that cannot be displayed should be in the
            // RemoteViews bundle, which crosses into the launcher's process.
            val locked = try {
                userPreferences.appLockEnabled.first()
            } catch (e: Exception) {
                // Fail closed. An unreadable preference is not permission to
                // paint someone's memories onto their home screen.
                true
            }
            val recent = if (locked) {
                null
            } else {
                try {
                    memoryStore.recent(1).firstOrNull()
                } catch (e: Exception) {
                    null
                }
            }
            withContext(Dispatchers.Main) {
                for (id in appWidgetIds) {
                    updateOne(context, appWidgetManager, id, recent?.content, locked)
                }
            }
        }
    }

    private fun updateOne(
        context: Context,
        mgr: AppWidgetManager,
        widgetId: Int,
        memoryContent: String?,
        locked: Boolean = false,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_ask_aura)

        val bodyText = when {
            // Says it is hidden rather than pretending there is nothing. "No
            // memories yet" would be a lie to the one user who knows better,
            // and the widget still works — tapping it opens the app, which
            // asks for the fingerprint.
            locked -> "Hidden while Aura is locked."
            memoryContent.isNullOrBlank() ->
                "No memories yet — start a chat to teach Aura about you."
            else -> memoryContent.take(180)
        }
        views.setTextViewText(R.id.widget_memory_text, bodyText)

        // Tapping the widget body opens Aura.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPending = PendingIntent.getActivity(
            context, 0, openIntent,
            pendingFlags(),
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPending)

        // The Ask button opens QuickAskActivity — a lightweight
        // transparent activity with a text field for asking a
        // question without opening the full app.
        val askIntent = Intent(context, QuickAskActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val askPending = PendingIntent.getActivity(
            context, 1, askIntent,
            pendingFlags(),
        )
        views.setOnClickPendingIntent(R.id.widget_ask_button, askPending)

        mgr.updateAppWidget(widgetId, views)
    }

    private fun pendingFlags(): Int =
        // FLAG_IMMUTABLE unconditionally. It is *required* from API 31 and has
        // existed since API 23, and `minSdk` here is 26 — so the version guard
        // this used to carry only ever did one thing: hand a mutable
        // PendingIntent to every device below Android 12, which is the half of
        // the range where it matters most. Six other call sites in the app set
        // it unconditionally already; these two widgets were the outliers.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        /**
         * Broadcast a refresh to all widget instances. Call from
         * [com.aura.proactive.ProactiveBootstrap] or whenever the
         * memory store is updated so the widget body stays current
         * (independent of the 30-minute system refresh).
         */
        fun requestRefresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AskAuraWidget::class.java)
            val ids = mgr.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val intent = Intent(context, AskAuraWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
