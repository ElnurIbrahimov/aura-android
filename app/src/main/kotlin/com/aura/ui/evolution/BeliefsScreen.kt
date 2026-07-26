package com.aura.ui.evolution

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.components.AuraEmptyState
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraThemeTokens

@Composable
fun BeliefsScreen(viewModel: BeliefsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val beliefs = state.beliefs
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    AuraScreenShell(
        title = "Beliefs",
        subtitle = "What Aura currently holds true about you and your world",
    ) {
        if (beliefs.isEmpty()) {
            AuraEmptyState(
                icon = Icons.Outlined.Lightbulb,
                title = "No beliefs yet",
                message = "Aura forms beliefs as it learns stable facts from your " +
                    "conversations. They'll appear here once it has enough signal.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(beliefs, key = { it.id }) { belief ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { viewModel.select(belief.id) },
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "${belief.subject} — ${belief.predicate}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(belief.valueJson, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row {
                                Text("conf: ${belief.confidence}", style = MaterialTheme.typography.labelSmall)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("status: ${belief.status}", style = MaterialTheme.typography.labelSmall)
                            }
                            val supporting = state.evidence[belief.id].orEmpty()
                            if (supporting.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                supporting.take(3).forEach { evidence ->
                                    Text(
                                        text = "· ${evidence.summary}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AuraThemeTokens.colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail dialog — previously select() loaded a belief that nothing read,
    // so tapping a card did nothing. Now the selection drives a dialog.
    selected?.let { belief ->
        AlertDialog(
            onDismissRequest = { viewModel.clearSelection() },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSelection() }) { Text(stringResource(R.string.close)) }
            },
            title = { Text("${belief.subject} — ${belief.predicate}") },
            text = {
                Column {
                    Text(belief.valueJson, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Confidence: ${belief.confidence}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text("Status: ${belief.status}", style = MaterialTheme.typography.labelMedium)
                }
            },
        )
    }
}
