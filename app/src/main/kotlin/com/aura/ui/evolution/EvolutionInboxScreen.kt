package com.aura.ui.evolution

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    viewModel: EvolutionInboxViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val proposals = viewModel.proposals.collectAsState().value
    val settings = viewModel.settings.collectAsState().value

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
                )
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: EvolutionProposalEntity,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
        }
    }
}
