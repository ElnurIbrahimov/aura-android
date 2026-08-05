package com.aura.ui.screens

import com.aura.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.proactive.ProactiveEventBus
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.util.toSummary
import com.aura.ui.viewmodel.ProactiveHistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.aura.ui.theme.AuraThemeTokens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
/**
 * Category tag used by [HistoryCard] to pick the right accent colours
 * for the icon container — no colour values live in the model itself.
 */
enum class HistoryCardKind { MorningBrief, CalendarEvent, MemoryDecay, DaemonInsight }

/**
 * Polished icon + data model for a single proactive-history card.
 * [icon] is a Material [ImageVector] for a consistent brand look.
 * [kind] drives accent colours inside the card composable.
 */
data class HistoryCardModel(
    val icon: ImageVector,
    val kind: HistoryCardKind,
    val title: String,
    val body: String,
    val badge: Pair<String, Int>? = null,           // (label, count) e.g. ("recalled", 5)
    val tapHint: String?,
    val timestamp: Long,
    val onClick: (() -> Unit)?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveHistoryScreen(
    onBack: () -> Unit = {},
    viewModel: ProactiveHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AuraScreenShell(
        title = stringResource(R.string.proactive_history),
        subtitle = "Proactive events and interactions",
    ) { padding ->
        val status by viewModel.status.collectAsStateWithLifecycle()
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.md), horizontalArrangement = Arrangement.End) {
            androidx.compose.material3.IconButton(onClick = { viewModel.fireCalendarCheck() }) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                )
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.medium),
        ) {
            val reversed = state.events.reversed()
            if (reversed.isEmpty()) {
                item { EmptyState() }
            }
            items(reversed) { event ->
                val model = event.toCardModel(context)
                HistoryCard(
                    model = model,
                    modifier = if (model.onClick != null) Modifier.clickable(onClick = model.onClick) else Modifier,
                )
            }
            item {
                DebugSection(
                    status = status,
                    onFireBrief = { viewModel.fireMorningBrief() },
                    onFireDecay = { viewModel.fireDecayPass() },
                    onFireCalendar = { viewModel.fireCalendarCheck() },
                    onClearStatus = { viewModel.clearStatus() },
                )
        }
        }
    }
}

// ── Empty state ────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, bottom = AuraSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.HistoryToggleOff,
            contentDescription = null,
            tint = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.25f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(AuraSpacing.sm))
        Text(
            text = stringResource(R.string.no_proactive_events_yet),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(AuraSpacing.xxs))
        Text(
            text = stringResource(R.string.morning_briefs_calendar_reminders_and_memory),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.38f),
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

// ── Event → model mapping ──────────────────────────────────────────────────

private fun ProactiveEventBus.Event.toCardModel(context: android.content.Context): HistoryCardModel = when (this) {
    is ProactiveEventBus.Event.MorningBriefReady -> HistoryCardModel(
        icon = Icons.Filled.WbSunny,
        kind = HistoryCardKind.MorningBrief,
        title = "Morning brief",
        body = body,
        tapHint = "Tap to chat",
        timestamp = timestamp,
        onClick = {
            context.startActivity(
                Intent(context, com.aura.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("openChat", true)
                }
            )
        },
    )
    is ProactiveEventBus.Event.MorningBriefStructured -> HistoryCardModel(
        icon = Icons.Filled.WbSunny,
        kind = HistoryCardKind.MorningBrief,
        title = "Morning brief",
        body = this.context.toSummary(),
        tapHint = "Tap to chat",
        timestamp = timestamp,
        onClick = {
            context.startActivity(
                Intent(context, com.aura.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("openChat", true)
                }
            )
        },
    )
    is ProactiveEventBus.Event.CalendarEventSoon -> {
        val minutes = minutesUntil
        val label = if (minutes < 60) "in $minutes min" else "in ${minutes / 60}h ${minutes % 60}m"
        HistoryCardModel(
            icon = Icons.Filled.CalendarMonth,
            kind = HistoryCardKind.CalendarEvent,
            title = "Upcoming: $title",
            body = label,
            tapHint = "Open calendar",
            timestamp = timestamp,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = CalendarContract.CONTENT_URI.buildUpon()
                        .appendPath("time")
                        .appendEncodedPath(System.currentTimeMillis().toString())
                        .build()
                }
                context.startActivity(intent)
            },
        )
    }
    is ProactiveEventBus.Event.DaemonInsight -> HistoryCardModel(
        icon = Icons.Filled.Lightbulb,
        kind = HistoryCardKind.DaemonInsight,
        title = this.title,
        body = this.body,
        tapHint = null,
        timestamp = timestamp,
        onClick = null,
    )
    is ProactiveEventBus.Event.MemoryDecayWarning -> HistoryCardModel(
        icon = Icons.Filled.Psychology,
        kind = HistoryCardKind.MemoryDecay,
        title = "Memory fading",
        body = preview,
        tapHint = "Open memory",
        timestamp = timestamp,
        onClick = {
            context.startActivity(
                Intent(context, com.aura.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("openMemory", true)
                }
            )
        },
    )
}

// ── History card composable ─────────────────────────────────────────────────

