package com.aura.ui.screens

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.hands.Hand
import com.aura.hands.HandRun
import com.aura.hands.HandRunStatus
import com.aura.hands.HandScheduleType
import com.aura.ui.components.AuraScreenHeader
import com.aura.ui.viewmodel.HandsViewModel
import java.text.DateFormat
import java.time.DayOfWeek
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.components.SwipeToDeleteContainer
import com.aura.ui.theme.AuraSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
@Composable
fun HandsScreen(
    onBack: () -> Unit = {},
    onRecordHand: () -> Unit = {},
    viewModel: HandsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visibleHands by viewModel.filteredHands.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    var editingHand by remember { mutableStateOf<Hand?>(null) }
    var showNewHand by remember { mutableStateOf(false) }
    var runHand by remember { mutableStateOf<Hand?>(null) }
    var deleteHand by remember { mutableStateOf<Hand?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var permissionRun by remember { mutableStateOf<HandRun?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val run = permissionRun
        permissionRun = null
        if (granted && run != null) viewModel.resumeRun(run)
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selectedTab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showNewHand = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_hand)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = AuraSpacing.xxl2)) {
            AuraScreenHeader(
                title = "Hands",
                subtitle = "Automations with explicit inputs, gates, and history",
            )
            // The other way in: show Aura the task once instead of writing the steps out.
            TextButton(onClick = onRecordHand) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(AuraSpacing.xs))
                Text(stringResource(R.string.record_a_hand))
            }
            // Search bar
            androidx.compose.material3.OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(top = AuraSpacing.xs),
                placeholder = { Text(stringResource(R.string.search_hands_by_name_or_trigger)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
            )
            Spacer(Modifier.height(AuraSpacing.xs))
            HandsStatusFilterChips(
                selected = state.statusFilter,
                onSelect = { viewModel.setStatusFilter(it) },
            )
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Automations  ${state.hands.size}") },
                    icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Run history  ${state.runs.size}") },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                )
            }
            Spacer(Modifier.height(AuraSpacing.medium))

            if (selectedTab == 0) {
                // `filteredHands` from the ViewModel, not a second filter
                // written here. The ViewModel's version applies the status
                // chips *and* the search box; this copy applied search only,
                // so tapping "Enabled" or "Disabled" highlighted the chip and
                // changed nothing. `filteredHands` had no consumer anywhere in
                // the repo — the filter worked, on a screen that never called
                // it.
                when {
                    state.loading -> HandsSkeletonLoading()
                    visibleHands.isEmpty() -> HandsEmptyState()
                    else -> LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(AuraSpacing.medium),
                    ) {
                        items(visibleHands, key = { it.id }) { hand ->
                            SwipeToDeleteContainer(onDelete = { deleteHand = hand }) {
                                HandCard(
                                    hand = hand,
                                    lastRun = state.runs.firstOrNull { it.handId == hand.id },
                                    isRunning = state.running == hand.name,
                                    onRun = { runHand = hand },
                                    onToggle = { viewModel.toggle(hand) },
                                    onEdit = { editingHand = hand },
                                    onDelete = { deleteHand = hand },
                                )
                            }
                        }
                    }
                }
            } else {
                RunHistory(
                    runs = state.runs,
                    onClear = { confirmClearHistory = true },
                    onApprove = viewModel::resumeRun,
                    onGrantPermission = { run ->
                        val permission = viewModel.pendingPermission(run)
                        if (permission == null) {
                            viewModel.resumeRun(run)
                        } else {
                            permissionRun = run
                            permissionLauncher.launch(permission)
                        }
                    },
                )
            }
        }
    }

    if (showNewHand) {
        HandEditorDialog(
            initial = null,
            toolDefinitions = viewModel.toolRegistry.definitions(),
            onDismiss = { showNewHand = false },
            onSave = { draft ->
                viewModel.save(null, draft)
                showNewHand = false
            },
        )
    }
    editingHand?.let { hand ->
        HandEditorDialog(
            initial = hand,
            toolDefinitions = viewModel.toolRegistry.definitions(),
            onDismiss = { editingHand = null },
            onSave = { draft ->
                viewModel.save(hand, draft)
                editingHand = null
            },
        )
    }
    runHand?.let { hand ->
        RunHandDialog(
            hand = hand,
            onDismiss = { runHand = null },
            onRun = { variables ->
                viewModel.runHand(hand, variables)
                runHand = null
            },
        )
    }
    deleteHand?.let { hand ->
        AlertDialog(
            onDismissRequest = { deleteHand = null },
            title = { Text("Delete ${hand.name}?") },
            text = { Text(stringResource(R.string.the_hand_is_removed_its_run)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(hand); deleteHand = null }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteHand = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text(stringResource(R.string.clear_run_history)) },
            text = { Text(stringResource(R.string.this_removes_local_automation_outputs_and)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory(); confirmClearHistory = false }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.lastResult?.let { output ->
        AlertDialog(
            onDismissRequest = viewModel::clearResult,
            title = { Text(stringResource(R.string.hand_finished)) },
            text = { Text(output) },
            confirmButton = { TextButton(onClick = viewModel::clearResult) { Text(stringResource(R.string.done)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandCard(
    hand: Hand,
    lastRun: HandRun?,
    isRunning: Boolean,
    onRun: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = AuraThemeTokens.colors.surface1.copy(alpha = if (hand.enabled) 0.85f else 0.45f),
        shape = RoundedCornerShape(AuraSpacing.xl2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(AuraSpacing.md), verticalArrangement = Arrangement.spacedBy(AuraSpacing.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.14f),
                    shape = CircleShape,
                ) {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = null,
                        tint = AuraThemeTokens.colors.actionPrimary,
                        modifier = Modifier.padding(AuraSpacing.medium).size(AuraSpacing.xxl2),
                    )
                }
                Spacer(Modifier.size(AuraSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(hand.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        hand.triggerPhrase.ifBlank { "Manual or agent-triggered" },
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = hand.enabled, onCheckedChange = { onToggle() })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs), verticalArrangement = Arrangement.spacedBy(AuraSpacing.small)) {
                MetadataPill(Icons.Filled.Schedule, scheduleLabel(hand))
                MetadataPill(null, "${jsonArrayCount(hand.steps)} steps")
                val variableCount = jsonObjectCount(hand.variables)
                if (variableCount > 0) MetadataPill(null, "$variableCount vars")
                val conditionCount = jsonArrayCount(hand.conditions)
                if (conditionCount > 0) MetadataPill(null, "$conditionCount gates")
                if (lastRun != null) {
                    MetadataPill(null, "Last: ${lastRun.status.replace('_', ' ')}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.small), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRun, enabled = hand.enabled && !isRunning, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(if (isRunning) "Running…" else "Run")
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit hand") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete hand", tint = AuraThemeTokens.colors.error)
                }
            }
        }
    }
}

@Composable
private fun MetadataPill(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String) {
    Surface(color = AuraThemeTokens.colors.surface1, shape = RoundedCornerShape(999.dp)) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RunHistory(
    runs: List<HandRun>,
    onClear: () -> Unit,
    onApprove: (HandRun) -> Unit,
    onGrantPermission: (HandRun) -> Unit,
) {
    if (runs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(36.dp), tint = AuraThemeTokens.colors.actionPrimary)
                Spacer(Modifier.height(AuraSpacing.medium))
                Text(stringResource(R.string.no_runs_yet), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.manual_agent_phrase_and_scheduled_runs), style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textPrimary)
            }
        }
        return
    }
    var filter by remember { mutableStateOf("all") }
    val filteredRuns = when (filter) {
        "success" -> runs.filter { it.status == HandRunStatus.SUCCESS.value }
        "failed" -> runs.filter {
            it.status in setOf(
                HandRunStatus.FAILED.value,
                HandRunStatus.NEEDS_PERMISSION.value,
                HandRunStatus.NEEDS_APPROVAL.value,
            )
        }
        else -> runs
    }
    Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AuraSpacing.small)) {
            listOf("all" to "All", "success" to "Success", "failed" to "Failed").forEach { (value, label) ->
                FilterChip(
                    selected = filter == value,
                    onClick = { filter = value },
                    label = { Text(label) },
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
        }
        if (filteredRuns.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No $filter runs", color = AuraThemeTokens.colors.textPrimary)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                items(filteredRuns, key = { it.id }) { run ->
                    RunHistoryCard(
                        run = run,
                        onApprove = { onApprove(run) },
                        onGrantPermission = { onGrantPermission(run) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RunHistoryCard(
    run: HandRun,
    onApprove: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    var expanded by remember(run.id) { mutableStateOf(false) }
    val color = statusColor(run.status)
    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.md),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(AuraSpacing.large), verticalArrangement = Arrangement.spacedBy(AuraSpacing.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(color, CircleShape))
                Spacer(Modifier.size(9.dp))
                Text(run.handName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(run.status.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = color)
            }
            Text(
                "${run.trigger.replaceFirstChar { it.uppercase() }} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.startedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary,
            )
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.small)) {
                    HorizontalDivider()
                    if (run.failedStep != null) Text("Stopped at step ${run.failedStep}", color = AuraThemeTokens.colors.error)
                    Text(run.output.ifBlank { "No output recorded" }, style = MaterialTheme.typography.bodySmall)
                    if (run.variablesJson != "{}") {
                        Text("Inputs: ${run.variablesJson}", style = MaterialTheme.typography.labelSmall, color = AuraThemeTokens.colors.textPrimary)
                    }
                    when (run.status) {
                        HandRunStatus.NEEDS_APPROVAL.value -> Button(
                            onClick = onApprove,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.approve_resume))
                        }
                        HandRunStatus.NEEDS_PERMISSION.value -> Button(
                            onClick = onGrantPermission,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.grant_permission_resume))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunHandDialog(hand: Hand, onDismiss: () -> Unit, onRun: (Map<String, String>) -> Unit) {
    val defaults = remember(hand.id, hand.variables, hand.steps) { runtimeVariableInputs(hand) }
    var values by remember(hand.id) { mutableStateOf(defaults) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run ${hand.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                if (defaults.isEmpty()) {
                    Text(stringResource(R.string.this_hand_has_no_runtime_variables))
                } else {
                    Text(stringResource(R.string.override_inputs_for_this_run_only), style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textPrimary)
                    defaults.keys.forEach { key ->
                        OutlinedTextField(
                            value = values[key].orEmpty(),
                            onValueChange = { values = values + (key to it) },
                            label = { Text(key) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRun(values) }) { Text(stringResource(R.string.run)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun HandsSkeletonLoading() {
    val transition = rememberInfiniteTransition(label = "hands-skeleton")
    val alpha by transition.animateFloat(0.25f, 0.60f, infiniteRepeatable(tween(850)), label = "pulse")
    Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.medium), modifier = Modifier.fillMaxWidth()) {
        repeat(3) {
            Box(Modifier.fillMaxWidth().height(126.dp).background(AuraThemeTokens.colors.surface1.copy(alpha = alpha), RoundedCornerShape(AuraSpacing.xl2)))
        }
    }
}

@Composable
private fun HandsEmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
            Surface(color = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.14f), shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = AuraThemeTokens.colors.actionPrimary, modifier = Modifier.padding(AuraSpacing.xl2).size(AuraSpacing.xl))
            }
            Spacer(Modifier.height(AuraSpacing.large))
            Text(stringResource(R.string.build_your_first_hand), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(
                "Chain tools, name the inputs, decide when it may run, and keep every result inspectable.",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    HandRunStatus.SUCCESS.value -> AuraThemeTokens.colors.success
    HandRunStatus.RUNNING.value -> AuraThemeTokens.colors.actionPrimary
    HandRunStatus.SKIPPED.value -> AuraThemeTokens.colors.warning
    HandRunStatus.NEEDS_PERMISSION.value, HandRunStatus.NEEDS_APPROVAL.value -> AuraThemeTokens.colors.warning
    else -> AuraThemeTokens.colors.error
}

private fun scheduleLabel(hand: Hand): String = when (HandScheduleType.from(hand.scheduleType)) {
    HandScheduleType.NONE -> "Manual"
    HandScheduleType.DAILY -> "Daily %02d:%02d".format(hand.scheduleHour, hand.scheduleMinute)
    HandScheduleType.WEEKDAYS -> "Weekdays %02d:%02d".format(hand.scheduleHour, hand.scheduleMinute)
    HandScheduleType.WEEKLY -> {
        val day = runCatching { DayOfWeek.of(hand.scheduleDayOfWeek).name.take(3).lowercase().replaceFirstChar { it.uppercase() } }.onFailure { Log.w("HandsScreen", "runCatching failed: ${it.message}", it) }.getOrDefault("Mon")
        "$day %02d:%02d".format(hand.scheduleHour, hand.scheduleMinute)
    }
}

private fun jsonArrayCount(raw: String): Int = runCatching {
    (Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray)?.size ?: 0
}.onFailure { Log.w("HandsScreen", "runCatching failed: ${it.message}", it) }.getOrDefault(0)

private fun jsonObjectCount(raw: String): Int = runCatching {
    Json.parseToJsonElement(raw).jsonObject.size
}.onFailure { Log.w("HandsScreen", "runCatching failed: ${it.message}", it) }.getOrDefault(0)

internal fun runtimeVariableInputs(hand: Hand): Map<String, String> {
    val inputs = LinkedHashMap(parseVariableDefaults(hand.variables))
    Regex("""\{\{\s*([A-Za-z][A-Za-z0-9_.-]*)\s*\}\}""")
        .findAll(hand.steps)
        .map { it.groupValues[1] }
        .forEach { inputs.putIfAbsent(it, "") }
    return inputs
}

private fun parseVariableDefaults(raw: String): Map<String, String> = runCatching {
    Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
}.onFailure { Log.w("HandsScreen", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyMap())
@Composable
private fun HandsStatusFilterChips(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val filters = listOf(
        "all" to "All",
        "enabled" to "Enabled",
        "disabled" to "Disabled",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.sm),
    ) {
        filters.forEach { (key, label) ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Text(label) },
            )
        }
    }
}
