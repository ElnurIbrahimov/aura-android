package com.aura.ui.evolution

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.evolution.EvolutionDomain
import com.aura.evolution.EvolutionProposalEntity
import com.aura.evolution.ProposalStatus

@Composable
fun EvolutionInboxScreen(
    onBack: () -> Unit,
    onRollback: (String) -> Unit = {},
    viewModel: EvolutionInboxViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    var selectedProposal by remember { mutableStateOf<EvolutionProposalEntity?>(null) }
    val proposals = viewModel.proposals.collectAsState().value
    val settings = viewModel.settings.collectAsState().value
    val showOnboarding = viewModel.showOnboarding.collectAsState().value

    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier = modifier.fillMaxSize()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            text = "Evolution Inbox",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp),
        )

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            for (domain in EvolutionDomain.entries) {
                val enabled = settings.find { it.domain == domain.name }?.enabled != false
                Switch(
                    checked = enabled,
                    onCheckedChange = { viewModel.setDomainEnabled(domain, it) },
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = domain.name.lowercase().replaceFirstChar { it.uppercase() },
                    modifier = Modifier.align(Alignment.CenterVertically).padding(end = 16.dp),
                )
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(proposals, key = { it.id }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    onApprove = { viewModel.approve(proposal.id) },
                    onReject = { viewModel.reject(proposal.id) },
                    onDetail = { selectedProposal = proposal },
                    onRollback = { onRollback(proposal.id) },
                )
            }
        }
    }

    if (showOnboarding) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissOnboarding() },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissOnboarding() }) {
                    Text("Start evolving")
                }
            },
            title = { Text("Evolution") },
            text = { Text("Aura can learn from your conversations and propose skill, memory, and proactive improvements. Approved changes run through a safety gate, and you can roll back anything later.") },
        )
    }

    selectedProposal?.let { proposal ->
        AlertDialog(
            onDismissRequest = { selectedProposal = null },
            title = { Text(proposal.title) },
            text = {
                Column {
                    Text("Domain: ${proposal.domain}")
                    Text("Action: ${proposal.action}")
                    Text("Status: ${proposal.status}")
                    Text("Confidence: ${"%.0f".format(proposal.confidence * 100)}%")
                    if (proposal.patchJson.isNotBlank() && proposal.patchJson != "{}") {
                        Text(
                            text = "Patch: " + proposal.patchJson,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedProposal = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun ProposalCard(
    proposal: EvolutionProposalEntity,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDetail: () -> Unit,
    onRollback: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onDetail() },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = proposal.title, style = MaterialTheme.typography.titleMedium)
            Text(text = proposal.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${proposal.domain} • ${proposal.action} • ${proposal.status}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (proposal.status == ProposalStatus.PENDING_REVIEW.name) {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = onApprove) { Text("Approve") }
                    TextButton(onClick = onReject) { Text("Reject") }
                }
            }
            if (proposal.status == ProposalStatus.APPLIED.name) {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = onRollback) { Text("Rollback") }
                }
            }
        }
    }
}
