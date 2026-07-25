package com.aura.ui.evolution

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun EvolutionRollbackScreen(
    proposalId: String,
    onBack: () -> Unit,
    viewModel: EvolutionInboxViewModel = hiltViewModel(),
) {
    val proposals by viewModel.proposals.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    val proposal = remember(proposals, proposalId) { proposals.firstOrNull { it.id == proposalId } }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(stringResource(R.string.rollback_proposal), style = MaterialTheme.typography.headlineMedium)
        proposal?.let { p ->
            Text("Domain: ${p.domain}", style = MaterialTheme.typography.bodyLarge)
            Text("Target: ${p.targetId}", style = MaterialTheme.typography.bodyLarge)
            Text("Status: ${p.status}", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { confirm = true }, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.rollback))
            }
        } ?: Text(stringResource(R.string.proposal_not_found), style = MaterialTheme.typography.bodyLarge)
    }

    if (confirm && proposal != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Rollback ${proposal.domain} proposal?") },
            text = { Text(stringResource(R.string.this_restores_the_previous_version_any)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rollback(proposal.id)
                    confirm = false
                    onBack()
                }) { Text(stringResource(R.string.rollback)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
