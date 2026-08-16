package com.aura.ui.screens.skills

import com.aura.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
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
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.viewmodel.SkillsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.unit.sp
import com.aura.ui.components.AuraUnderlinedField
import com.aura.ui.theme.AuraThemeTokens

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

    Box(modifier = Modifier.fillMaxSize()) {
        AuraScreenShell(
            title = stringResource(R.string.skills),
            subtitle = "Saved skill definitions",
            action = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
        FloatingActionButton(
            onClick = { showNew = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(AuraSpacing.md),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add skill")
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
            .padding(AuraSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.no_skills_yet), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Skills are reusable instruction modules. The agent can invoke them by name via the use_skill tool.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = AuraSpacing.xs),
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
            .padding(horizontal = AuraSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
        // The FAB sits bottom-end over this list and used to overlap the last
        // card. Reserving its height means the final skill can be read and
        // tapped like every other one.
        contentPadding = PaddingValues(bottom = AuraSpacing.xxl + AuraSpacing.lg),
    ) {
        items(items = skills, key = { it.id }) { skill ->
            SkillCard(skill = skill, onClick = { onClick(skill) })
        }
    }
}

/**
 * One skill, in the shape the rest of the app uses.
 *
 * This list previously drew a bare `Card {}`, which takes Material3's default
 * `surfaceContainerLow` — a light flat grey — while every other surface in Aura
 * is `AuraThemeTokens.colors.surface1` (#121214) on a near-black ground. The
 * cards read as borrowed UI rather than part of the app, which is the actual
 * cause of the "grey" complaint; the fix is to use the tokens, not to pick a
 * nicer grey.
 *
 * The row also had an `Edit` button floating under a large empty gap, making
 * each card about 380px tall to show three lines. The whole card is the target
 * now, so the height roughly halves and twice as many skills fit on screen.
 */
@Composable
private fun SkillCard(skill: Skill, onClick: () -> Unit) {
    val colors = AuraThemeTokens.colors
    Surface(
        color = colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.xl2),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(AuraSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = colors.actionPrimary.copy(alpha = 0.14f), shape = CircleShape) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = colors.actionPrimary,
                    modifier = Modifier.padding(AuraSpacing.medium).size(AuraSpacing.xxl2),
                )
            }
            Spacer(Modifier.size(AuraSpacing.sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        skill.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Built-ins can be edited and reset but never deleted, and
                    // the craft guidance the creative engine depends on lives
                    // among them. Worth seeing before you open one.
                    if (skill.builtin) {
                        Spacer(Modifier.size(AuraSpacing.xs))
                        Surface(
                            color = colors.actionPrimary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(AuraSpacing.xs),
                        ) {
                            Text(
                                stringResource(R.string.built_in),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.actionPrimary,
                                modifier = Modifier.padding(
                                    horizontal = AuraSpacing.small,
                                    vertical = AuraSpacing.xxs,
                                ),
                            )
                        }
                    }
                }
                if (skill.description.isNotBlank()) {
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = AuraSpacing.xxs),
                    )
                }
                // Dimmer than the description on purpose: this is a peek at the
                // body, not a second summary. They were the same size and colour
                // before, so a card read as three interchangeable grey lines.
                Text(
                    skill.preview(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = AuraSpacing.xxs),
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colors.textTertiary,
            )
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
            .padding(AuraSpacing.md)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
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
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
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
    val colors = AuraThemeTokens.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.lg),
        title = {
            Text(
                text = stringResource(R.string.new_skill),
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 26.sp, lineHeight = 32.sp),
                color = colors.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.md),
            ) {
                AuraUnderlinedField(name, { name = it.take(80) }, stringResource(R.string.name))
                AuraUnderlinedField(
                    description,
                    { description = it.take(240) },
                    stringResource(R.string.description_optional),
                )
                AuraUnderlinedField(
                    body,
                    { body = it },
                    stringResource(R.string.body_markdown),
                    minLines = 5,
                )
                // A blank markdown box says nothing about what belongs in
                // it. One line of guidance is the whole difference between
                // an empty field and a usable one.
                Text(
                    text = "The instructions Aura follows when you invoke this skill — steps, rules, examples.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            }
        },
        confirmButton = {
            // Create carries the accent and Cancel is neutral. They were
            // the other way round, so the highlighted button was the one
            // that throws the work away.
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name, description, body) },
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
