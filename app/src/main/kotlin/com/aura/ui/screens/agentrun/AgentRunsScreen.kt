package com.aura.ui.screens.agentrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.agentrun.AgentRunEntity
import com.aura.agentrun.ApprovalRequestEntity
import com.aura.agentrun.StepEntity
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.AgentRunsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentRunsScreen(
    runId: String? = null,
    onBack: () -> Unit,
    viewModel: AgentRunsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(runId) {
        runId?.let { viewModel.selectRun(it) } ?: viewModel.clearSelection()
    }

    AuraScreenShell(
        title = "Agent runs",
        subtitle = "Durable runs, approvals, and progress",
        action = {
            if (state.selectedRun != null) {
                IconButton(onClick = viewModel::clearSelection) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to list")
                }
            }
        },
    ) { padding ->
        if (state.selectedRun == null) {
            AgentRunsList(
                runs = state.runs,
                loading = state.loading,
                error = state.error,
                onSelect = viewModel::selectRun,
                onRefresh = viewModel::loadRuns,
                modifier = Modifier.padding(padding),
            )
        } else {
            AgentRunDetail(
                run = state.selectedRun!!,
                steps = state.steps,
                approvals = state.approvals,
                onBack = viewModel::clearSelection,
                onApprove = viewModel::approve,
                onDeny = viewModel::deny,
                onResume = viewModel::resume,
                onCancel = viewModel::cancel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun AgentRunsList(
    runs: List<AgentRunEntity>,
    loading: Boolean,
    error: String?,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AuraSpacing.md)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            androidx.compose.material3.IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = AuraThemeTokens.colors.textSecondary,
                )
            }
        }
        if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        if (error != null) {
            Surface(color = AuraThemeTokens.colors.error) {
                Row(Modifier.padding(AuraSpacing.md)) {
                    Text("Error: $error", modifier = Modifier.weight(1f), color = AuraThemeTokens.colors.textPrimary)
                    TextButton(onClick = onRefresh) { Text("Retry") }
                }
            }
        }
        if (runs.isEmpty() && !loading) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No agent runs yet.", style = MaterialTheme.typography.titleMedium)
                Text("Run a hand or production pipeline to create one.", style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textSecondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = AuraSpacing.md)) {
                items(runs, key = { it.id }) { run ->
                    AgentRunRow(run, onClick = { onSelect(run.id) })
                }
            }
        }
    }
}

@Composable
private fun AgentRunRow(run: AgentRunEntity, onClick: () -> Unit) {
    val colors = AuraThemeTokens.colors
    Surface(
        color = colors.surface1,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(Modifier.padding(AuraSpacing.md), verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(run.runDescription(), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                StatusChip(run.status)
            }
            Text("${formatTime(run.startedAt)} · trigger: ${run.triggerType}", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = when (status.uppercase()) {
        "RUNNING" -> AuraThemeTokens.colors.actionPrimary
        "COMPLETED" -> AuraThemeTokens.colors.success
        "CANCELLED" -> AuraThemeTokens.colors.warning
        "FAILED" -> AuraThemeTokens.colors.error
        else -> AuraThemeTokens.colors.textSecondary
    }
    Text(status, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun AgentRunDetail(
    run: AgentRunEntity,
    steps: List<StepEntity>,
    approvals: List<ApprovalRequestEntity>,
    onBack: () -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AuraSpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(run.runDescription(), fontWeight = FontWeight.SemiBold)
                Text("${run.status} · ${formatTime(run.startedAt)}", style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textSecondary)
            }
            StatusChip(run.status)
        }
        if (approvals.isNotEmpty()) {
            Text("Approvals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            approvals.forEach { approval ->
                ApprovalCard(approval, onApprove = { onApprove(approval.id) }, onDeny = { onDeny(approval.id) })
            }
        }
        if (steps.isNotEmpty()) {
            Text("Steps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            steps.forEach { step ->
                StepRow(step)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            if (run.status == "RUNNING" || run.status == "PAUSED") {
                OutlinedButton(onClick = { onCancel(run.id) }) { Text("Cancel") }
                Button(onClick = { onResume(run.id) }) { Text("Resume") }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approval: ApprovalRequestEntity,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val colors = AuraThemeTokens.colors
    Surface(color = colors.surface1, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AuraSpacing.md), verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm)) {
            Text(approval.rationale, fontWeight = FontWeight.SemiBold)
            Text("requested by ${approval.toolName}", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                OutlinedButton(onClick = onDeny) { Text("Deny") }
                Button(onClick = onApprove) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun StepRow(step: StepEntity) {
    val colors = AuraThemeTokens.colors
    Surface(color = colors.surface0, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AuraSpacing.md), verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(step.toolName, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                StatusChip(step.status)
            }
            if (step.result.isNotBlank()) {
                Text(step.result, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 4)
            }
        }
    }
}

private fun AgentRunEntity.runDescription(): String =
    errorMessage.takeIf { it.isNotBlank() } ?: triggerPayload.takeIf { it.isNotBlank() } ?: "Run $id"

private fun formatTime(ts: Long): String {
    return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))
}
