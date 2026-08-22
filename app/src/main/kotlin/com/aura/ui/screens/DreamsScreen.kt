package com.aura.ui.screens
import com.aura.ui.components.AuraCard
import com.aura.ui.theme.AuraThemeTokens

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aura.dream.ContradictionEntity
import com.aura.dream.ContradictionDao
import com.aura.dream.DreamConsolidationDao
import com.aura.dream.DreamSummaryEntity
import com.aura.dream.KgEdgeProposalDao
import com.aura.dream.KgEdgeProposalEntity
import com.aura.dream.RoutineDao
import com.aura.dream.RoutineEntity
import com.aura.kg.KnowledgeGraphRepository
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel

@HiltViewModel
class DreamsViewModel @Inject constructor(
    private val routineDao: RoutineDao,
    private val contradictionDao: ContradictionDao,
    private val dreamDao: DreamConsolidationDao,
    private val kgProposalDao: KgEdgeProposalDao,
    private val knowledgeGraphRepository: KnowledgeGraphRepository,
) : ViewModel() {

    val summaries: StateFlow<List<DreamSummaryEntity>> = dreamDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val routines: StateFlow<List<RoutineEntity>> = routineDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val contradictions: StateFlow<List<ContradictionEntity>> = contradictionDao.observeByStatus("UNRESOLVED")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val kgProposals: StateFlow<List<KgEdgeProposalEntity>> = kgProposalDao.observeByStatus("PENDING")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun resolveContradiction(id: String, acceptNewer: Boolean) {
        viewModelScope.launch {
            val entity = contradictionDao.byId(id) ?: return@launch
            val resolved = entity.copy(
                status = if (acceptNewer) "RESOLVED" else "DISMISSED",
                resolvedAt = System.currentTimeMillis(),
            )
            contradictionDao.update(resolved)
        }
    }

    fun acceptKgProposal(id: String) {
        viewModelScope.launch {
            val entity = kgProposalDao.byId(id) ?: return@launch
            knowledgeGraphRepository.addRelatesToEdge(entity.fromNodeId, entity.toNodeId, entity.similarity)
            kgProposalDao.update(entity.copy(status = "ACCEPTED", decidedAt = System.currentTimeMillis()))
        }
    }

    fun rejectKgProposal(id: String) {
        viewModelScope.launch {
            val entity = kgProposalDao.byId(id) ?: return@launch
            kgProposalDao.update(entity.copy(status = "REJECTED", decidedAt = System.currentTimeMillis()))
        }
    }

    fun deleteSummary(id: String) {
        viewModelScope.launch {
            dreamDao.deleteById(id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DreamsScreen(
    onBack: () -> Unit = {},
    viewModel: DreamsViewModel = hiltViewModel(),
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val contradictions by viewModel.contradictions.collectAsStateWithLifecycle()
    val kgProposals by viewModel.kgProposals.collectAsStateWithLifecycle()

    AuraScreenShell(
        title = stringResource(R.string.dream_summaries),
        subtitle = "Memory consolidation summaries",
        action = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            if (summaries.isNotEmpty()) {
                item {
                    Text(
                        "Dream summaries (${summaries.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(summaries, key = { it.id }) { summary ->
                    SummaryCard(summary, onDelete = { viewModel.deleteSummary(summary.id) })
                }
            }
            if (routines.isNotEmpty()) {
                item {
                    Text(
                        "Routines (${routines.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = AuraSpacing.md),
                    )
                }
                items(routines, key = { it.id }) { routine ->
                    RoutineCard(routine)
                }
            }
            if (contradictions.isNotEmpty()) {
                item {
                    Text(
                        "Contradictions (${contradictions.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = AuraSpacing.md),
                    )
                }
                items(contradictions, key = { it.id }) { contradiction ->
                    ContradictionCard(
                        contradiction = contradiction,
                        onResolve = { viewModel.resolveContradiction(it, acceptNewer = true) },
                        onDismiss = { viewModel.resolveContradiction(it, acceptNewer = false) },
                    )
                }
            }
            if (kgProposals.isNotEmpty()) {
                item {
                    Text(
                        "Graph proposals (${kgProposals.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = AuraSpacing.md),
                    )
                }
                items(kgProposals, key = { it.id }) { proposal ->
                    KgProposalCard(
                        proposal = proposal,
                        onAccept = { viewModel.acceptKgProposal(it) },
                        onReject = { viewModel.rejectKgProposal(it) },
                    )
                }
            }
            if (summaries.isEmpty() && routines.isEmpty() && contradictions.isEmpty() && kgProposals.isEmpty()) {
                item {
                    Text(
                        "No dream summaries yet. Run a dream consolidation cycle from Settings to generate routines and detect contradictions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraThemeTokens.colors.textSecondary,
                        modifier = Modifier.padding(top = AuraSpacing.xl),
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    summary: DreamSummaryEntity,
    /**
     * No default. `DreamsViewModel.deleteSummary` existed with no caller, so a
     * dream summary the user disagreed with was permanent — the consolidator
     * writes them unattended and nothing could take one back.
     */
    onDelete: () -> Unit,
) {
    AuraCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    summary.compressedText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                // Deletes the summary, never the memories it was made from.
                // Consolidation is lossy by design and the sources stay in the
                // memory store, so this discards a bad reading rather than the
                // reading material.
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.delete_summary),
                        tint = AuraThemeTokens.colors.textSecondary,
                    )
                }
            }
            if (summary.dominantTags.isNotBlank()) {
                Text(
                    summary.dominantTags.split(",").joinToString("  ") { "#${it.trim()}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraThemeTokens.colors.actionPrimary,
                )
            }
            Text(
                "${summary.sourceCount} sources · ${summary.modelUsed}",
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun RoutineCard(routine: RoutineEntity) {
    AuraCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    routine.displayLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${routine.occurrenceCount}x",
                    style = MaterialTheme.typography.labelLarge,
                    color = AuraThemeTokens.colors.actionPrimary,
                )
            }
            if (routine.description.isNotBlank()) {
                Text(
                    routine.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
            Text(
                "Seen in ${routine.distinctConversations} conversations",
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun ContradictionCard(
    contradiction: ContradictionEntity,
    onResolve: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    AuraCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    contradiction.triggerPhrase,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(AuraSpacing.xxl2),
                    tint = AuraThemeTokens.colors.error,
                )
            }
            if (contradiction.newerText.isNotBlank()) {
                Text(
                    contradiction.newerText,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
            if (contradiction.olderText.isNotBlank()) {
                Text(
                    "Earlier: ${contradiction.olderText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onDismiss(contradiction.id) }) { Text(stringResource(R.string.dismiss)) }
                OutlinedButton(onClick = { onResolve(contradiction.id) }) { Text(stringResource(R.string.use_newer)) }
            }
        }
    }
}

@Composable
private fun KgProposalCard(
    proposal: KgEdgeProposalEntity,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    AuraCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
        ) {
            Text(
                "${proposal.fromLabel}  →  ${proposal.toLabel}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Similarity ${"%.0f".format(proposal.similarity * 100)}% · ${proposal.proposedEdge}",
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onReject(proposal.id) }) { Text(stringResource(R.string.reject)) }
                OutlinedButton(onClick = { onAccept(proposal.id) }) { Text(stringResource(R.string.accept)) }
            }
        }
    }
}
