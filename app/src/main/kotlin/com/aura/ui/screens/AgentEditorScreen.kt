package com.aura.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.AgentEditorViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentEditorScreen(
    agentId: String? = null,
    onDone: () -> Unit,
    viewModel: AgentEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    androidx.compose.runtime.LaunchedEffect(agentId) {
        if (agentId != null) viewModel.loadAgent(agentId)
    }

    if (state.saved) {
        onDone()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // Header
        Column(modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) {
            Text(
                text = if (state.isBuiltin) "Edit Agent" else if (agentId != null) "Edit Agent" else "New Agent",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = AuraThemeTokens.colors.textPrimary,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Name
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::updateName,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.isBuiltin,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Icon
        OutlinedTextField(
            value = state.icon,
            onValueChange = viewModel::updateIcon,
            label = { Text("Icon (emoji)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Description
        OutlinedTextField(
            value = state.description,
            onValueChange = viewModel::updateDescription,
            label = { Text("Description (one line)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Identity / system prompt
        OutlinedTextField(
            value = state.identity,
            onValueChange = viewModel::updateIdentity,
            label = { Text("System prompt / identity") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 12,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Preferred model
        OutlinedTextField(
            value = state.preferredModel,
            onValueChange = viewModel::updatePreferredModel,
            label = { Text("Preferred model (empty = default)") },
            placeholder = { Text("e.g. ollama:deepseek-v4-pro") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Memory scope
        Text("Memory", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.memoryScope == "shared") "Shared (sees all memories)" else "Private (sees only its own + shared)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
            )
            Switch(
                checked = state.memoryScope == "shared",
                onCheckedChange = { viewModel.updateMemoryScope(if (it) "shared" else "agent") },
                enabled = !state.isBuiltin,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Personality sliders
        Text("Personality", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        PersonalitySlider("Warmth", state.personality.warmth, 0f, 1f) { viewModel.updatePersonality("warmth", it) }
        PersonalitySlider("Formality", state.personality.formality, 0f, 1f) { viewModel.updatePersonality("formality", it) }
        PersonalitySlider("Verbosity", state.personality.verbosity, 0f, 1f) { viewModel.updatePersonality("verbosity", it) }
        PersonalitySlider("Humor", state.personality.humor, 0f, 1f) { viewModel.updatePersonality("humor", it) }
        PersonalitySlider("Proactivity", state.personality.proactivity, 0f, 1f) { viewModel.updatePersonality("proactivity", it) }
        PersonalitySlider("Risk Tolerance", state.personality.riskTolerance, 0f, 1f) { viewModel.updatePersonality("riskTolerance", it) }

        Spacer(modifier = Modifier.height(16.dp))

        // Tools
        Text("Tools", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Toggle which tools this agent can use. Empty = all tools.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        val toolNames = viewModel.availableTools
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (tool in toolNames) {
                val selected = tool in state.toolsAllowed
                AssistChip(
                    onClick = { viewModel.toggleTool(tool) },
                    label = { Text(tool.replace("_", " ")) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (selected) AuraThemeTokens.colors.actionPrimary else AuraThemeTokens.colors.surface1,
                        labelColor = if (selected) AuraThemeTokens.colors.onActionPrimary else AuraThemeTokens.colors.textPrimary,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Error
        state.error?.let { err ->
            Text(text = err, color = AuraThemeTokens.colors.error, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Save + Delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            if (agentId != null && !state.isBuiltin) {
                var showDelete by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showDelete = true },
                    modifier = Modifier.weight(1f),
                ) { Text("Delete") }
                if (showDelete) {
                    AlertDialog(
                        onDismissRequest = { showDelete = false },
                        title = { Text("Delete agent?") },
                        text = { Text("This agent and its private memories will be removed.") },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.delete()
                                showDelete = false
                            }) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDelete = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PersonalitySlider(
    label: String,
    value: Float,
    from: Float,
    to: Float,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f))
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = from..to,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}