package com.aura.widget

import androidx.compose.ui.res.stringResource
import android.app.PendingIntent
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.MainActivity
import com.aura.R
import com.aura.agent.Turn
import com.aura.ui.theme.AuraTheme
import com.aura.ui.viewmodel.ChatViewModel
import com.aura.ui.viewmodel.ModelSelectionState
import dagger.hilt.android.AndroidEntryPoint

import com.aura.ui.theme.AuraThemeTokens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.theme.AuraSpacing
internal fun buildQuickAskSystemPrompt(prefix: String): String = buildString {
    append(
        "This is a compact Aura session opened from the home-screen widget. " +
            "Answer directly and keep ordinary answers brief, but use tools, memory, " +
            "and deeper reasoning whenever the question needs them.",
    )
    if (prefix.isNotBlank()) {
        append("\n\nWidget instruction:\n")
        append(prefix.trim())
    }
}

internal fun latestQuickAskResponse(turns: List<Turn>): String =
    turns.asReversed().firstNotNullOfOrNull { it.assistant?.takeIf(String::isNotBlank) }.orEmpty()

/**
 * Compact transparent surface opened by [AskAuraWidget]. It deliberately uses
 * [ChatViewModel], the same agentic pipeline as the full chat: memory recall,
 * tools, specialists, KG/profile extraction, persistence, and provider failover.
 */
@AndroidEntryPoint
class QuickAskActivity : androidx.fragment.app.FragmentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    /**
     * The app-lock prompt needs a `FragmentActivity` and needs to find one here.
     *
     * This class was a `ComponentActivity` and never published itself, which is
     * half of why the lock could not cover it — the other half being that
     * `unlocked` lived inside `MainActivity`'s composition. Both are why a
     * screen running the full agentic pipeline with memory recall opened
     * straight onto the user's conversations from the home screen.
     */
    @javax.inject.Inject lateinit var biometricHolder: com.aura.security.BiometricActivityHolder

    @javax.inject.Inject lateinit var userPreferences: com.aura.data.UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biometricHolder.activity = this
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        window.setDimAmount(0.5f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            AuraTheme {
                com.aura.ui.components.AppLockGate {
                    val viewModel: ChatViewModel = hiltViewModel()
                    QuickAskContent(
                        viewModel = viewModel,
                        appWidgetId = appWidgetId,
                        widgetPrefix = WidgetConfig.prefixFor(this, appWidgetId),
                        configuredWidgetModel = WidgetConfig.modelFor(this, appWidgetId, ""),
                        onCompleted = ::updateWidgetWithResponse,
                        onOpenFullChat = {
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        },
                        onDismiss = ::finish,
                    )
                }
            }
        }
    }

    /**
     * Release the biometric slot this activity claimed in [onCreate].
     *
     * This class had no lifecycle override but `onCreate`, so the slot stayed
     * pointed at a destroyed overlay until something else happened to claim it
     * — and `BiometricPrompt` was handed that dead activity in the meantime.
     * The clear is identity-checked because `MainActivity` owns the same slot
     * and is usually still alive behind this one.
     */
    override fun onDestroy() {
        biometricHolder.clearIfCurrent(this)
        super.onDestroy()
    }

    /**
     * Echo the answer onto the widget — unless the app can lock.
     *
     * Two defects, both invisible:
     *
     * 1. **The answer outlived the unlock.** Ask something while unlocked, put
     *    the phone down, and "Q: … A: …" stayed painted on the home screen
     *    through every subsequent lock. Gating the activity does not help: the
     *    text is already out, in the launcher's process.
     * 2. **It dropped the widget's click handlers.** This built a fresh
     *    `RemoteViews` with only the text set, and `updateAppWidget` replaces
     *    the whole view tree — so the body and Ask button stopped responding
     *    until the next 30-minute refresh reinstated them. Delegating to
     *    `AskAuraWidget.requestRefresh` rebuilds the views the one way that
     *    installs the intents.
     *
     * When the lock is on, the answer is simply not echoed and the widget
     * refreshes to its normal locked state. The user still has the answer — it
     * is on the screen in front of them, and in the conversation.
     */
    private fun updateWidgetWithResponse(query: String, response: String) {
        lifecycleScope.launch {
            // The lock read was runBlocking on the main thread -- the same
            // cold-start ANR window WidgetConfigActivity removed. A failed
            // read still fails closed.
            val locked = runCatching { userPreferences.appLockEnabled.first() }
                .getOrDefault(true)
            if (locked) {
                AskAuraWidget.requestRefresh(this@QuickAskActivity)
            } else {
                echoAnswerToWidget(query, response)
            }
        }
    }

    private fun echoAnswerToWidget(query: String, response: String) {
        val manager = AppWidgetManager.getInstance(this)
        val ids = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            intArrayOf(appWidgetId)
        } else {
            manager.getAppWidgetIds(ComponentName(this, AskAuraWidget::class.java))
        }
        for (id in ids) {
            val views = RemoteViews(packageName, R.layout.widget_ask_aura)
            views.setTextViewText(
                R.id.widget_memory_text,
                "Q: ${query.take(100)}\nA: ${response.take(150)}",
            )
            // Reinstall both tap targets. Without these the widget renders the
            // answer and stops being a button.
            val openIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(this, 0, openIntent, WIDGET_PENDING_FLAGS),
            )
            val askIntent = Intent(this, QuickAskActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            views.setOnClickPendingIntent(
                R.id.widget_ask_button,
                PendingIntent.getActivity(this, 1, askIntent, WIDGET_PENDING_FLAGS),
            )
            manager.updateAppWidget(id, views)
        }
    }

    private companion object {
        val WIDGET_PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}

