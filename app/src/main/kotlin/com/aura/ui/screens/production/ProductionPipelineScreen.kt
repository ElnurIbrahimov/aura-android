package com.aura.ui.screens.production
import com.aura.ui.theme.AuraThemeTokens

import com.aura.R
import androidx.compose.ui.res.stringResource
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
import com.aura.ui.components.AuraEmptyState
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.viewmodel.ProductionPipelineViewModel
import com.aura.ui.theme.AuraSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionPipelineScreen(
    onOpenAgentRuns: () -> Unit = {},
    onOpenCreative: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ProductionPipelineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    AuraScreenShell(
        title = "Production",
        subtitle = "Film & story pipelines",
        modifier = modifier,
    ) {
        // Pipelines run on a Creative project. With none, the pickers are
        // empty and Schedule is permanently disabled — so guide the user to
        // Creative Studio (which is also where the Creative Council lives)
        // instead of showing an inert form.
        if (state.projects.isEmpty()) {
            AuraEmptyState(
                icon = Icons.Filled.Movie,
                title = "No projects yet",
                message = "Production pipelines run on a Creative project. Create one " +
                    "in Creative Studio first — that's also where the Creative Council lives.",
                actionLabel = "Open Creative Studio",
                onAction = onOpenCreative,
                modifier = Modifier.fillMaxSize(),
            )
            return@AuraScreenShell
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
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
                label = { Text(stringResource(R.string.project)) },
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
                label = { Text(stringResource(R.string.pipeline)) },
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
            label = { Text(stringResource(R.string.brief_prompt)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        Button(
            onClick = { viewModel.schedule() },
            enabled = state.selectedProjectId != null && state.selectedPipeline != null && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.width(AuraSpacing.lg).height(AuraSpacing.lg))
                Spacer(modifier = Modifier.width(AuraSpacing.xs))
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(AuraSpacing.xs))
            }
            Text(if (state.busy) "Scheduling..." else "Schedule pipeline")
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = AuraThemeTokens.colors.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { viewModel.dismissResult() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dismiss))
            }
        }

        state.scheduledRunId?.let { runId ->
            Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = AuraThemeTokens.colors.actionPrimary)
                Text("Scheduled run: $runId")
            }
            Button(onClick = onOpenAgentRuns, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Movie, contentDescription = null)
                Spacer(modifier = Modifier.width(AuraSpacing.xs))
                Text(stringResource(R.string.view_in_agent_runs))
            }
            OutlinedButton(onClick = { viewModel.dismissResult() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.schedule_another))
            }
        }
        }
    }
}
