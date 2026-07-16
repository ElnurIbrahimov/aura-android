package com.aura.ui.screens.production

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.creative.ProductionPipelineEngine
import com.aura.ui.viewmodel.ProductionPipelineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionPipelineScreen(
    onOpenAgentRuns: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ProductionPipelineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Production Pipeline",
            style = MaterialTheme.typography.headlineSmall,
        )

        // Project picker
        var projectExpanded by remember { mutableStateOf(false) }
        val selectedProject = state.projects.find { it.id == state.selectedProjectId }
        ExposedDropdownMenuBox(
            expanded = projectExpanded,
            onExpandedChange = { projectExpanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selectedProject?.name ?: "Select a project",
                onValueChange = {},
                readOnly = true,
                label = { Text("Project") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = projectExpanded,
                onDismissRequest = { projectExpanded = false },
            ) {
                state.projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name) },
                        onClick = {
                            viewModel.selectProject(project.id)
                            projectExpanded = false
                        },
                    )
                }
            }
        }

        // Pipeline picker
        var pipelineExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = pipelineExpanded,
            onExpandedChange = { pipelineExpanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = state.selectedPipeline?.displayName ?: "Select pipeline",
                onValueChange = {},
                readOnly = true,
                label = { Text("Pipeline") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pipelineExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = pipelineExpanded,
                onDismissRequest = { pipelineExpanded = false },
            ) {
                state.available.forEach { pipeline ->
                    DropdownMenuItem(
                        text = { Text(pipeline.displayName) },
                        onClick = {
                            viewModel.selectPipeline(pipeline)
                            pipelineExpanded = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.brief,
            onValueChange = viewModel::setBrief,
            label = { Text("Brief / prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        Button(
            onClick = { viewModel.schedule() },
            enabled = state.selectedProjectId != null && state.selectedPipeline != null && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (state.busy) "Scheduling..." else "Schedule pipeline")
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { viewModel.dismissResult() }, modifier = Modifier.fillMaxWidth()) {
                Text("Dismiss")
            }
        }

        state.scheduledRunId?.let { runId ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Scheduled run: $runId")
            }
            Button(onClick = onOpenAgentRuns, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Movie, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("View in Agent Runs")
            }
            OutlinedButton(onClick = { viewModel.dismissResult() }, modifier = Modifier.fillMaxWidth()) {
                Text("Schedule another")
            }
        }
    }
}
