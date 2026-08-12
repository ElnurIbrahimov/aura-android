package com.aura.ui.screens

import com.aura.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.agent.runtime.AgentTraceEvent
import com.aura.core.error.CrashLogEntry
import com.aura.ui.viewmodel.DiagnosticsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.exportFile) {
        val file = state.exportFile ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Share Aura diagnostics"))
        viewModel.consumeExport()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AuraSpacing.xxl2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = AuraSpacing.medium, bottom = AuraSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(AuraSpacing.xxs))
            Column(Modifier.weight(1f)) {
                Text(
                    "Diagnostics",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${state.entries.size} local ${if (state.entries.size == 1) "entry" else "entries"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.62f),
                )
            }
            IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh diagnostics")
            }
        }

        Surface(
            color = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.42f),
            shape = RoundedCornerShape(AuraSpacing.large),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(13.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = AuraThemeTokens.colors.actionPrimary,
                    modifier = Modifier.size(AuraSpacing.xxl2),
                )
                Spacer(Modifier.width(AuraSpacing.medium))
                Text(
                    "Logs stay on this device. Aura never uploads them; they leave only when you tap Share.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.onActionPrimary,
                )
            }
        }
        Spacer(Modifier.height(AuraSpacing.medium))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
        ) {
            Button(
                onClick = viewModel::prepareExport,
                enabled = !state.exporting,
                modifier = Modifier.weight(1f),
            ) {
                if (state.exporting) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = AuraSpacing.tiny)
                } else {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(AuraSpacing.small))
                Text(if (state.exporting) "Preparing…" else "Share log")
            }
            OutlinedButton(
                onClick = { showClearConfirm = true },
                enabled = state.entries.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = AuraThemeTokens.colors.error,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(AuraSpacing.small))
                Text(stringResource(R.string.clear), color = AuraThemeTokens.colors.error)
            }
        }
        Spacer(Modifier.height(AuraSpacing.medium))

        BackgroundHealthCard(state.health)
        Spacer(Modifier.height(AuraSpacing.medium))

        // Rebuild the knowledge graph from stored conversations.
        //
        // Manual and opt-in because it costs one model call per turn. The graph
        // was truncated to roughly one turn's worth of connections for as long
        // as kg_nodes was written with INSERT OR REPLACE against a cascading
        // child table. That is fixed and the graph grows correctly now, but the
        // connections lost in between exist only in the conversations that
        // produced them, so recovering them means paying to re-read those.
        Surface(
            color = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.42f),
            shape = RoundedCornerShape(AuraSpacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(AuraSpacing.medium)) {
                Text(
                    "Rebuild knowledge graph",
                    style = MaterialTheme.typography.titleSmall,
                    color = AuraThemeTokens.colors.textPrimary,
                )
                Spacer(Modifier.height(AuraSpacing.xs))
                Text(
                    "Re-reads your saved conversations and re-derives the graph. " +
                        "Costs one model call per turn, so it is not automatic. " +
                        "Safe to run more than once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.62f),
                )
                val rebuild = state.kgRebuild
                if (rebuild != null) {
                    Spacer(Modifier.height(AuraSpacing.small))
                    val done = rebuild.turnsDone
                    val total = rebuild.turnsTotal
                    Text(
                        buildString {
                            append(if (rebuild.running) "Rebuilding… " else "Finished. ")
                            append("$done of $total turns")
                            if (rebuild.failures > 0) append(" — ${rebuild.failures} could not be read")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.62f),
                    )
                }
                Spacer(Modifier.height(AuraSpacing.small))
                if (rebuild?.running == true) {
                    OutlinedButton(onClick = viewModel::cancelKnowledgeGraphRebuild) {
                        Text(stringResource(R.string.cancel))
                    }
                } else {
                    OutlinedButton(onClick = viewModel::rebuildKnowledgeGraph) {
                        Text("Rebuild from history")
                    }
                }
            }
        }
        Spacer(Modifier.height(AuraSpacing.medium))

        state.error?.let { error ->
            Surface(
                color = AuraThemeTokens.colors.error,
                shape = RoundedCornerShape(AuraSpacing.sm),
                modifier = Modifier.fillMaxWidth().padding(bottom = AuraSpacing.xs),
            ) {
                Text(
                    error,
                    modifier = Modifier.padding(AuraSpacing.sm),
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary,
                )
            }
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.entries.isEmpty() && state.traceEvents.isEmpty() -> DiagnosticsEmptyState()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
            ) {
                if (state.traceEvents.isNotEmpty()) {
                    item {
                        Text(
                            "Agent Trace (${state.traceCount} events)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.68f),
                            modifier = Modifier.padding(top = AuraSpacing.xs, bottom = AuraSpacing.xxs),
                        )
                    }
                    items(state.traceEvents.takeLast(50), key = { it.id }) { event ->
                        TraceEventCard(event)
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = AuraSpacing.xs)) }
                }
                items(state.entries, key = { "${it.timestamp}:${it.code}:${it.message.hashCode()}" }) { entry ->
                    DiagnosticCard(entry)
                }
                item { Spacer(Modifier.height(AuraSpacing.sm)) }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_diagnostic_history)) },
            text = { Text("This permanently deletes ${state.entries.size} local entries. Export first if you need them.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearAll()
                }) { Text(stringResource(R.string.clear_all), color = AuraThemeTokens.colors.error) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DiagnosticCard(entry: CrashLogEntry) {
    var expanded by remember(entry.timestamp, entry.code) { mutableStateOf(false) }
    val canExpand = !entry.stackTrace.isNullOrBlank()
    val accent = if (entry.fatal) AuraThemeTokens.colors.error else AuraThemeTokens.colors.assistantAccent

    Surface(
        color = AuraThemeTokens.colors.surface1.copy(alpha = 0.38f),
        shape = RoundedCornerShape(AuraSpacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier),
    ) {
        Column(Modifier.padding(AuraSpacing.large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accent.copy(alpha = 0.14f), shape = CircleShape) {
                    Text(
                        if (entry.fatal) "FATAL" else "ERROR",
                        modifier = Modifier.padding(horizontal = AuraSpacing.xs, vertical = AuraSpacing.xxs),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(AuraSpacing.xs))
                Text(
                    entry.code,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canExpand) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse stack trace" else "Expand stack trace",
                        modifier = Modifier.size(AuraSpacing.xxl2),
                    )
                }
            }
            Spacer(Modifier.height(AuraSpacing.xs))
            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(7.dp))
            Text(
                buildString {
                    append(DIAGNOSTIC_DATE_FORMAT.format(Date(entry.timestamp)))
                    if (entry.threadName.isNotBlank()) append(" · ${entry.threadName}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.68f),
            )
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = AuraSpacing.medium))
                Text(
                    entry.stackTrace.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = AuraThemeTokens.colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun TraceEventCard(event: AgentTraceEvent) {
    val accent = when {
        !event.success -> AuraThemeTokens.colors.error
        event.type.name.contains("FAIL") || event.type.name.contains("CANCEL") -> AuraThemeTokens.colors.warning
        event.type.name.contains("TOOL") -> AuraThemeTokens.colors.assistantAccent
        else -> AuraThemeTokens.colors.actionPrimary
    }
    Surface(
        color = AuraThemeTokens.colors.surface1.copy(alpha = 0.38f),
        shape = RoundedCornerShape(AuraSpacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(AuraSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accent.copy(alpha = 0.14f), shape = CircleShape) {
                    Text(
                        event.type.name,
                        modifier = Modifier.padding(horizontal = AuraSpacing.xs, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(AuraSpacing.xs))
                Text(
                    event.toolName ?: event.stepId ?: event.runId,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    DIAGNOSTIC_DATE_FORMAT.format(Date(event.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
                )
            }
            if (event.redactedPayload.isNotBlank()) {
                Spacer(Modifier.height(AuraSpacing.xxs))
                Text(
                    event.redactedPayload,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.68f),
                )
            }
            if (!event.success && event.errorCode != null) {
                Spacer(Modifier.height(AuraSpacing.xxs))
                Text(
                    "error: ${event.errorCode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraThemeTokens.colors.error,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsEmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                color = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.10f),
                shape = CircleShape,
            ) {
                Icon(
                    Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = AuraThemeTokens.colors.actionPrimary,
                    modifier = Modifier.padding(AuraSpacing.md).size(28.dp),
                )
            }
            Spacer(Modifier.height(AuraSpacing.sm))
            Text(stringResource(R.string.no_diagnostic_entries), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(
                "That is the good kind of empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.62f),
            )
        }
    }
}

private val DIAGNOSTIC_DATE_FORMAT = SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.getDefault())

/**
 * Whether Aura's background life is actually happening.
 *
 * The three states this distinguishes — nothing to do, switched off, broken —
 * were previously one state: an app that looks identical either way. Reading
 * `dumpsys jobscheduler` and comparing database file sizes was the only way to
 * tell, which is not a thing anyone should have to do to find out whether a
 * feature they were told about exists.
 */
@Composable
private fun BackgroundHealthCard(health: com.aura.health.BackgroundHealth.Snapshot) {
    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(AuraSpacing.medium)) {
            Text(
                "Background work",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(AuraSpacing.xs))

            if (health.runs.isEmpty()) {
                Text(
                    "Nothing has run yet. Most background work waits for charging " +
                        "or for a daily schedule.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.62f),
                )
            } else {
                for (run in health.runs) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = AuraSpacing.xxs)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                run.worker.removeSuffix("Worker"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                run.detail.ifBlank { run.outcome },
                                style = MaterialTheme.typography.labelSmall,
                                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.62f),
                            )
                        }
                        Text(
                            android.text.format.DateUtils
                                .getRelativeTimeSpanString(run.startedAt)
                                .toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (run.outcome) {
                                com.aura.health.WorkerRunEntity.OUTCOME_FAILED -> AuraThemeTokens.colors.error
                                else -> AuraThemeTokens.colors.textPrimary.copy(alpha = 0.62f)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(AuraSpacing.sm))
            Text(
                "Switches",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            for (switch in health.switches) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = AuraSpacing.tiny)) {
                    Column(Modifier.weight(1f)) {
                        Text(switch.name, style = MaterialTheme.typography.bodySmall)
                        if (switch.note.isNotBlank() && !switch.on) {
                            Text(
                                switch.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = AuraThemeTokens.colors.error,
                            )
                        }
                    }
                    Text(
                        if (switch.on) "on" else "off",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (switch.on) {
                            AuraThemeTokens.colors.textPrimary.copy(alpha = 0.62f)
                        } else {
                            AuraThemeTokens.colors.error
                        },
                    )
                }
            }

            Spacer(Modifier.height(AuraSpacing.sm))
            Text(
                "Knowledge graph: ${health.graphNodes} nodes, ${health.graphEdges} connections",
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.62f),
            )
        }
    }
}
