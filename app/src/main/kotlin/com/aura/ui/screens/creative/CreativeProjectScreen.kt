package com.aura.ui.screens.creative

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.creative.CouncilRole
import com.aura.creative.CreativeMode
import com.aura.creative.CreativeProject
import com.aura.creative.ProseCraftTools
import com.aura.creative.WritingTemplates
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.CreativeStudioUiState
import com.aura.ui.viewmodel.CreativeStudioViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreativeProjectScreen(
    projectId: String,
    onBack: () -> Unit,
    viewModel: CreativeStudioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project = state.selectedProject
    var selectedTab by remember { mutableIntStateOf(0) }
    var showEdit by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) { viewModel.loadProject(projectId) }

    if (state.loading || project == null) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.loading) CircularProgressIndicator() else Text(state.error ?: "Project not found")
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_to_studio)) }
        }
        return
    }

    AuraScreenShell(
        title = project.name,
        subtitle = listOfNotNull(
            project.genre.takeIf(String::isNotBlank),
            project.tone.takeIf(String::isNotBlank),
            WritingTemplates.byId(project.templateId)?.name,
        ).joinToString(" · ").ifBlank { "Open creative project" },
        action = {
            IconButton(onClick = { showEdit = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit project")
            }
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Studio")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                listOf("World", "Write", "Simulate", "Council", "Craft", "Tools").forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) },
                    )
                }
            }
            if (state.error != null || state.message != null) {
                Surface(
                    color = if (state.error != null) AuraThemeTokens.colors.error else AuraThemeTokens.colors.actionPrimary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(AuraSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.error ?: state.message.orEmpty(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::clearNotice) { Text(stringResource(R.string.dismiss)) }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = AuraSpacing.md,
                    bottom = padding.calculateBottomPadding() + AuraSpacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.md),
            ) {
                item {
                    when (selectedTab) {
                        0 -> WorldBibleEditor(project = project, onSave = viewModel::saveWorld)
                        1 -> WritingRoom(state.output, state.generating, state.wordCount, viewModel::generate, viewModel::cancelGeneration)
                        2 -> SimulationRoom(project, state.output, state.generating, viewModel::generate, viewModel::cancelGeneration, viewModel::canonizeSimulation)
                        3 -> CouncilRoom(state.output, state.generating, viewModel::runCouncil, viewModel::cancelGeneration)
                        4 -> CraftRoom(state.output, state.generating, viewModel::applyCraftTool, viewModel::cancelGeneration)
                        else -> ToolsRoom(state, viewModel)
                    }
                }
            }
        }
    }

    if (showEdit) {
        ProjectMetadataDialog(
            project = project,
            onDismiss = { showEdit = false },
            onSave = { name, description, genre, tone, template ->
                showEdit = false
                viewModel.saveMetadata(name, description, genre, tone, template)
            },
        )
    }
}

