package com.aura.ui.screens.council

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.agent.AgentCouncil
import com.aura.agent.AgentEntity
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.InterDisplay
import com.aura.ui.viewmodel.CouncilViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouncilScreen(
    onBack: () -> Unit,
    onSendToChat: (String) -> Unit = {},
    viewModel: CouncilViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AuraScreenShell(
        title = "Agent Council",
        subtitle = "Multi-agent council results",
        action = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AuraSpacing.md)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Ask multiple agents, then let one synthesize the best answer.",
                color = AuraThemeTokens.colors.textSecondary,
                fontFamily = InterDisplay,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.task,
                onValueChange = viewModel::setTask,
                label = { Text("Task for the council") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
                trailingIcon = {
                    IconButton(
                        onClick = { viewModel.runCouncil() },
                        enabled = state.task.isNotBlank() && !state.running,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run council")
                    }
                },
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Agents",
                fontFamily = InterDisplay,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(8.dp))

            AgentMultiSelect(
                agents = state.availableAgents,
                selectedIds = state.selectedAgentIds,
                onToggle = viewModel::toggleAgent,
            )

            Spacer(Modifier.height(24.dp))

            if (state.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Running council...", color = AuraThemeTokens.colors.textSecondary)
                }
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = AuraThemeTokens.colors.error, fontSize = 14.sp)
            }

            state.result?.let { result ->
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Final synthesis", fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    IconButton(onClick = { onSendToChat(result.directorOutput) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send synthesis to chat",
                            tint = AuraThemeTokens.colors.actionPrimary,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = AuraThemeTokens.colors.surface1,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = result.directorOutput,
                        modifier = Modifier.padding(16.dp),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }

            if (state.progress.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Progress", fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                state.progress.forEach { progress ->
                    ProgressCard(progress)
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AgentMultiSelect(
    agents: List<AgentEntity>,
    selectedIds: List<String>,
    onToggle: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(agents, key = { it.id }) { agent ->
            val selected = agent.id in selectedIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.12f) else AuraThemeTokens.colors.surface1)
                    .clickable { onToggle(agent.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = agent.icon,
                    fontSize = 22.sp,
                    modifier = Modifier.width(32.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        fontFamily = InterDisplay,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                    if (agent.description.isNotBlank()) {
                        Text(
                            text = agent.description,
                            color = AuraThemeTokens.colors.textTertiary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = AuraThemeTokens.colors.actionPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(progress: AgentCouncil.Progress) {
    val (icon, title, body) = when (progress) {
        is AgentCouncil.Progress.ProposalsStarted ->
            Triple(Icons.Filled.Groups, "Proposals started", progress.agentNames.joinToString(", "))
        is AgentCouncil.Progress.ProducerDone ->
            Triple(Icons.Filled.Groups, "${progress.agentName} finished", progress.output)
        is AgentCouncil.Progress.DirectorStarted ->
            Triple(Icons.Filled.Groups, "Director ${progress.agentName} synthesizing", "")
        is AgentCouncil.Progress.DirectorDone ->
            Triple(Icons.Filled.Groups, "Director done", progress.output)
        is AgentCouncil.Progress.Error ->
            Triple(Icons.Filled.Groups, "Error", progress.message)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AuraThemeTokens.colors.surface1,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = AuraThemeTokens.colors.actionPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontFamily = InterDisplay, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                if (body.isNotBlank()) {
                    Text(
                        text = body,
                        color = AuraThemeTokens.colors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}
