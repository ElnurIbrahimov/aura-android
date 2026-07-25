package com.aura.ui.screens.skills

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.skills.Skill
import com.aura.ui.viewmodel.SkillsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onBack: () -> Unit,
    vm: SkillsViewModel = hiltViewModel(),
) {
    val skills by vm.skills.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val selected = skills.firstOrNull { it.id == selectedId }

    var showNew by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skills)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNew = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add skill")
            }
        },
    ) { padding ->
        if (skills.isEmpty()) {
            EmptyState(padding = padding)
        } else if (selected == null) {
            SkillList(
                padding = padding,
                skills = skills,
                onClick = { vm.select(it.id) },
            )
        } else {
            SkillDetail(
                padding = padding,
                skill = selected,
                onBack = { vm.select(null) },
                onSave = { vm.update(it) },
                onDelete = { confirmDeleteId = it.id },
            )
        }
    }

    if (showNew) {
        NewSkillDialog(
            onDismiss = { showNew = false },
            onCreate = { name, description, body ->
                vm.add(name, description, body)
                showNew = false
            },
        )
    }

    val pendingDelete = confirmDeleteId
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text(stringResource(R.string.delete_skill)) },
            text = { Text(stringResource(R.string.this_removes_the_skill_and_its)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(pendingDelete)
                    confirmDeleteId = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.no_skills_yet), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Skills are reusable instruction modules. The agent can invoke them by name via the use_skill tool.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SkillList(
    padding: PaddingValues,
    skills: List<Skill>,
    onClick: (Skill) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = skills, key = { it.id }) { skill ->
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        skill.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (skill.description.isNotBlank()) {
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        skill.preview(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                        maxLines = 2,
                    )
                    FilledTonalButton(
                        onClick = { onClick(skill) },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .align(Alignment.End),
                    ) { Text(stringResource(R.string.edit)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillDetail(
    padding: PaddingValues,
    skill: Skill,
    onBack: () -> Unit,
    onSave: (Skill) -> Unit,
    onDelete: (Skill) -> Unit,
) {
    var name by remember(skill.id) { mutableStateOf(skill.name) }
    var description by remember(skill.id) { mutableStateOf(skill.description) }
    var body by remember(skill.id) { mutableStateOf(skill.body) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            label = { Text(stringResource(R.string.name)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(240) },
            label = { Text(stringResource(R.string.description_optional)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text(stringResource(R.string.body_markdown)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
        )
        HorizontalDivider()
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = {
                    val updated = skill.renamed(name).withDescription(description).withBody(body)
                    onSave(updated)
                    onBack()
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.save)) }
            TextButton(
                onClick = { onDelete(skill) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun NewSkillDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, body: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_skill)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it.take(240) }, label = { Text(stringResource(R.string.description_optional)) }, singleLine = true)
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text(stringResource(R.string.body_markdown)) }, minLines = 6, maxLines = 12)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name, description, body) },
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