@Composable
private fun CouncilRoom(
    output: String,
    generating: Boolean,
    onRunCouncil: (String, List<CouncilRole>) -> Unit,
    onCancel: () -> Unit,
) {
    var brief by remember { mutableStateOf("") }
    var selectedRoles by remember { mutableStateOf(CouncilRole.full) }
    Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.md)) {
        Text(stringResource(R.string.creative_council), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Run a multi-role review: producers draft, critics refine, the director synthesizes.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
        OutlinedTextField(
            value = brief,
            onValueChange = { brief = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text(stringResource(R.string.brief)) },
            placeholder = { Text(stringResource(R.string.ask_the_council_to_design_the)) },
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CouncilRole.entries) { role ->
                val included = role in selectedRoles
                FilterChip(
                    selected = included,
                    onClick = {
                        selectedRoles = if (included) selectedRoles - role else selectedRoles + role
                    },
                    label = { Text(role.displayName) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            if (generating) {
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text(stringResource(R.string.stop))
                }
            }
            Button(enabled = brief.isNotBlank() && !generating, onClick = { onRunCouncil(brief, selectedRoles) }) {
                Text(stringResource(R.string.run_council))
            }
        }
        GenerationOutput(output = output, generating = generating)
    }
}

@Composable
private fun WritingRoom(
    output: String,
    generating: Boolean,
    wordCount: Int,
    onGenerate: (CreativeMode, String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var mode by remember { mutableStateOf(CreativeMode.DRAFT) }
    var prompt by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.md)) {
        Text(stringResource(R.string.writing_room), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "The selected model receives this project's current canon every time. Your draft stays exploratory until you add facts to the World tab.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CreativeMode.entries.filter { it != CreativeMode.SIMULATE }) { item ->
                FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.label) })
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            label = { Text(promptLabel(mode)) },
            supportingText = { Text(mode.instruction) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            if (generating) {
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text(stringResource(R.string.stop))
                }
            }
            Button(enabled = prompt.isNotBlank() && !generating, onClick = { onGenerate(mode, prompt, "") }) {
                Text(if (mode == CreativeMode.CONTINUITY) "Check continuity" else mode.label)
            }
        }
        if (output.isNotBlank()) {
            Text("$wordCount words", style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textSecondary)
        }
        GenerationOutput(output = output, generating = generating)
    }
}

@Composable
private fun CraftRoom(
    output: String,
    generating: Boolean,
    onApply: (ProseCraftTools.CraftTool, String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedTool by remember { mutableStateOf(ProseCraftTools.CraftTool.SHOW_DONT_TELL) }
    var selectedText by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.md)) {
        Text("Prose Craft Tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Select a craft tool, paste the text you want to transform, and run. Each tool applies a specific craft principle.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ProseCraftTools.CraftTool.entries) { tool ->
                FilterChip(
                    selected = selectedTool == tool,
                    onClick = { selectedTool = tool },
                    label = { Text("${tool.iconName} ${tool.label}") },
                )
            }
        }
        OutlinedTextField(
            value = selectedText,
            onValueChange = { selectedText = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text("Selected text to transform") },
        )
        OutlinedTextField(
            value = context,
            onValueChange = { context = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text("Surrounding context (optional)") },
            supportingText = { Text("Paste the paragraph before/after for reference") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            if (generating) {
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text(stringResource(R.string.stop))
                }
            }
            Button(
                enabled = selectedText.isNotBlank() && !generating,
                onClick = { onApply(selectedTool, selectedText, context) },
            ) {
                Text(selectedTool.label)
            }
        }
        GenerationOutput(output = output, generating = generating)
    }
}