@Composable
private fun HistoryCard(
    model: HistoryCardModel,
    modifier: Modifier = Modifier,
) {
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

    // Resolve accent colours by event kind
    val (iconTint, iconBackground) = when (model.kind) {
        HistoryCardKind.MorningBrief, HistoryCardKind.MemoryDecay ->
            AuraThemeTokens.colors.actionPrimary to AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.12f)
        HistoryCardKind.CalendarEvent ->
            AuraThemeTokens.colors.assistantAccent to AuraThemeTokens.colors.assistantAccent.copy(alpha = 0.12f)
        HistoryCardKind.DaemonInsight ->
            AuraThemeTokens.colors.actionPrimary to AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.12f)
    }
    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.large),
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AuraSpacing.large),
            verticalAlignment = Alignment.Top,
        ) {
            // ── Icon container (branded circle) ──────────────────────
            Surface(
                color = iconBackground,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = model.icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(AuraSpacing.xs),
                )
            }
            Spacer(modifier = Modifier.width(AuraSpacing.large))
            Column(modifier = Modifier.weight(1f)) {
                // ── Title row ────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraThemeTokens.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    model.badge?.let { (label, count) ->
                        Surface(
                            color = iconBackground,
                            shape = RoundedCornerShape(AuraSpacing.small),
                        ) {
                            Text(
                                text = "$count $label",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = iconTint,
                                modifier = Modifier.padding(horizontal = AuraSpacing.xs, vertical = 3.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                // ── Body ─────────────────────────────────────────────
                Text(
                    text = model.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.85f),
                )
                // ── Footer row: tap hint + timestamp ─────────────────
                Spacer(modifier = Modifier.height(AuraSpacing.small))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    model.tapHint?.let { hint ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.7f),
                                modifier = Modifier.size(AuraSpacing.sm),
                            )
                            Spacer(modifier = Modifier.width(AuraSpacing.xxs))
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.8f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.38f),
                            modifier = Modifier.size(AuraSpacing.sm),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = fmt.format(Date(model.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

// ── Debug section with branded action rows ─────────────────────────────────

@Composable
private fun DebugSection(
    status: String?,
    onFireBrief: () -> Unit,
    onFireDecay: () -> Unit,
    onFireCalendar: () -> Unit,
    onClearStatus: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = AuraSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
    ) {
        HorizontalDivider(
            color = AuraThemeTokens.colors.borderDefault,
        )
        Spacer(modifier = Modifier.height(AuraSpacing.xxs))

        // ── Section header ──────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = AuraThemeTokens.colors.actionPrimary,
                modifier = Modifier.size(AuraSpacing.xl2),
            )
            Spacer(modifier = Modifier.width(AuraSpacing.xs))
            Text(
                text = stringResource(R.string.debug_fire_now),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AuraThemeTokens.colors.actionPrimary,
            )
        }
        Spacer(modifier = Modifier.height(AuraSpacing.tiny))
        Text(
            text = stringResource(R.string.run_each_loop_on_demand_without),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.55f),
        )
        Spacer(modifier = Modifier.height(AuraSpacing.xxs))

        // ── Action: Morning brief ───────────────────────────────────────
        DebugActionRow(
            icon = Icons.Filled.WbSunny,
            iconTint = AuraThemeTokens.colors.actionPrimary,
            iconBackground = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.12f),
            label = "Morning brief",
            description = "Generate and deliver the daily morning summary",
            onClick = onFireBrief,
        )
        // ── Action: Memory decay ────────────────────────────────────────
        DebugActionRow(
            icon = Icons.Filled.DeleteSweep,
            iconTint = AuraThemeTokens.colors.actionPrimary,
            iconBackground = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.12f),
            label = "Memory decay pass",
            description = "Prune and consolidate aged memories",
            onClick = onFireDecay,
        )
        // ── Action: Calendar check ──────────────────────────────────────
        DebugActionRow(
            icon = Icons.Filled.Notifications,
            iconTint = AuraThemeTokens.colors.assistantAccent,
            iconBackground = AuraThemeTokens.colors.assistantAccent.copy(alpha = 0.12f),
            label = "Calendar check",
            description = "Scan for upcoming calendar events",
            onClick = onFireCalendar,
        )

        // ── Status message ──────────────────────────────────────────────
        status?.let { msg ->
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            Surface(
                color = if (msg.contains("Error", ignoreCase = true))
                    AuraThemeTokens.colors.error
                else
                    AuraThemeTokens.colors.surface2,
                shape = RoundedCornerShape(AuraSpacing.medium),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(AuraSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (msg.contains("Error", ignoreCase = true))
                            Icons.Filled.Clear
                        else
                            Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (msg.contains("Error", ignoreCase = true))
                            AuraThemeTokens.colors.error
                        else
                            AuraThemeTokens.colors.assistantAccent,
                        modifier = Modifier.size(AuraSpacing.xl2),
                    )
                    Spacer(modifier = Modifier.width(AuraSpacing.medium))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = AuraThemeTokens.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.dismiss),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraThemeTokens.colors.actionPrimary,
                        modifier = Modifier.clickable { onClearStatus() },
                    )
                }
            }
        }
    }
}

/**
 * A single debug action row with a branded icon container, label,
 * description, and a compact "Run" button.
 */
@Composable
private fun DebugActionRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    iconBackground: androidx.compose.ui.graphics.Color,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.sm),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AuraSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Branded icon circle
            Surface(
                color = iconBackground,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .size(36.dp)
                        .padding(7.dp),
                )
            }
            Spacer(modifier = Modifier.width(AuraSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraThemeTokens.colors.textPrimary,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.55f),
                )
            }
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = iconBackground,
                    contentColor = iconTint,
                ),
                shape = RoundedCornerShape(AuraSpacing.xs),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                modifier = Modifier.height(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(AuraSpacing.large),
                )
                Spacer(modifier = Modifier.width(AuraSpacing.xxs))
                Text(
                    text = stringResource(R.string.run),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}