@Composable
private fun QuickAskContent(
    viewModel: ChatViewModel,
    appWidgetId: Int,
    widgetPrefix: String,
    configuredWidgetModel: String,
    onCompleted: (String, String) -> Unit,
    onOpenFullChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf<String?>(null) }
    var lastPublishedResponse by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!initialized) {
            viewModel.startIsolatedSession(
                systemPrompt = buildQuickAskSystemPrompt(widgetPrefix),
                model = configuredWidgetModel.takeIf(String::isNotBlank),
                title = if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                    "Quick Ask"
                } else {
                    "Quick Ask · Widget $appWidgetId"
                },
            )
            initialized = true
        }
    }

    val response = latestQuickAskResponse(state.conversation.turns)
    LaunchedEffect(state.streaming, response, submittedQuery) {
        val sent = submittedQuery
        if (!state.streaming && sent != null && response.isNotBlank() && response != lastPublishedResponse) {
            lastPublishedResponse = response
            onCompleted(sent, response)
        }
    }

    val sendEnabled = query.isNotBlank() &&
        !state.streaming &&
        state.modelSelection is ModelSelectionState.Ready
    val submit = {
        if (sendEnabled) {
            val cleaned = query.trim()
            submittedQuery = cleaned
            lastPublishedResponse = ""
            viewModel.onUserMessage(cleaned)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(AuraSpacing.md),
        shape = RoundedCornerShape(AuraSpacing.lg),
        tonalElevation = AuraSpacing.xs,
    ) {
        Column(
            modifier = Modifier.padding(AuraSpacing.xxl2),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ask_aura), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Full memory + tools",
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraThemeTokens.colors.actionPrimary,
                    )
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.ask_anything)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                enabled = !state.streaming,
            )

            state.providerWarning?.let { warning ->
                Surface(
                    color = AuraThemeTokens.colors.surface2,
                    shape = RoundedCornerShape(AuraSpacing.large),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = AuraSpacing.sm, end = AuraSpacing.xxs, top = AuraSpacing.small, bottom = AuraSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            warning,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = viewModel::dismissProviderWarning) { Text(stringResource(R.string.dismiss)) }
                    }
                }
            }

            when {
                state.streaming && response.isBlank() -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(AuraSpacing.md), strokeWidth = AuraSpacing.tiny)
                    Text(stringResource(R.string.aura_is_thinking), style = MaterialTheme.typography.bodySmall)
                }
                state.error != null -> Surface(
                    color = AuraThemeTokens.colors.error,
                    shape = RoundedCornerShape(AuraSpacing.large),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        state.error.orEmpty(),
                        modifier = Modifier.padding(AuraSpacing.sm),
                        color = AuraThemeTokens.colors.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                response.isNotBlank() -> Surface(
                    color = AuraThemeTokens.colors.surface1,
                    shape = RoundedCornerShape(AuraSpacing.md),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        response,
                        modifier = Modifier.padding(AuraSpacing.large),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.modelSelection !is ModelSelectionState.Ready -> Text(
                    "Choose and verify a model in Aura Settings first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs, Alignment.End),
            ) {
                if (response.isNotBlank()) {
                    OutlinedButton(onClick = onOpenFullChat) { Text(stringResource(R.string.open_chat)) }
                }
                Button(onClick = submit, enabled = sendEnabled) {
                    Text(if (state.streaming) "Thinking" else "Send")
                }
            }
        }
    }
}