@Composable
private fun ToolsRoom(
    state: CreativeStudioUiState,
    viewModel: CreativeStudioViewModel,
) {
    var voiceSample by remember { mutableStateOf("") }
    var tensionText by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.md)) {
        // Voice Calibration
        Text("Voice Calibration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Paste 500-5000 words of your writing. Aura will analyze your prose style and match it in generated content.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
        OutlinedTextField(
            value = voiceSample,
            onValueChange = { voiceSample = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            label = { Text("Your writing sample") },
        )
        Button(
            enabled = voiceSample.length > 200 && !state.calibrating,
            onClick = { viewModel.calibrateVoice(voiceSample) },
        ) {
            Text(if (state.calibrating) "Analyzing..." else "Calibrate Voice")
        }
        if (state.voiceProfile.isNotBlank()) {
            Surface(
                color = AuraThemeTokens.colors.surface1,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(AuraSpacing.md)) {
                    Text("Voice Profile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(state.voiceProfile, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        // Tension Analysis
        Text("Tension Analysis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Paste your manuscript. Aura will analyze pacing scene-by-scene and produce a tension heatmap with recommendations.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
        OutlinedTextField(
            value = tensionText,
            onValueChange = { tensionText = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            label = { Text("Manuscript text") },
        )
        Button(
            enabled = tensionText.length > 500 && !state.analyzingTension,
            onClick = { viewModel.analyzeTension(tensionText) },
        ) {
            Text(if (state.analyzingTension) "Analyzing..." else "Analyze Pacing")
        }
        if (state.tensionReport.isNotBlank()) {
            Surface(
                color = AuraThemeTokens.colors.surface1,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(AuraSpacing.md)) {
                    Text("Tension Report", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(state.tensionReport, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SimulationRoom(
    project: CreativeProject,
    output: String,
    generating: Boolean,
    onGenerate: (CreativeMode, String, String) -> Unit,
    onCancel: () -> Unit,
    onCanonize: (String) -> Unit,
) {
    var premise by remember { mutableStateOf("") }
    var perspective by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.md)) {
        Text(stringResource(R.string.scenario_simulator), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Explore alternate decisions without contaminating canon. Aura traces first- and second-order consequences against the world bible.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
        OutlinedTextField(
            value = premise,
            onValueChange = { premise = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text(stringResource(R.string.what_if)) },
            placeholder = { Text(stringResource(R.string.what_if_the_protagonist_accepts_the)) },
        )
        OutlinedTextField(
            value = perspective,
            onValueChange = { perspective = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.perspective_character_optional)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            if (generating) OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.stop)) }
            Button(enabled = premise.isNotBlank() && !generating, onClick = { onGenerate(CreativeMode.SIMULATE, premise, perspective) }) {
                Text(stringResource(R.string.run_scenario))
            }
        }
        GenerationOutput(output = output, generating = generating)
        if (project.world.simulations.isNotEmpty()) {
            Text(stringResource(R.string.simulation_history), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            project.world.simulations.forEach { simulation ->
                val colors = AuraThemeTokens.colors
                Surface(
                    color = colors.surface1,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, colors.borderSubtle),
                ) {
                    Column(Modifier.padding(AuraSpacing.md), verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                        Text(simulation.premise, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(simulation.outcome, style = MaterialTheme.typography.bodySmall, maxLines = 8)
                        if (simulation.canonized) {
                            Text("CANON", color = colors.actionPrimary, style = MaterialTheme.typography.labelSmall)
                        } else {
                            TextButton(onClick = { onCanonize(simulation.id) }, modifier = Modifier.align(Alignment.End)) {
                                Icon(Icons.Filled.CheckCircleOutline, contentDescription = null)
                                Text(stringResource(R.string.add_outcome_to_canon_timeline))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenerationOutput(output: String, generating: Boolean) {
    if (output.isBlank() && !generating) return
    val colors = AuraThemeTokens.colors
    Surface(
        color = colors.surface1,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Column(Modifier.padding(AuraSpacing.md), verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
            Text(if (generating) "Aura is writing…" else "Result", style = MaterialTheme.typography.labelLarge, color = colors.actionPrimary)
            if (generating && output.isBlank()) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            SelectionContainer { Text(output, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun promptLabel(mode: CreativeMode): String = when (mode) {
    CreativeMode.BRAINSTORM -> "Question, problem, or possibility space"
    CreativeMode.OUTLINE -> "Story premise, arc, or section to outline"
    CreativeMode.DRAFT -> "Scene goal, viewpoint, conflict, and desired length"
    CreativeMode.REWRITE -> "Paste text and describe what should improve"
    CreativeMode.CONTINUITY -> "Paste the passage to audit against canon"
    CreativeMode.SIMULATE -> "Scenario premise"
}

@Composable
private fun ProjectMetadataDialog(
    project: CreativeProject,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(project.name) }
    var description by remember { mutableStateOf(project.description) }
    var genre by remember { mutableStateOf(project.genre) }
    var tone by remember { mutableStateOf(project.tone) }
    var template by remember { mutableStateOf(project.templateId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.project_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.premise)) }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(genre, { genre = it }, label = { Text(stringResource(R.string.genre)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(tone, { tone = it }, label = { Text(stringResource(R.string.tone)) }, modifier = Modifier.weight(1f))
                }
                WritingTemplates.all.forEach { form ->
                    FilterChip(selected = template == form.id, onClick = { template = form.id }, label = { Text("${form.icon} ${form.name}") })
                }
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(name, description, genre, tone, template) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}