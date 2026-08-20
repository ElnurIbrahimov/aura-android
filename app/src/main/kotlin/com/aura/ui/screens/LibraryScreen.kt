@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.aura.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.R
import com.aura.library.LibraryItem
import com.aura.library.LibraryKind
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.LibraryViewModel

/**
 * Everything Aura has made, newest first.
 *
 * Aura's outputs used to dissolve into whichever feature produced them — an image into the
 * chat it was generated in, a scene into a creative project, a document into an import
 * dialog. Each was persisted correctly and none of them met, so there was no answer to
 * "what has this thing actually made for me".
 *
 * A list rather than a grid. Most of what is here is text — documents and scenes — and a
 * grid of mostly-blank tiles to accommodate the images would make the common case worse to
 * read for the sake of the rarer one.
 */
@Composable
fun LibraryScreen(
    onBack: () -> Unit = {},
    onOpen: (LibraryItem) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.availableKinds.size > 1) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                ) {
                    FilterChip(
                        selected = state.filter == null,
                        onClick = { viewModel.setFilter(null) },
                        label = { Text(stringResource(R.string.everything)) },
                    )
                    // Only the kinds actually present. A chip that filters to nothing is a
                    // chip that looks broken.
                    state.availableKinds.forEach { kind ->
                        FilterChip(
                            selected = state.filter == kind,
                            onClick = { viewModel.setFilter(kind) },
                            label = { Text(kind.label()) },
                        )
                    }
                }
                Spacer(Modifier.height(AuraSpacing.xs))
            }

            if (!state.loading && state.items.isEmpty()) {
                Text(
                    stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraThemeTokens.colors.textSecondary,
                    modifier = Modifier.padding(AuraSpacing.md),
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(AuraSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
            ) {
                // Keyed, so a refresh that prepends does not rebuild every visible row —
                // the bug ProactiveHistoryScreen had until recently, in a list that grows
                // exactly the same way.
                items(state.items, key = { it.id }) { item ->
                    LibraryRow(item = item, onClick = { onOpen(item) })
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem, onClick: () -> Unit) {
    val colors = AuraThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryKind.label(): String = when (this) {
    LibraryKind.IMAGE -> stringResource(R.string.library_images)
    LibraryKind.DOCUMENT -> stringResource(R.string.library_documents)
    LibraryKind.WRITING -> stringResource(R.string.library_writing)
    LibraryKind.OTHER -> stringResource(R.string.library_other)
}

@Composable
private fun stringResource(id: Int): String = androidx.compose.ui.res.stringResource(id)
