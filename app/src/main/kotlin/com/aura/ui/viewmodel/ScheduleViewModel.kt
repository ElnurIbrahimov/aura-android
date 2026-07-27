package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.tasks.ReminderDao
import com.aura.tasks.ReminderEntity
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val taskDao: TaskDao,
    private val reminderDao: ReminderDao,
    private val taskScheduler: com.aura.tasks.TaskScheduler,
) : ViewModel() {

    data class UiState(
        val tasks: List<TaskEntity> = emptyList(),
        val reminders: List<ReminderEntity> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                taskDao.observeAll(),
                reminderDao.observeUpcoming(System.currentTimeMillis()),
            ) { tasks: List<TaskEntity>, reminders: List<ReminderEntity> ->
                UiState(tasks.sortedBy { it.dueAt }, reminders)
            }.collect { _uiState.value = it }
        }
    }

    fun toggleTask(id: String) {
        viewModelScope.launch {
            val existing = taskDao.get(id) ?: return@launch
            val next = if (existing.status == "done") "pending" else "done"
            taskDao.insert(existing.copy(status = next, completedAt = if (next == "done") System.currentTimeMillis() else null))
        }
    }

    fun deleteTask(id: String) {
        viewModelScope.launch {
            taskScheduler.cancel(id)
            taskDao.delete(id)
        }
    }

    fun cancelReminder(id: String) {
        viewModelScope.launch {
            val existing = reminderDao.get(id) ?: return@launch
            reminderDao.insert(existing.copy(status = "cancelled"))
        }
    }

    fun deleteReminder(id: String) {
        viewModelScope.launch { reminderDao.delete(id) }
    }
}
