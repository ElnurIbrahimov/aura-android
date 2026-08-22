package com.aura.ui.screens.creative

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onPlay: (String) -> Unit = {},
    viewModel: CreativeStudioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project = state.selectedProject
    var selectedTab by remember { mutableIntStateOf(0) }

    state.sceneEditor?.let { editor ->
        SceneEditorDialog(
            editor = editor,
            onSave = viewModel::saveSceneEdit,
            onDismiss = viewModel::dismissSceneEditor,
        )
    }

    // The tab-open ritual: snapshot what was missed, then move the marker.
    androidx.compose.runtime.LaunchedEffect(selectedTab) {
        if (selectedTab == LIVING_TAB) viewModel.onLivingTabOpened()
    }
    var showEdit by remember { mutableStateOf(false) }
    // The Intent fires here, not in the ViewModel. No ViewModel in this app
    // constructs one — the ViewModel produces the payload and the composable
    // hands it out. manuscriptSection cannot hold these itself: it is a
    // LazyListScope extension, not a @Composable.
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()

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
            // Scrollable, not fixed-width. PrimaryTabRow divides the screen
            // equally between tabs, so every label has to fit whatever 1/n of
            // the width happens to be — adding a seventh tab took each from
            // ~240px to ~205px and wrapped every label to three or four lines
            // ("Ma/nu/scr/ipt"). Content-sized tabs that scroll sideways keep
            // full words at any count, and `softWrap = false` makes that
            // structural rather than a thing that survives until the next tab.
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = AuraSpacing.md,
            ) {
                listOf("World", "Living", "Write", "Manuscript", "Simulate", "Council", "Craft", "Tools").forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label, maxLines = 1, softWrap = false) },
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
                // Manuscript and Living get real `items()` rather than sharing
                // the single `item { }` the other tabs sit in. A finished
                // chapter set is tens of thousands of words and a year of world
                // history is thousands of events; inside one item, Compose
                // measures all of it on every frame.
                when (selectedTab) {
                    LIVING_TAB -> livingWorldSection(state, viewModel, onPlay)
                    MANUSCRIPT_TAB -> manuscriptSection(
                        state = state,
                        viewModel = viewModel,
                        onExport = {
                            exportScope.launch {
                                val markdown = viewModel.exportManuscript()
                                if (markdown == null) {
                                    // exportManuscript already set state.error if the
                                    // read failed; a null with no error means there was
                                    // simply nothing drafted, which the disabled button
                                    // should have prevented.
                                    return@launch
                                }
                                val shared = com.aura.ui.components.shareTextFile(
                                    context = context,
                                    fileStem = com.aura.ui.components.markdownFileName(project.name),
                                    content = markdown,
                                    subject = project.name,
                                    chooserTitle = "Export manuscript",
                                )
                                if (!shared) {
                                    viewModel.reportExportFailed()
                                }
                            }
                        },
                    )
                    else -> item {
                        when (selectedTab) {
                            0 -> WorldBibleEditor(project = project, onSave = viewModel::saveWorld)
                            2 -> WritingRoom(state.output, state.generating, state.wordCount, viewModel::generate, viewModel::cancelGeneration, state.thinkingEnabled, viewModel::toggleThinking)
                            4 -> SimulationRoom(project, state.output, state.generating, viewModel::generate, viewModel::cancelGeneration, viewModel::canonizeSimulation)
                            5 -> CouncilRoom(state.output, state.generating, viewModel::runCouncil, viewModel::cancelGeneration)
                            6 -> CraftRoom(state.output, state.generating, viewModel::applyCraftTool, viewModel::cancelGeneration)
                            else -> ToolsRoom(state, viewModel)
                        }
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs), modifier = Modifier.align(Alignment.End)) {
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
    thinkingEnabled: Boolean,
    /**
     * No default. `CreativeStudioViewModel.toggleThinking` had no caller, so
     * `thinkingEnabled` was permanently true and the branch it guards —
     * `if (thinkingEnabled) thinkingBudget else 0` — could only ever take one
     * side. A switch with no way to flip it is a constant with extra steps.
     */
    onToggleThinking: () -> Unit,
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
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
        // Extended thinking costs tokens and latency on every generate, and
        // drafting prose is the mode where it buys least — a scene does not
        // need a reasoning budget the way a continuity check does. Beside the
        // generate button rather than in Settings, because the answer changes
        // per run and the cost lands on this button.
        Row(
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = thinkingEnabled, onCheckedChange = { onToggleThinking() })
            Column {
                Text(stringResource(R.string.extended_thinking), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (thinkingEnabled) {
                        "The model reasons before it writes. Slower, and it costs thinking tokens"
                    } else {
                        "Straight to the draft. Cheaper and faster; weaker on continuity and plot logic"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs), modifier = Modifier.align(Alignment.End)) {
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
        Text(stringResource(R.string.prose_craft_tools), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Select a craft tool, paste the text you want to transform, and run. Each tool applies a specific craft principle.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
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
            label = { Text(stringResource(R.string.selected_text_to_transform)) },
        )
        OutlinedTextField(
            value = context,
            onValueChange = { context = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text(stringResource(R.string.surrounding_context_optional)) },
            supportingText = { Text(stringResource(R.string.paste_the_paragraph_before_after_for)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs), modifier = Modifier.align(Alignment.End)) {
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
        Text(stringResource(R.string.voice_calibration), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
            label = { Text(stringResource(R.string.your_writing_sample)) },
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
                shape = androidx.compose.foundation.shape.RoundedCornerShape(AuraSpacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(AuraSpacing.md)) {
                    Text(stringResource(R.string.voice_profile), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(state.voiceProfile, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = AuraSpacing.xs))
        // Tension Analysis
        Text(stringResource(R.string.tension_analysis), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
            label = { Text(stringResource(R.string.manuscript_text)) },
        )
        // Which scene the analysis is filed against.
        //
        // `analyzeTension` has always taken an optional `artifactId`, and this screen has
        // always omitted it — so `storeAndDiff` never ran, the report was never persisted,
        // and `state.tensionDiff` below could only ever be null. The whole
        // compare-with-the-previous-revision half of the feature was unreachable through
        // the only button that reaches it.
        //
        // A scene has to be named because the panel analyses pasted text, which belongs to
        // no artifact by itself. "Not linked" is the default and behaves exactly as before.
        val scenes = state.selectedProject?.world?.outline.orEmpty()
            .filter { it.artifactId.isNotBlank() }
        var linkedArtifactId by remember { mutableStateOf<String?>(null) }
        if (scenes.isNotEmpty()) {
            Text(
                stringResource(R.string.file_analysis_against),
                style = MaterialTheme.typography.labelLarge,
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
            ) {
                FilterChip(
                    selected = linkedArtifactId == null,
                    onClick = { linkedArtifactId = null },
                    label = { Text(stringResource(R.string.not_linked)) },
                )
                scenes.forEach { scene ->
                    FilterChip(
                        selected = linkedArtifactId == scene.artifactId,
                        onClick = { linkedArtifactId = scene.artifactId },
                        label = { Text(scene.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        Button(
            enabled = tensionText.length > 500 && !state.analyzingTension,
            onClick = { viewModel.analyzeTension(tensionText, linkedArtifactId) },
        ) {
            Text(if (state.analyzingTension) "Analyzing..." else "Analyze Pacing")
        }
        if (state.tensionReport.isNotBlank()) {
            Surface(
                color = AuraThemeTokens.colors.surface1,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(AuraSpacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(AuraSpacing.md)) {
                    Text(stringResource(R.string.tension_report), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                    // The comparison goes above the report, not below it. What
                    // moved since the last draft is the finding; the scores are
                    // the working. Absent when there is no earlier analysis to
                    // compare against, rather than shown as a row of zeroes.
                    state.tensionDiff?.let { diff ->
                        Spacer(Modifier.height(AuraSpacing.xs))
                        Text(
                            text = "Since your last draft: mean %.1f → %.1f".format(diff.meanBefore, diff.meanAfter),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (diff.improved) {
                                AuraThemeTokens.colors.actionPrimary
                            } else {
                                AuraThemeTokens.colors.textPrimary
                            },
                        )
                        diff.moved().take(5).forEach { d ->
                            Text(
                                text = when {
                                    d.isNew -> "${d.label} — new, ${d.after}/10"
                                    d.isGone -> "${d.label} — cut"
                                    else -> "${d.label} — ${d.before} → ${d.after}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.75f),
                            )
                        }
                        val ignored = diff.stillFlat()
                        if (ignored.isNotEmpty()) {
                            Text(
                                // The notes that went unacted-on, which is the
                                // thing a writer most wants pointed out and the
                                // thing a fresh analysis of the new draft alone
                                // can never tell them.
                                text = "Still flat: " + ignored.joinToString { it.label },
                                style = MaterialTheme.typography.bodySmall,
                                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.55f),
                            )
                        }
                        Spacer(Modifier.height(AuraSpacing.xs))
                    }

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
        Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs), modifier = Modifier.align(Alignment.End)) {
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
                    border = BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
                ) {
                    Column(Modifier.padding(AuraSpacing.md), verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                        Text(simulation.premise, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(simulation.outcome, style = MaterialTheme.typography.bodySmall, maxLines = 8)
                        if (simulation.canonized) {
                            Text(stringResource(R.string.canon), color = colors.actionPrimary, style = MaterialTheme.typography.labelSmall)
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
        border = BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
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
            Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.premise)) }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
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

/** Index of the Manuscript tab in the tab row. */
/**
 * Tab indices into the `listOf(...)` above. Named because inserting "Living" at
 * position 1 shifted every tab after it, and a bare `3` in a `when` is the kind
 * of thing that survives such a shift while quietly meaning something else.
 */
private const val LIVING_TAB = 1
private const val MANUSCRIPT_TAB = 3

/**
 * The long-form drafting surface: plan an outline, review it, draft every scene.
 *
 * A `LazyListScope` extension rather than a `@Composable`, so a finished chapter
 * set lays out as one item per scene. The other tabs share a single `item { }`,
 * which is fine for a text field and pathological for forty thousand words —
 * Compose would measure the whole book every frame.
 *
 * Everything sits in `Surface` cards matching [WorldSection], because this is one
 * tab of a screen and not its own app. The first version was bare text on the
 * background beside tabs full of bordered cards, and looked exactly as
 * unfinished as that sounds.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.manuscriptSection(
    state: CreativeStudioUiState,
    viewModel: CreativeStudioViewModel,
    // No default value, deliberately. With `= {}` the call site keeps compiling
    // if the wire is ever removed, and the export button renders and silently
    // does nothing — which is precisely the shape CraftWiringTest was written
    // about. A required parameter makes that a compile error instead of a
    // feature nobody notices is dead.
    onExport: () -> Unit,
) {
    val project = state.selectedProject ?: return
    val beats = project.world.outline
    val run = state.longform
    val drafted = beats.count { it.status == DRAFTED }

    // Deliberately a different count from `drafted` above.
    //
    // `status` is free-form text — WorldBibleEditor and CreativeTools both let a
    // user or a model write anything into it — while the exporter resolves
    // through `artifactId`. Enabling the button on `status` would offer an
    // export to legacy and hand-added beats that have no scene behind them, and
    // hand back a document of nothing but placeholders. Keying both on the same
    // fact means "enabled" and "has something to export" cannot drift apart.
    // Matches LongformRunner.drafted().
    val exportable = beats.count { it.artifactId.isNotBlank() }

    if (beats.isEmpty()) {
        item(key = "manuscript-plan") {
            ManuscriptCard(title = "Plan the outline") {
                Text(
                    "Aura breaks the story into beats, then drafts them one scene at a time in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
                var brief by remember { mutableStateOf(project.description) }
                OutlinedTextField(
                    value = brief,
                    onValueChange = { brief = it },
                    label = { Text(stringResource(R.string.what_is_this_about)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    enabled = !state.planningOutline,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.planningOutline) {
                        CircularProgressIndicator(Modifier.size(AuraSpacing.md))
                        Text(
                            "Planning…",
                            style = MaterialTheme.typography.bodySmall,
                            color = AuraThemeTokens.colors.textSecondary,
                            modifier = Modifier.padding(start = AuraSpacing.sm),
                        )
                    } else {
                        Button(onClick = { viewModel.planOutline(brief) }, enabled = brief.isNotBlank()) {
                            Text(stringResource(R.string.plan_outline))
                        }
                    }
                }
            }
        }
        // The model's own words, shown only while there is no outline — so a
        // reply that failed to parse is visible rather than merely reported.
        if (state.output.isNotBlank()) {
            item(key = "manuscript-raw") {
                ManuscriptCard(title = "Model reply") {
                    SelectionContainer {
                        Text(state.output, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        return
    }

    item(key = "manuscript-progress") {
        ManuscriptCard(title = "Manuscript") {
            LinearProgressIndicator(
                progress = { drafted.toFloat() / beats.size },
                modifier = Modifier.fillMaxWidth(),
            )
            val current = run?.currentIndex?.takeIf { it >= 0 }?.let { beats.getOrNull(it) }
            Text(
                text = when {
                    current != null -> "Scene ${run.currentIndex + 1} of ${beats.size} · ${current.title}"
                    run?.active == true -> "Starting…"
                    drafted == beats.size -> "All ${beats.size} scenes written"
                    else -> "$drafted of ${beats.size} scenes written"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (run?.active == true) {
                Text(
                    "Drafting continues if you leave this screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
            run?.error?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.error)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm, Alignment.End),
            ) {
                OutlinedButton(onClick = onExport, enabled = exportable > 0) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(AuraSpacing.md))
                    Text(stringResource(R.string.export))
                }
                if (run?.active == true) {
                    OutlinedButton(onClick = viewModel::cancelDrafting) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text(stringResource(R.string.stop))
                    }
                } else {
                    Button(onClick = viewModel::startDrafting, enabled = drafted < beats.size) {
                        Text(if (drafted == 0) "Write all scenes" else "Continue drafting")
                    }
                }
            }
        }
    }

    if (state.canonFactCount > 0 || state.openConflicts.isNotEmpty()) {
        item(key = "manuscript-canon") {
            ManuscriptCard(title = "Canon") {
                Text(
                    if (state.canonFactCount == 1) {
                        "1 fact recorded from the scenes so far."
                    } else {
                        "${state.canonFactCount} facts recorded from the scenes so far."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
                state.openConflicts.forEach { issue ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = AuraSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            issue.message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.dismissConflict(issue.id) }) {
                            Text(stringResource(R.string.intentional))
                        }
                    }
                }
            }
        }
    }

    // The scene being written, and only that. The manuscript so far belongs in
    // the artifact list; putting it here would redraw a book on every token.
    if (run?.liveText?.isNotBlank() == true) {
        item(key = "manuscript-live") {
            ManuscriptCard(title = "Writing now") {
                SelectionContainer {
                    Text(run.liveText.takeLast(LIVE_TEXT_CHARS), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    item(key = "manuscript-beats-header") {
        Text(
            "Beats",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = AuraSpacing.xxl2),
        )
    }

    itemsIndexed(beats, key = { _, beat -> beat.id }) { index, beat ->
        val done = beat.status == DRAFTED
        val isCurrent = run?.currentIndex == index
        val colors = AuraThemeTokens.colors
        Surface(
            color = if (isCurrent) colors.surface2 else colors.surface1,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(
                AuraSpacing.hairline,
                if (isCurrent) colors.actionPrimary.copy(alpha = 0.5f) else colors.borderSubtle,
            ),
            modifier = Modifier
                .then(
                    if (done) {
                        Modifier.clickable {
                            viewModel.openSceneEditor(index, beat.artifactId, beat.title)
                        }
                    } else {
                        Modifier
                    },
                )
                .fillMaxWidth()
                .padding(horizontal = AuraSpacing.xxl2),
        ) {
            Row(
                Modifier.padding(AuraSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
            ) {
                if (done) {
                    Icon(
                        Icons.Filled.CheckCircleOutline,
                        contentDescription = "written",
                        tint = colors.actionPrimary,
                        modifier = Modifier.size(AuraSpacing.md),
                    )
                } else {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textTertiary,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(beat.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (beat.summary.isNotBlank()) {
                        Text(
                            beat.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A titled card in the shape every other section on this screen uses. */
@Composable
private fun ManuscriptCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(AuraSpacing.hairline, AuraThemeTokens.colors.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.xxl2),
    ) {
        Column(
            Modifier.padding(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** Beat status meaning "a scene exists for this beat". Mirrors LongformRunner. */
private const val DRAFTED = "drafted"

/** How much of the in-flight scene to show. Enough to read, bounded so it cannot grow without limit. */
private const val LIVE_TEXT_CHARS = 1_500

/**
 * A plain full-text editor, deliberately: the goal is the revision chain and
 * the signal, and scope stays there. No diff view, no partial edits.
 */
@Composable
private fun SceneEditorDialog(
    editor: com.aura.ui.viewmodel.SceneEditorUi,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(editor.artifactId) { mutableStateOf(editor.text) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(editor.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 420.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.save_scene))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_edit)) }
        },
    )
}
