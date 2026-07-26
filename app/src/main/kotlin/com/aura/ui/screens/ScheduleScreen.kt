@file:OptIn(ExperimentalMaterial3Api::class)

package com.aura.ui.screens.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.tasks.TaskEntity
import com.aura.tasks.ReminderEntity
import com.aura.ui.viewmodel.ScheduleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedule", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                listOf("Tasks", "Reminders").forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
                }
            }
            when (selectedTab) {
                0 -> TaskList(
                    tasks = uiState.tasks,
                    onToggle = { viewModel.toggleTask(it) },
                    onDelete = { viewModel.deleteTask(it) },
                )
                1 -> ReminderList(
                    reminders = uiState.reminders,
                    onCancel = { viewModel.cancelReminder(it) },
                    onDelete = { viewModel.deleteReminder(it) },
                )
            }
        }
    }
}

@Composable
private fun TaskList(
    tasks: List<TaskEntity>,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn {
        items(tasks, key = { it.id }) { task ->
            var showDelete by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(task.id) }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        task.dueAt?.let { SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(it)) } ?: "No due date",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (task.status == "done") "✓ Done" else "Pending",
                    color = if (task.status == "done") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            if (showDelete) {
                AlertDialog(
                    onDismissRequest = { showDelete = false },
                    confirmButton = { TextButton(onClick = { onDelete(task.id); showDelete = false }) { Text("Delete") } },
                    dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
                    title = { Text("Delete task?") },
                )
            }
        }
    }
}

@Composable
private fun ReminderList(
    reminders: List<ReminderEntity>,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn {
        items(reminders, key = { it.id }) { reminder ->
            var showMenu by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(reminder.message, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(reminder.triggerAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(reminder.recurrence, style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = { showMenu = true }) { Text("…") }
            }
            HorizontalDivider()
            if (showMenu) {
                AlertDialog(
                    onDismissRequest = { showMenu = false },
                    confirmButton = { TextButton(onClick = { onCancel(reminder.id); showMenu = false }) { Text("Cancel") } },
                    dismissButton = { TextButton(onClick = { onDelete(reminder.id); showMenu = false }) { Text("Delete") } },
                    title = { Text(reminder.message) },
                )
            }
        }
    }
}
