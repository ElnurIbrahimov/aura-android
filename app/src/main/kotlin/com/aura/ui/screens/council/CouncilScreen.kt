package com.aura.ui.screens.council

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.agent.forum.ForumPostEntity
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraThemeTokens

@Composable
fun CouncilScreen(
    convId: String? = null,
    onBack: () -> Unit,
    onOpenDreamLog: () -> Unit = {},
    onOpenAgentProfiles: () -> Unit = {},
    viewModel: CouncilViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AuraScreenShell(
        title = "Council",
        subtitle = "Agent society decisions",
        action = {
            Row {
                IconButton(onClick = onOpenDreamLog) {
                    Icon(Icons.Filled.Bedtime, contentDescription = "Dream Log")
                }
                IconButton(onClick = onOpenAgentProfiles) {
                    Icon(Icons.Filled.Person, contentDescription = "Agent Profiles")
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "Back")
                }
            }
        },
    ) { paddingValues ->
        if (state.selectedThread.isNotEmpty()) {
            ThreadView(
                posts = state.selectedThread,
                onBack = { viewModel.closeThread() },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.interventions.isEmpty() && !state.isLoading) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Filled.Forum,
                                contentDescription = null,
                                modifier = Modifier.padding(bottom = 16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "No council activity yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Agents will debate and propose interventions while your phone is idle.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                            )
                        }
                    }
                }

                items(state.interventions, key = { it.id }) { post ->
                    InterventionCard(
                        post = post,
                        onOpenThread = { viewModel.openThread(post.threadId) },
                        onApprove = { viewModel.approveIntervention(post.id) },
                        onReject = { viewModel.rejectIntervention(post.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InterventionCard(
    post: ForumPostEntity,
    onOpenThread: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val agentName = post.agentId.removePrefix("agent_").replaceFirstChar { it.uppercase() }
    val isProposal = post.type == "proposal"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = post.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AuraThemeTokens.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            AssistChip(
                onClick = onOpenThread,
                label = { Text(agentName, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Filled.HowToVote, contentDescription = null, modifier = Modifier.height(16.dp)) },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = post.body.take(300),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
            maxLines = 4,
        )

        if (isProposal) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tap thread to see debate",
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraThemeTokens.colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onReject) {
                    Icon(Icons.Filled.Close, contentDescription = "Reject", tint = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = onApprove) {
                    Icon(Icons.Filled.Check, contentDescription = "Approve", tint = AuraThemeTokens.colors.actionPrimary)
                }
            }
        }
    }
}

@Composable
private fun ThreadView(
    posts: List<ForumPostEntity>,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "Close thread")
                }
                Text(
                    "Debate thread",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        items(posts, key = { it.id }) { post ->
            val agentName = post.agentId.removePrefix("agent_").replaceFirstChar { it.uppercase() }
            val typeLabel = when (post.type) {
                "debate" -> "debated"
                "proposal" -> "proposed"
                "intervention" -> "intervened"
                "dream" -> "dreamed"
                else -> post.type
            }
            val sentimentIcon = when {
                post.sentiment > 0.3f -> "▲"
                post.sentiment < -0.3f -> "▼"
                else -> "◆"
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "$agentName $typeLabel",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraThemeTokens.colors.actionPrimary,
                    )
                    Text(
                        text = sentimentIcon,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            post.sentiment > 0.3f -> AuraThemeTokens.colors.success
                            post.sentiment < -0.3f -> MaterialTheme.colorScheme.error
                            else -> AuraThemeTokens.colors.textTertiary
                        },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = post.body.take(500),
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
        }
    }
}