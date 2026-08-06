package com.aura.ui.screens

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.tasks.ReminderEntity
import com.aura.tasks.TaskEntity
import com.aura.ui.components.AuraScreenHeader
import com.aura.ui.viewmodel.TasksViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.components.SwipeToDeleteContainer
import com.aura.ui.theme.AuraSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@Composable
fun TasksScreen(
    viewModel: TasksViewModel = hiltViewModel(),
    onOpenSchedule: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showAddReminder by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<ReminderEntity?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add task")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AuraSpacing.md)
                .padding(padding),
        ) {
            val doneCount = state.tasks.count { it.status == "done" }
            AuraScreenHeader(
                title = "Tasks",
                subtitle = "${state.tasks.size} task${if (state.tasks.size == 1) "" else "s"}",
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xxs)) {
                        TextButton(onClick = onOpenSchedule) { Text("Schedule") }
                        if (doneCount > 0 && state.statusFilter != "pending") {
                            TextButton(onClick = { showClearConfirm = true }) {
                                Text("Clear $doneCount done", color = AuraThemeTokens.colors.error)
                            }
                        }
                    }
                },
            )

            // Search bar
            Spacer(modifier = Modifier.height(AuraSpacing.xs))
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tasks-search"),
                placeholder = { Text(stringResource(R.string.search_tasks_by_title_description_or)) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(AuraSpacing.xs))

            // Status filter chips
            Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.small)) {
                AssistChip(
                    onClick = { viewModel.setStatusFilter("all") },
                    label = { Text(stringResource(R.string.all)) },
                    colors = if (state.statusFilter == "all")
                        AssistChipDefaults.assistChipColors(
                        containerColor = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.18f),
                        labelColor = AuraThemeTokens.colors.assistantAccent,
                    )
                    else AssistChipDefaults.assistChipColors(),
                )
                AssistChip(
                    onClick = { viewModel.setStatusFilter("pending") },
                    label = { Text(stringResource(R.string.pending)) },
                    colors = if (state.statusFilter == "pending")
                        AssistChipDefaults.assistChipColors(
                        containerColor = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.18f),
                        labelColor = AuraThemeTokens.colors.assistantAccent,
                    )
                    else AssistChipDefaults.assistChipColors(),
                )
                AssistChip(
                    onClick = { viewModel.setStatusFilter("done") },
                    label = { Text(stringResource(R.string.done)) },
                    colors = if (state.statusFilter == "done")
                        AssistChipDefaults.assistChipColors(
                        containerColor = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.18f),
                        labelColor = AuraThemeTokens.colors.assistantAccent,
                    )
                    else AssistChipDefaults.assistChipColors(),
                )
            }
            Spacer(modifier = Modifier.height(AuraSpacing.xs))

            if (state.loading) {
                TasksSkeletonLoading()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = AuraSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                ) {
                    if (state.reminders.isNotEmpty() && state.statusFilter != "done") {
                        item { RemindersHeader(onAddReminder = { showAddReminder = true }) }
                        items(state.reminders, key = { "reminder-${it.id}" }) { reminder ->
                            ReminderRow(
                                reminder = reminder,
                                onEdit = { editingReminder = reminder },
                                onCancel = { viewModel.cancelReminder(reminder.id) },
                            )
                        }
                        item { Spacer(Modifier.height(AuraSpacing.xs)); HorizontalDivider(); Spacer(Modifier.height(AuraSpacing.xs)) }
                    } else if (state.statusFilter != "done") {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xxs),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.upcoming_reminders), style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = { showAddReminder = true }) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(AuraSpacing.xl2))
                                    Spacer(Modifier.width(AuraSpacing.xxs))
                                    Text(stringResource(R.string.add))
                                }
                            }
                        }
                    }

                    if (state.tasks.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = AuraSpacing.xl), contentAlignment = Alignment.Center) {
                                Text(
                                    when {
                                        state.searchQuery.isNotBlank() -> "No tasks match \"${state.searchQuery}\""
                                        state.statusFilter == "done" -> "No completed tasks"
                                        state.statusFilter == "pending" -> "No pending tasks"
                                        else -> "No tasks yet"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
                                )
                            }
                        }
                    } else {
                        items(state.tasks, key = { it.id }) { task ->
                            SwipeToDeleteContainer(onDelete = { viewModel.deleteTask(task.id) }) {
                                TaskRow(
                                    task = task,
                                    onDelete = { viewModel.deleteTask(task.id) },
                                    onDone = { viewModel.markDone(task.id) },
                                    onReopen = { viewModel.reopenTask(task.id) },
                                    onEdit = { editingTask = task },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddTaskDialog(
            onDismiss = { showAdd = false },
            onAdd = { title, description, dueAt, priority, tags ->
                viewModel.addTask(title, description, dueAt, priority, tags)
                showAdd = false
            },
        )
    }

    editingTask?.let { task ->
        EditTaskDialog(
            task = task,
            onDismiss = { editingTask = null },
            onSave = { title, description, dueAt, priority, tags ->
                viewModel.updateTask(task.id, title, description, dueAt, priority, tags)
                editingTask = null
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_completed_tasks)) },
            text = { Text(stringResource(R.string.this_will_permanently_delete_all_completed)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearCompleted()
                }) { Text(stringResource(R.string.delete), color = AuraThemeTokens.colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showAddReminder) {
        ReminderEditorDialog(
            title = "Add reminder",
            confirmLabel = "Add",
            onDismiss = { showAddReminder = false },
            onConfirm = { message, triggerAt, recurrence ->
                viewModel.createReminder(message, triggerAt, recurrence)
                showAddReminder = false
            },
        )
    }

    editingReminder?.let { reminder ->
        ReminderEditorDialog(
            title = "Edit reminder",
            confirmLabel = "Save",
            initialMessage = reminder.message,
            initialTriggerAt = reminder.triggerAt,
            initialRecurrence = reminder.recurrence,
            onDismiss = { editingReminder = null },
            onConfirm = { message, triggerAt, recurrence ->
                viewModel.updateReminder(reminder.id, message, triggerAt, recurrence)
                editingReminder = null
            },
        )
    }
}

// ── Skeleton Loading ─────────────────────────────────────────────────────────

/**
 * Skeleton loading placeholder for the tasks list. Shows 5 card-shaped
 * rectangles with a subtle pulse animation that mirrors the real TaskRow
 * layout — the header, count text, and filter chips remain visible
 * throughout so the transition to full content feels natural.
 */
@Composable
private fun TasksSkeletonLoading() {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900)),
        label = "pulse-alpha",
    )

    LazyColumn(
        contentPadding = PaddingValues(vertical = AuraSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
    ) {
        items(5) {
            SkeletonTaskCard(alpha = pulseAlpha)
        }
    }
}

@Composable
private fun SkeletonTaskCard(alpha: Float) {
    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AuraSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Title line
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(AuraSpacing.md)
                        .background(
                            color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.4f),
                            shape = RoundedCornerShape(AuraSpacing.xxs),
                        ),
                )
                Spacer(Modifier.height(AuraSpacing.xs))
                // Description line
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(AuraSpacing.sm)
                        .background(
                            color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.25f),
                            shape = RoundedCornerShape(AuraSpacing.xxs),
                        ),
                )
                Spacer(Modifier.height(AuraSpacing.small))
                // Meta line (due date + priority chip)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(AuraSpacing.medium)
                            .background(
                                color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.2f),
                                shape = RoundedCornerShape(AuraSpacing.xxs),
                            ),
                    )
                    Spacer(Modifier.width(AuraSpacing.xs))
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = AuraSpacing.xxl2)
                            .background(
                                color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.2f),
                                shape = RoundedCornerShape(AuraSpacing.medium),
                            ),
                    )
                }
            }
            Spacer(Modifier.width(AuraSpacing.xs))
            // Action button placeholder
            Box(
                modifier = Modifier
                    .size(width = AuraSpacing.xxl, height = 28.dp)
                    .background(
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.2f),
                        shape = RoundedCornerShape(AuraSpacing.small),
                    ),
            )
        }
    }
}

