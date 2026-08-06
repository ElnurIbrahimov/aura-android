package com.aura.ui.screens.creative

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.creative.CreativeProject
import com.aura.creative.WritingTemplates
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.CreativeStudioViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Box

@Composable
fun CreativeStudioScreen(
    onOpenProject: (String) -> Unit,
    viewModel: CreativeStudioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var deleteProject by remember { mutableStateOf<CreativeProject?>(null) }

    LaunchedEffect(state.createdProjectId) {
        state.createdProjectId?.let { id ->
            viewModel.consumeCreatedProject()
            onOpenProject(id)
        }
    }

    AuraScreenShell(
        title = "Creative Studio",
        subtitle = "World bibles, writing rooms, simulations, and continuity",
        action = {
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New creative project")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding() + AuraSpacing.sm,
                bottom = padding.calculateBottomPadding() + AuraSpacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            item {
                CreativeHero(
                    projectCount = state.projects.size,
                    onCreate = { showCreate = true },
                )
            }
            if (!state.loading && state.projects.isEmpty()) {
                item {
                    Text(
                        "No projects yet. Start with a form, then let the world grow around your own ideas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraThemeTokens.colors.textSecondary,
                        modifier = Modifier.padding(vertical = AuraSpacing.md),
                    )
                }
            }
            items(state.projects, key = { it.id }) { project ->
                CreativeProjectCard(
                    project = project,
                    onOpen = { onOpenProject(project.id) },
                    onDelete = { deleteProject = project },
                )
            }
        }
    }

    if (showCreate) {
        NewCreativeProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, description, genre, tone, template ->
                showCreate = false
                viewModel.createProject(name, description, genre, tone, template)
            },
        )
    }
    deleteProject?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteProject = null },
            title = { Text("Delete ${project.name}?") },
            text = { Text(stringResource(R.string.the_project_world_bible_outline_and)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteProject(project.id)
                    deleteProject = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteProject = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CreativeHero(projectCount: Int, onCreate: () -> Unit) {
    val colors = AuraThemeTokens.colors
    Surface(
        color = colors.surface1,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
    ) {
        Column(
            Modifier.padding(AuraSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            Icon(
                Icons.Filled.AutoStories,
                contentDescription = null,
                tint = colors.actionPrimary,
                modifier = Modifier.size(30.dp),
            )
            Text(
                "Build a world that remembers itself.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                "Keep canon, characters, places, rules, and story beats together. Draft against them, test alternate futures, then canonize only what earns its place.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (projectCount == 1) "1 active world" else "$projectCount active worlds",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textTertiary,
                )
                Button(onClick = onCreate) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(AuraSpacing.small))
                    Text(stringResource(R.string.new_project))
                }
            }
        }
    }
}

@Composable
private fun CreativeProjectCard(
    project: CreativeProject,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AuraThemeTokens.colors
    val world = project.world
    val template = WritingTemplates.byId(project.templateId)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = colors.surface1,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
    ) {
        Row(
            Modifier.padding(AuraSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            Surface(
                color = colors.actionPrimary.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(template?.icon ?: "✍️", modifier = Modifier.padding(AuraSpacing.sm))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(project.genre.takeIf(String::isNotBlank), project.tone.takeIf(String::isNotBlank)).joinToString(" · ").ifBlank { template?.name ?: "Open canvas" },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(AuraSpacing.xxs))
                Text(
                    "${world.characters.size} characters · ${world.locations.size} places · ${world.rules.size} rules · ${project.turnCount} turns",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete project", tint = colors.textTertiary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewCreativeProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("") }
    var templateId by remember { mutableStateOf(WritingTemplates.all.first().id) }
    val colors = AuraThemeTokens.colors
    val selectedTemplate = WritingTemplates.all.firstOrNull { it.id == templateId }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.lg),
        title = {
            Text(
                text = stringResource(R.string.new_creative_project),
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 26.sp, lineHeight = 32.sp),
                color = colors.textPrimary,
            )
        },
        text = {
            // Scrollable: four fields plus the form picker make this taller
            // than the space left above the keyboard, and Create was pushed
            // off-screen the moment a field took focus.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.md),
            ) {
                UnderlinedField(name, { name = it }, stringResource(R.string.project_name))
                UnderlinedField(description, { description = it }, stringResource(R.string.premise), minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.md)) {
                    UnderlinedField(genre, { genre = it }, stringResource(R.string.genre), modifier = Modifier.weight(1f))
                    UnderlinedField(tone, { tone = it }, stringResource(R.string.tone), modifier = Modifier.weight(1f))
                }

                Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                    Text(
                        text = stringResource(R.string.form),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textTertiary,
                    )
                    // FlowRow, not a bare forEach in a Column: each template
                    // used to occupy its own full row, so five forms burned
                    // five rows of a dialog that was already too tall.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        WritingTemplates.all.forEach { template ->
                            val selected = template.id == templateId
                            AssistChip(
                                onClick = { templateId = template.id },
                                // Name only. The emoji prefixes sat beside
                                // line icons everywhere else in the app and
                                // read as placeholder art. FilterChip's
                                // default selected colours were also a blue
                                // that appears nowhere else in the theme.
                                label = { Text(template.name, maxLines = 1) },
                                border = null,
                                colors = if (selected) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = colors.actionPrimary.copy(alpha = 0.18f),
                                        labelColor = colors.assistantAccent,
                                    )
                                } else {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = colors.surface2,
                                        labelColor = colors.textSecondary,
                                    )
                                },
                            )
                        }
                    }
                    // Every template already carries a description of what
                    // the form does to the writing. The picker discarded it
                    // and showed an emoji instead.
                    selectedTemplate?.let {
                        Text(
                            text = it.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textTertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name, description, genre, tone, templateId) },
                colors = ButtonDefaults.textButtonColors(contentColor = colors.assistantAccent),
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary),
            ) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * A hairline-underlined text field, matching Home's ask input and the Add
 * note dialog.
 *
 * Material's OutlinedTextField draws a full box plus a floating accent
 * label, so a four-field form read as heavier than anything it sat on top
 * of. Here the placeholder carries the label and a hairline carries the
 * affordance.
 */
@Composable
private fun UnderlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
) {
    val colors = AuraThemeTokens.colors
    Column(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.actionPrimary),
            minLines = minLines,
            maxLines = if (minLines > 1) 5 else 1,
            singleLine = minLines == 1,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textTertiary,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AuraSpacing.xs),
        )
        HorizontalDivider(thickness = AuraSpacing.hairline, color = colors.borderDefault)
    }
}