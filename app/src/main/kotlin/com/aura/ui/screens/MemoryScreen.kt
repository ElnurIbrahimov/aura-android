package com.aura.ui.screens

import com.aura.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.memory.MemoryEntity
import com.aura.ui.components.AuraScreenHeader
import com.aura.ui.components.SwipeToDeleteContainer
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.DocumentImportViewModel
import com.aura.ui.viewmodel.MemoryViewModel
import com.aura.ui.theme.AuraSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val MEMORY_CATEGORIES = listOf("fact", "preference", "episode", "person", "project", "idea", "task")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(
    onOpenKnowledgeGraph: () -> Unit = {},
    onOpenDreams: () -> Unit = {},
    onOpenSourceConversation: (String, Long) -> Unit = { _, _ -> },
    viewModel: MemoryViewModel = hiltViewModel(),
    documentViewModel: DocumentImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val documentState by documentViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    var showRebuildConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showClearCategoryConfirm by remember { mutableStateOf(false) }
    var showAddNote by remember { mutableStateOf(false) }
    var showDocuments by remember { mutableStateOf(false) }
    var showDreamSummaries by remember { mutableStateOf(false) }
    var historyMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { android.util.Log.w("MemoryScreen", "takePersistableUriPermission failed", it) }
        documentViewModel.import(uri)
    }

    LaunchedEffect(state.undoMessage) {
        val message = state.undoMessage ?: return@LaunchedEffect
        when (
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Undo",
                withDismissAction = true,
            )
        ) {
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.clearUndo()
        }
    }

    LaunchedEffect(documentState.message, documentState.error) {
        val notice = documentState.error ?: documentState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice, withDismissAction = true)
        documentViewModel.clearNotice()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AuraSpacing.xxl2)
        ) {
        val filterText = state.categoryFilter?.let { " · $it" } ?: ""
        val subtitle = when {
            state.memories.isEmpty() && state.query.isBlank() -> "No memories yet$filterText"
            state.memories.isEmpty() -> "No memories match your filters$filterText"
            state.memories.size == 1 -> "1 memory$filterText"
            else -> "${state.memories.size} memories$filterText"
        }
        AuraScreenHeader(title = "Memory", subtitle = subtitle)

        // Rebuild-embeddings banner — shown after the user has run the
        // action. Dismissible. The text comes from the VM so the
        // success / no-op / failure cases all render the same way.
        state.rebuildResult?.let { result ->
            Surface(
                color = AuraThemeTokens.colors.surface2,
                shape = RoundedCornerShape(AuraSpacing.medium),
                modifier = Modifier.fillMaxWidth().padding(bottom = AuraSpacing.xs),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { viewModel.clearRebuildResult() },
                        modifier = Modifier.size(AuraSpacing.lg),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = AuraThemeTokens.colors.textSecondary,
                            modifier = Modifier.size(AuraSpacing.xl2),
                        )
                    }
                }
            }
        }

        // Search bar
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.setQuery(it) },
            placeholder = { Text(stringResource(R.string.search_memories)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Spacer(modifier = Modifier.height(AuraSpacing.xs))

        // Category filter chips
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.small),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.small),
        ) {
            AssistChip(
                onClick = { viewModel.setCategory(null) },
                label = { Text(stringResource(R.string.all)) },
                colors = if (state.categoryFilter == null)
                    AssistChipDefaults.assistChipColors(containerColor = AuraThemeTokens.colors.actionPrimary, labelColor = AuraThemeTokens.colors.onActionPrimary)
                else AssistChipDefaults.assistChipColors(),
            )
            for (cat in MEMORY_CATEGORIES) {
                AssistChip(
                    onClick = { viewModel.setCategory(cat) },
                    label = { Text(cat) },
                    colors = if (state.categoryFilter == cat)
                        AssistChipDefaults.assistChipColors(containerColor = AuraThemeTokens.colors.actionPrimary, labelColor = AuraThemeTokens.colors.onActionPrimary)
                    else AssistChipDefaults.assistChipColors(),
                )
            }
        }


        // Manual note creation — bypasses the write gate so the user
        // can explicitly store anything they want without going through
        // the agent.
        Button(
            onClick = { showAddNote = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AuraSpacing.xl2),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(AuraSpacing.xl2))
            Spacer(Modifier.width(AuraSpacing.small))
            Text(stringResource(R.string.add_note))
        }
        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        OutlinedButton(
            onClick = { showDocuments = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AuraSpacing.xl2),
        ) {
            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(AuraSpacing.xl2))
            Spacer(Modifier.width(AuraSpacing.small))
            Text(
                if (documentState.documents.isEmpty()) "Import documents"
                else "Documents · ${documentState.documents.size}",
            )
        }
        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        OutlinedButton(
            onClick = onOpenKnowledgeGraph,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AuraSpacing.xl2),
        ) {
            Icon(Icons.Filled.AccountTree, contentDescription = null, modifier = Modifier.size(AuraSpacing.xl2))
            Spacer(Modifier.width(AuraSpacing.small))
            Text(stringResource(R.string.knowledge_graph))
        }

        if (state.memories.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AuraSpacing.xs))

            // Dream summaries stat row. Visible when the count is > 0
            // (so it doesn't show on a fresh install with no cycles
            // run). Tappable -> shows the full list of summaries in a
            // dialog. This is the user-visible signal that the
            // consolidator is doing its job.
            if (state.dreamSummaryCount > 0) {
                OutlinedButton(
                    onClick = { showDreamSummaries = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(AuraSpacing.xl2),
                    )
                    Spacer(Modifier.size(AuraSpacing.xs))
                    Text("${state.dreamSummaryCount} dream summaries")
                }
                Spacer(modifier = Modifier.height(AuraSpacing.xs))
            }

            // v2 phase stats: routines + contradictions. Tappable for
            // the future "open routines screen" but for now they're
            // just informative chips on the main Memory header.
            if (state.routineCount > 0 || state.contradictionCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AuraSpacing.xxs),
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                ) {
                    if (state.routineCount > 0) {
                        AssistChip(
                            onClick = onOpenDreams,
                            label = { Text("${state.routineCount} routines") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Repeat,
                                    contentDescription = null,
                                    modifier = Modifier.size(AuraSpacing.md),
                                )
                            },
                        )
                    }
                    if (state.contradictionCount > 0) {
                        AssistChip(
                            onClick = onOpenDreams,
                            label = { Text("${state.contradictionCount} contradictions") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(AuraSpacing.md),
                                )
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AuraSpacing.xs))
            }

            // Rebuild embeddings action. Visible only once there is something
            // to rebuild.
            OutlinedButton(
                onClick = { showRebuildConfirm = true },
                enabled = !state.rebuildInFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.rebuildInFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(AuraSpacing.md),
                        strokeWidth = AuraSpacing.tiny,
                        color = AuraThemeTokens.colors.actionPrimary,
                    )
                    Spacer(modifier = Modifier.size(AuraSpacing.xs))
                    Text(stringResource(R.string.rebuilding))
                } else {
                    Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = null,
                        modifier = Modifier.size(AuraSpacing.xl2),
                    )
                    Spacer(modifier = Modifier.size(AuraSpacing.xs))
                    Text(stringResource(R.string.rebuild_embeddings))
                }
            }
            Spacer(modifier = Modifier.height(AuraSpacing.xs))

            // Bulk delete actions. "Clear category" only shows when a
            // category filter is active. Both show a confirm dialog.
            if (state.categoryFilter != null) {
                OutlinedButton(
                    onClick = { showClearCategoryConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(AuraSpacing.xl2),
                        tint = AuraThemeTokens.colors.error,
                    )
                    Spacer(modifier = Modifier.size(AuraSpacing.xs))
                    Text("Clear ${state.categoryFilter} memories", color = AuraThemeTokens.colors.error)
                }
                Spacer(modifier = Modifier.height(AuraSpacing.xs))
            }
            OutlinedButton(
                onClick = { showClearAllConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(AuraSpacing.xl2),
                    tint = AuraThemeTokens.colors.error,
                )
                Spacer(modifier = Modifier.size(AuraSpacing.xs))
                Text(stringResource(R.string.clear_all_memories), color = AuraThemeTokens.colors.error)
            }
            Spacer(modifier = Modifier.height(AuraSpacing.xs))
        }

        Spacer(modifier = Modifier.height(AuraSpacing.xs))

        if (state.loading) {
            MemorySkeletonLoading()
        } else if (state.memories.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AuraSpacing.sm),
                shape = RoundedCornerShape(AuraSpacing.xxl2),
                color = AuraThemeTokens.colors.surface1.copy(alpha = 0.28f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = AuraSpacing.xl2, vertical = AuraSpacing.xl2),
                    verticalArrangement = Arrangement.spacedBy(AuraSpacing.small),
                ) {
                    Text(
                        text = if (state.query.isBlank()) "No memories yet" else "Nothing matches that search",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (state.query.isBlank()) {
                            "Add a note, or keep chatting and Aura will save facts, preferences, and episodes here."
                        } else {
                            "Try a different keyword or clear the category filter."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraThemeTokens.colors.textPrimary,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = AuraSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
            ) {
                items(state.memories, key = { it.id }) { mem ->
                    SwipeToDeleteContainer(onDelete = { viewModel.forget(mem.id) }) {
                        MemoryRow(
                            mem = mem,
                            onEdit = { editingMemory = mem },
                            onShowHistory = {
                                historyMemory = mem
                                viewModel.loadEditHistory(mem.id)
                            },
                            onForget = { viewModel.forget(mem.id) },
                            onOpenSource = {
                                if (mem.sourceConversationId.isNotBlank()) {
                                    onOpenSourceConversation(
                                        mem.sourceConversationId,
                                        mem.sourceTurnTimestamp,
                                    )
                                }
                            },
                            onFeedback = { helpful -> viewModel.submitFeedback(mem.id, helpful) },
                        )
                    }
                }
            }
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(AuraSpacing.md),
        )
    }

    // Edit dialog (rendered outside the main Box so it overlays everything)
    editingMemory?.let { mem ->
        EditMemoryDialog(
            memory = mem,
            onDismiss = { editingMemory = null },
            onSave = { newContent, newCategory, newImportance, newTags ->
                viewModel.update(mem.id, newContent, newCategory, newImportance, newTags)
                editingMemory = null
            },
        )
    }

    historyMemory?.let { memory ->
        MemoryHistoryDialog(
            memory = memory,
            entries = state.editHistory,
            loading = state.editHistoryLoading,
            onDismiss = {
                historyMemory = null
                viewModel.clearEditHistory()
            },
        )
    }

    if (showDocuments) {
        DocumentLibraryDialog(
            state = documentState,
            onImport = {
                documentPicker.launch(arrayOf(
                    "application/pdf",
                    com.aura.documents.DocumentTextExtractor.DOCX_MIME,
                    "text/*",
                    "application/json",
                ))
            },
            onOpen = { document ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(android.net.Uri.parse(document.sourceUri), document.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                }.onFailure { android.util.Log.w("MemoryScreen", "open document failed", it) }
            },
            onDelete = documentViewModel::delete,
            onDismiss = { showDocuments = false },
        )
    }

    // Rebuild confirmation dialog. The action is reversible (re-running
    // it on a clean table is a no-op) but for a 500-row install it
    // takes long enough that an accidental tap is annoying.
    if (showRebuildConfirm) {
        AlertDialog(
            onDismissRequest = { showRebuildConfirm = false },
            title = { Text(stringResource(R.string.rebuild_embeddings_2)) },
            text = {
                Text(
                    "Re-embed every memory that currently has a null embedding. " +
                        "Use this after restoring a backup — Aura intentionally " +
                        "drops embeddings on export because they're model-specific. " +
                        "Existing embeddings are left alone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRebuildConfirm = false
                    viewModel.rebuildEmbeddings()
                }) { Text(stringResource(R.string.rebuild)) }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Dream summaries dialog. Shows the list of consolidated
    // summaries so the user can verify the consolidator is doing
    // what it claims (paraphrases got merged into a single row).
    // Each row shows: the summary text, the source count, the
    // creation date. Source memories are NOT listed individually
    // (the dialog would be too long); tapping a summary could in
    // a future iteration show the source list, but v1 keeps it
    // simple.
    if (showDreamSummaries) {
        LaunchedEffect(Unit) { viewModel.loadDreamSummaries() }
        AlertDialog(
            onDismissRequest = { showDreamSummaries = false },
            title = { Text("Dream summaries (${state.dreamSummaryCount})") },
            text = {
                // Observable locale read: recomposes on system locale change,
                // unlike Locale.getDefault() called directly in composition.
                val dialogLocale: java.util.Locale = LocalConfiguration.current.locales[0]
                if (state.dreamSummariesLoading) {
                    Text(stringResource(R.string.loading))
                } else if (state.dreamSummaries.isEmpty()) {
                    Text(stringResource(R.string.no_dream_summaries_yet))
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        state.dreamSummaries.forEach { summary ->
                            Column(modifier = Modifier.padding(vertical = AuraSpacing.small)) {
                                Text(
                                    text = summary.compressedText,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "${summary.sourceCount} sources · " +
                                        java.text.SimpleDateFormat("MMM d, HH:mm", dialogLocale)
                                            .format(java.util.Date(summary.createdAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuraThemeTokens.colors.textSecondary,
                                )
                                if (summary.dominantTags.isNotEmpty()) {
                                    Text(
                                        text = summary.dominantTags,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AuraThemeTokens.colors.textTertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDreamSummaries = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    // Clear all memories confirmation. Irreversible.
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.clear_all_memories_2)) },
            text = {
                Text(
                    "This will permanently delete all ${state.memories.size} memories. " +
                        "This cannot be undone. Consider exporting a backup first.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllConfirm = false
                    viewModel.forgetAll()
                }) { Text(stringResource(R.string.delete_all), color = AuraThemeTokens.colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Clear category confirmation. Irreversible.
    if (showClearCategoryConfirm) {
        val cat = state.categoryFilter ?: ""
        val count = state.memories.size
        AlertDialog(
            onDismissRequest = { showClearCategoryConfirm = false },
            title = { Text("Clear $cat memories?") },
            text = {
                Text(
                    "This will permanently delete all $count memories in the " +
                        "\"$cat\" category. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearCategoryConfirm = false
                    viewModel.forgetByCategory(cat)
                }) { Text("Delete $count", color = AuraThemeTokens.colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCategoryConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showAddNote) {
        AddNoteDialog(
            onDismiss = { showAddNote = false },
            onAdd = { content, category, importance ->
                viewModel.createNote(content, category, importance)
                showAddNote = false
            },
        )
    }
}

@Composable
private fun MemoryRow(
    mem: MemoryEntity,
    onEdit: () -> Unit,
    onShowHistory: () -> Unit,
    onForget: () -> Unit,
    onOpenSource: () -> Unit,
    onFeedback: (Boolean) -> Unit,
) {
    val age = (System.currentTimeMillis() - mem.createdAt) / 1000
    val ageDisplay = when {
        age < 60 -> "just now"
        age < 3600 -> "${age / 60}m ago"
        age < 86400 -> "${age / 3600}h ago"
        else -> "${age / 86400}d ago"
    }

    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AuraSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryDot(mem.category)
            Spacer(modifier = Modifier.size(AuraSpacing.medium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mem.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraThemeTokens.colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(AuraSpacing.tiny))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mem.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
                    )
                    Text(
                        text = "  \u00B7  ${mem.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.4f),
                    )
                    Text(
                        text = "  \u00B7  $ageDisplay",
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
                    )
                    if (mem.decayScore < 0.5f) {
                        Text(
                            text = "  \u00B7  fading",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuraThemeTokens.colors.error.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                // Second metadata line: importance + recall count + tags.
                // These fields are used by the RRF retrieval engine but
                // were previously invisible to the user.
                val metaParts = mutableListOf<String>()
                if (mem.importance != 0.5f) {
                    metaParts += "${"%.0f".format(mem.importance * 100)}% important"
                }
                if (mem.accessCount > 0) {
                    metaParts += if (mem.accessCount == 1) "recalled 1\u00D7" else "recalled ${mem.accessCount}\u00D7"
                }
                if (mem.tags.isNotBlank()) {
                    metaParts += mem.tags
                }
                if (metaParts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AuraSpacing.tiny))
                    Text(
                        text = metaParts.joinToString("  \u00B7  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
                    )
                }
            }
            if (mem.sourceConversationId.isNotBlank()) {
                TextButton(onClick = onOpenSource) {
                    Text(stringResource(R.string.source))
                }
            }
            IconButton(onClick = onShowHistory) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = "Edit history",
                    tint = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit",
                    tint = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = onForget) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Forget",
                    tint = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = { onFeedback(true) }) {
                Icon(
                    imageVector = Icons.Filled.ThumbUp,
                    contentDescription = "Helpful",
                    tint = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = { onFeedback(false) }) {
                Icon(
                    imageVector = Icons.Filled.ThumbDown,
                    contentDescription = "Not helpful",
                    tint = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun EditMemoryDialog(
    memory: MemoryEntity,
    onDismiss: () -> Unit,
    onSave: (content: String, category: String, importance: Float, tags: String) -> Unit,
) {
    var content by remember(memory.id) { mutableStateOf(memory.content) }
    var category by remember(memory.id) { mutableStateOf(memory.category) }
    var importance by remember(memory.id) { mutableStateOf(memory.importance) }
    var tags by remember(memory.id) { mutableStateOf(memory.tags) }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_memory)) },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.content)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AuraSpacing.xs))
                Box {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        label = { Text(stringResource(R.string.category)) },
                        readOnly = true,
                        trailingIcon = {
                            TextButton(onClick = { categoryMenuOpen = true }) {
                                Text("\u25BE")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false },
                    ) {
                        for (cat in MEMORY_CATEGORIES) {
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    category = cat
                                    categoryMenuOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(AuraSpacing.xs))
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.tags_comma_separated)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AuraSpacing.xs))
                Text(
                    text = "Importance: ${"%.0f".format(importance * 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
                )
                Slider(
                    value = importance,
                    onValueChange = { importance = it },
                    valueRange = 0f..1f,
                    steps = 9,
                )
                Spacer(Modifier.height(AuraSpacing.xxs))
                Text(
                    text = stringResource(R.string.higher_importance_ranks_higher_in_recall),
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(content.trim(), category, importance, tags.trim()) },
                enabled = content.trim().isNotBlank() && (
                    content.trim() != memory.content ||
                    category != memory.category ||
                    importance != memory.importance ||
                    tags.trim() != memory.tags
                ),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun CategoryDot(category: String) {
    val color = when (category) {
        "preference" -> AuraThemeTokens.colors.actionPrimary
        "person" -> AuraThemeTokens.colors.assistantAccent
        "task" -> AuraThemeTokens.colors.assistantAccent
        "idea" -> AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.6f)
        else -> AuraThemeTokens.colors.borderDefault
    }
    Box(
        modifier = Modifier
            .size(AuraSpacing.medium)
            .background(color, CircleShape),
    )
}

// ── Skeleton Loading ─────────────────────────────────────────────────────────

/**
 * Skeleton loading placeholder for the memories list. Shows 5 card-shaped
 * placeholders with a pulse animation that mirrors the MemoryRow layout —
 * category dot, content line, metadata rows, and action icon placeholders.
 * The header, search bar, and filter chips remain visible throughout so the
 * transition to full content feels natural.
 */
@Composable
private fun MemorySkeletonLoading() {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900)),
        label = "pulse-alpha",
    )

    LazyColumn(
        contentPadding = PaddingValues(vertical = AuraSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
    ) {
        items(5) {
            SkeletonMemoryCard(alpha = pulseAlpha)
        }
    }
}

@Composable
private fun SkeletonMemoryCard(alpha: Float) {
    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = RoundedCornerShape(AuraSpacing.medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AuraSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Category dot placeholder
            Box(
                modifier = Modifier
                    .size(AuraSpacing.medium)
                    .background(
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.4f),
                        shape = CircleShape,
                    ),
            )
            Spacer(modifier = Modifier.size(AuraSpacing.medium))
            Column(modifier = Modifier.weight(1f)) {
                // Content line placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(AuraSpacing.large)
                        .background(
                            color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.35f),
                            shape = RoundedCornerShape(AuraSpacing.xxs),
                        ),
                )
                Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                // First metadata line: category · source · age
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(AuraSpacing.medium)
                            .background(
                                color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.2f),
                                shape = RoundedCornerShape(AuraSpacing.xxs),
                            ),
                    )
                    Spacer(modifier = Modifier.width(AuraSpacing.small))
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(AuraSpacing.medium)
                            .background(
                                color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.2f),
                                shape = RoundedCornerShape(AuraSpacing.xxs),
                            ),
                    )
                    Spacer(modifier = Modifier.width(AuraSpacing.small))
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(AuraSpacing.medium)
                            .background(
                                color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.2f),
                                shape = RoundedCornerShape(AuraSpacing.xxs),
                            ),
                    )
                }
                // Second metadata line: importance · recall count · tags
                Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(AuraSpacing.medium)
                            .background(
                                color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.15f),
                                shape = RoundedCornerShape(AuraSpacing.xxs),
                            ),
                    )
                    Spacer(modifier = Modifier.width(AuraSpacing.small))
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(AuraSpacing.medium)
                            .background(
                                color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.15f),
                                shape = RoundedCornerShape(AuraSpacing.xxs),
                            ),
                    )
                }
            }
            // Edit icon placeholder
            Box(
                modifier = Modifier
                    .size(AuraSpacing.lg)
                    .background(
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.15f),
                        shape = CircleShape,
                    ),
            )
            Spacer(modifier = Modifier.width(AuraSpacing.xxs))
            // Delete icon placeholder
            Box(
                modifier = Modifier
                    .size(AuraSpacing.lg)
                    .background(
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = alpha * 0.15f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}


@Composable
private fun AddNoteDialog(
    onDismiss: () -> Unit,
    onAdd: (content: String, category: String, importance: Float) -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("note") }
    var importance by remember { mutableStateOf(0.5f) }
    val categories = listOf("note", "fact", "preference", "person", "episode", "idea", "task")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_note)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.note_content)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    categories.forEach { cat ->
                        AssistChip(
                            onClick = { category = cat },
                            label = { Text(cat.replaceFirstChar { it.uppercase() }) },
                            colors = if (category == cat)
                                AssistChipDefaults.assistChipColors()
                            else
                                AssistChipDefaults.assistChipColors(
                                    containerColor = AuraThemeTokens.colors.surface1,
                                ),
                        )
                    }
                }
                Text("Importance: ${"%.1f".format(importance)}", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = importance,
                    onValueChange = { importance = it },
                    valueRange = 0f..1f,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(content, category, importance) },
                enabled = content.isNotBlank(),
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