// ── Reminders Header ─────────────────────────────────────────────────────────

@Composable
private fun RemindersHeader(onAddReminder: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Upcoming reminders",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AuraThemeTokens.colors.actionPrimary,
        )
        TextButton(onClick = onAddReminder) {
            Icon(Icons.Filled.Add, contentDescription = "Add reminder", modifier = Modifier.size(AuraSpacing.xl2))
            Spacer(Modifier.width(AuraSpacing.xxs))
            Text(stringResource(R.string.add))
        }
    }
}

@Composable
private fun ReminderRow(
    reminder: ReminderEntity,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
) {
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
    Surface(
        color = AuraThemeTokens.colors.surface2,
        shape = RoundedCornerShape(AuraSpacing.medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AuraSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    reminder.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(AuraSpacing.tiny))
                Text(
                    buildString {
                        append(fmt.format(Date(reminder.triggerAt)))
                        if (reminder.recurrence != "none") append(" · ${reminder.recurrence}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit reminder")
            }
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Cancel reminder",
                    tint = AuraThemeTokens.colors.error.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskEntity,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    onReopen: () -> Unit,
    onEdit: () -> Unit,
) {
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AuraSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (task.status == "done") androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
                if (task.description.isNotBlank()) {
                    Spacer(Modifier.height(AuraSpacing.tiny))
                    Text(
                        task.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(AuraSpacing.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.dueAt != null) {
                        val due = task.dueAt!!
                        Text(
                            "Due ${fmt.format(Date(due))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.width(AuraSpacing.xs))
                    }
                    PriorityChip(task.priority)
                    if (task.tags.isNotBlank()) {
                        Spacer(Modifier.width(AuraSpacing.xs))
                        Text(
                            task.tags,
                            style = MaterialTheme.typography.labelSmall,
                            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (task.status == "done") {
                TextButton(onClick = onReopen) { Text(stringResource(R.string.reopen)) }
            } else {
                TextButton(onClick = onDone) { Text(stringResource(R.string.done)) }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit",
                    tint = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = AuraThemeTokens.colors.error.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun PriorityChip(priority: Int) {
    val label = when (priority) {
        3 -> "High"
        2 -> "Medium"
        1 -> "Low"
        else -> "None"
    }
    InputChip(
        selected = false,
        onClick = { },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.height(AuraSpacing.lg),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onAdd: (title: String, description: String, dueAt: Long?, priority: Int, tags: String) -> Unit,
) {
    TaskEditFields(
        title = "",
        description = "",
        dueAt = null,
        priority = 0,
        tags = "",
        dialogTitle = "Add task",
        confirmLabel = "Add",
        onDismiss = onDismiss,
        onConfirm = onAdd,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTaskDialog(
    task: TaskEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, dueAt: Long?, priority: Int, tags: String) -> Unit,
) {
    TaskEditFields(
        title = task.title,
        description = task.description,
        dueAt = task.dueAt,
        priority = task.priority,
        tags = task.tags,
        dialogTitle = "Edit task",
        confirmLabel = "Save",
        onDismiss = onDismiss,
        onConfirm = onSave,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditFields(
    title: String,
    description: String,
    dueAt: Long?,
    priority: Int,
    tags: String,
    dialogTitle: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String, dueAt: Long?, priority: Int, tags: String) -> Unit,
) {
    var titleState by remember { mutableStateOf(title) }
    var descriptionState by remember { mutableStateOf(description) }
    var priorityState by remember { mutableIntStateOf(priority) }
    var tagsState by remember { mutableStateOf(tags) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var selectedDateMs by remember { mutableStateOf(dueAt) }

    val calendar = remember { Calendar.getInstance() }
    if (selectedDateMs != null) calendar.timeInMillis = selectedDateMs!!

    fun dueAtMs(): Long? {
        if (selectedDateMs == null) return null
        val c = Calendar.getInstance().apply { timeInMillis = selectedDateMs!! }
        c.set(Calendar.HOUR_OF_DAY, calendar.get(Calendar.HOUR_OF_DAY))
        c.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE))
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    val dateText = selectedDateMs?.let { ms ->
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(dueAtMs() ?: ms))
    } ?: "No due date"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = titleState,
                    onValueChange = { titleState = it },
                    label = { Text(stringResource(R.string.title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AuraSpacing.xs))
                OutlinedTextField(
                    value = descriptionState,
                    onValueChange = { descriptionState = it },
                    label = { Text(stringResource(R.string.description)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AuraSpacing.xs))
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(dateText)
                }
                Spacer(Modifier.height(AuraSpacing.xs))
                Text(stringResource(R.string.priority), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                    PriorityOption(label = "None", selected = priorityState == 0) { priorityState = 0 }
                    PriorityOption(label = "Low", selected = priorityState == 1) { priorityState = 1 }
                    PriorityOption(label = "Medium", selected = priorityState == 2) { priorityState = 2 }
                    PriorityOption(label = "High", selected = priorityState == 3) { priorityState = 3 }
                }
                Spacer(Modifier.height(AuraSpacing.xs))
                OutlinedTextField(
                    value = tagsState,
                    onValueChange = { tagsState = it },
                    label = { Text(stringResource(R.string.tags_comma_separated_2)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(titleState, descriptionState, dueAtMs(), priorityState, tagsState)
                },
                enabled = titleState.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )

    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialDisplayMode = DisplayMode.Picker)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { selectedDateMs = it }
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState()
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.due_time)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    calendar.set(Calendar.HOUR_OF_DAY, timeState.hour)
                    calendar.set(Calendar.MINUTE, timeState.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PriorityOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AuraThemeTokens.colors.actionPrimary else AuraThemeTokens.colors.surface1,
        ),
    ) {
        Box(Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.small)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReminderDialog(
    onDismiss: () -> Unit,
    onAdd: (message: String, triggerAt: Long) -> Unit,
) {
    var message by remember { mutableStateOf("") }
    var showTimePicker by remember { mutableStateOf(false) }
    var selectedHour by remember { mutableIntStateOf(-1) }
    var selectedMinute by remember { mutableIntStateOf(-1) }
    var selectedDateMillis by remember { mutableStateOf(0L) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_reminder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.reminder_message)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (selectedDateMillis > 0)
                                SimpleDateFormat("MMM d", Locale.US).format(Date(selectedDateMillis))
                            else "Date"
                        )
                    }
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (selectedHour >= 0) String.format("%02d:%02d", selectedHour, selectedMinute)
                            else "Time"
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (message.isBlank() || selectedHour < 0 || selectedDateMillis == 0L) return@TextButton
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = selectedDateMillis
                        set(Calendar.HOUR_OF_DAY, selectedHour)
                        set(Calendar.MINUTE, selectedMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onAdd(message.trim(), cal.timeInMillis)
                },
                enabled = message.isNotBlank() && selectedHour >= 0 && selectedDateMillis > 0L,
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )

    if (showDatePicker) {
        val dpState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { selectedDateMillis = it }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) { DatePicker(state = dpState) }
    }

    if (showTimePicker) {
        val tpState = rememberTimePickerState()
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.select_time)) },
            text = { TimePicker(state = tpState) },
            confirmButton = {
                TextButton(onClick = {
                    selectedHour = tpState.hour
                    selectedMinute = tpState.minute
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}