package com.aura.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import com.aura.MainActivity
import com.aura.R
import com.aura.tasks.ReminderDao
import com.aura.tasks.ReminderEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.DateFormat
import java.util.Date
import android.util.Log

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderWidgetEntryPoint {
    fun reminderDao(): ReminderDao
    fun userPreferences(): com.aura.data.UserPreferences
}

class ReminderWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, ReminderWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                val intent = Intent(context, ReminderWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // goAsync (not a throwaway CoroutineScope): the PendingResult
        // keeps the process alive until the Room read and RemoteViews
        // push complete, so the update isn't dropped on process death.
        goAsync {
            val entry = runCatching {
                EntryPointAccessors.fromApplication(
                    context,
                    ReminderWidgetEntryPoint::class.java,
                )
            }.onFailure { Log.w("ReminderWidgetProvider", "runCatching failed: ${it.message}", it) }.getOrNull() ?: return@goAsync

            // Reminder bodies are user-authored text — "call the clinic about
            // the results" is exactly the sort of thing app lock is for, and
            // this widget painted three of them onto the home screen with no
            // check. Fail closed: an unreadable preference is not consent.
            val locked = runCatching { entry.userPreferences().appLockEnabled.first() }
                .onFailure { Log.w("ReminderWidgetProvider", "lock read failed: ${it.message}", it) }
                .getOrDefault(true)

            val reminders = if (locked) {
                emptyList()
            } else {
                entry.reminderDao().allForBackup()
                    .filter { it.triggerAt > System.currentTimeMillis() }
                    .sortedBy { it.triggerAt }
                    .take(3)
            }

            for (id in appWidgetIds) {
                val views = buildViews(context, reminders, locked)
                appWidgetManager.updateAppWidget(id, views)
            }
        }
    }

    private fun buildViews(
        context: Context,
        reminders: List<ReminderEntity>,
        locked: Boolean = false,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_reminders)
        val df = DateFormat.getTimeInstance(DateFormat.SHORT)

        // Title. States that it is hidden rather than claiming there is
        // nothing — "No upcoming reminders" to someone who set three would be
        // a lie, and the distinction matters most to the person it lies to.
        views.setTextViewText(
            R.id.widget_title,
            when {
                locked -> "Hidden while Aura is locked"
                reminders.isEmpty() -> "No upcoming reminders"
                else -> "Upcoming reminders"
            },
        )

        // Fill up to 3 reminder rows
        val rowIds = listOf(R.id.reminder_row_1, R.id.reminder_row_2, R.id.reminder_row_3)
        val msgIds = listOf(R.id.reminder_msg_1, R.id.reminder_msg_2, R.id.reminder_msg_3)
        val timeIds = listOf(R.id.reminder_time_1, R.id.reminder_time_2, R.id.reminder_time_3)

        for (i in rowIds.indices) {
            if (i < reminders.size) {
                val r = reminders[i]
                views.setTextViewText(msgIds[i], r.message.ifBlank { "Reminder" })
                views.setTextViewText(timeIds[i], df.format(Date(r.triggerAt)))
                views.setViewVisibility(rowIds[i], android.view.View.VISIBLE)

                // Tap opens the app at reminders route
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    putExtra("navRoute", "reminders")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                views.setOnClickPendingIntent(
                    rowIds[i],
                    PendingIntent.getActivity(
                        context, i, openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                views.setViewVisibility(rowIds[i], android.view.View.GONE)
            }
        }

        // "Add reminder" button opens chat with prefill
        val addIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("chatPrefillDraft", "remind me to ")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setOnClickPendingIntent(
            R.id.widget_add,
            PendingIntent.getActivity(
                context, 99, addIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        return views
    }
}