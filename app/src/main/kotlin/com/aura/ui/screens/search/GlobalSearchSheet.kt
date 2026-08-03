package com.aura.ui.screens.search

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.GlobalSearchViewModel
import com.aura.ui.theme.AuraSpacing

/**
 * Cross-type search over conversations, memories, tasks, hands, skills, and
 * the knowledge graph. Opened from the search row at the top of Home.
 *
 * This is deliberately reachable from exactly one place. It used to be a
 * primary-colored FAB on every screen except Chat, which gave a secondary
 * utility the most prominent control in the app and stacked it on top of the
 * per-screen search fields that Memory, History, Hands, Reminders and the
 * knowledge graph already have. Home is the one tab with no search of its
 * own, and the only place where "find this, wherever it lives" is the real
 * question.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchSheet(
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: GlobalSearchViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AuraThemeTokens.colors

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.clear()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = colors.surface1,
    ) {
        Column(modifier = Modifier.padding(AuraSpacing.md)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text(stringResource(R.string.search_chats_memories_tasks)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("global-search-field"),
                singleLine = true,
            )

            // Category chips, shown only once there is something to filter.
            // Rendering them over an empty result set would be dead chrome.
            if (state.availableCategories.size > 1) {
                Row(
                    modifier = Modifier.padding(top = AuraSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                ) {
                    FilterChip(
                        selected = state.categoryFilter == null,
                        onClick = { viewModel.onCategoryFilterChange(null) },
                        label = { Text(stringResource(R.string.all)) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                    state.availableCategories.forEach { category ->
                        FilterChip(
                            selected = state.categoryFilter == category,
                            onClick = {
                                viewModel.onCategoryFilterChange(
                                    if (state.categoryFilter == category) null else category,
                                )
                            },
                            label = { Text(category.label) },
                            colors = FilterChipDefaults.filterChipColors(),
                        )
                    }
                }
            }

            when {
                state.searching && state.results.isEmpty() -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AuraSpacing.xl),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.visibleResults.isNotEmpty() -> {
                    LazyColumn(modifier = Modifier.padding(top = AuraSpacing.xs)) {
                        items(
                            state.visibleResults,
                            key = { "${it.category}_${it.id}" },
                        ) { result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.clear()
                                        onDismiss()
                                        onNavigate(result.route)
                                    }
                                    .padding(vertical = AuraSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = result.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textPrimary,
                                    )
                                    Text(
                                        text = "${result.category.label} · ${result.subtitle}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }

                state.query.isNotBlank() && !state.searching -> {
                    Text(
                        text = "No results for \"${state.query}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = AuraSpacing.lg),
                    )
                }

                else -> {
                    // Initial state. The sheet used to open completely blank,
                    // which read as broken rather than as waiting for input.
                    Text(
                        text = stringResource(R.string.search_across_your_chats_memories_tasks),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = AuraSpacing.lg),
                    )
                }
            }
        }
    }
}
