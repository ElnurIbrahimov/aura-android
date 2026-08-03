package com.aura.ui.screens

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.viewmodel.ProfileEvent
import com.aura.ui.viewmodel.ProfileViewModel

import com.aura.ui.theme.AuraThemeTokens
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var name by remember(state.name) { mutableStateOf(state.name) }
    var traitInput by remember { mutableStateOf("") }
    var factInput by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // One-shot feedback events from the VM. The Channel-backed flow
    // delivers each event exactly once, even across config changes.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is ProfileEvent.Saved -> event.message
                is ProfileEvent.Removed -> event.message
                ProfileEvent.Cleared -> "Profile cleared"
                ProfileEvent.Duplicate -> "Already in your list"
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuraScreenShell(
            title = stringResource(R.string.profile),
            subtitle = "User profile and facts",
            action = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                TextButton(onClick = { showClearDialog = true }) {
                    Text(stringResource(R.string.clear), color = AuraThemeTokens.colors.error)
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = AuraSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.md),
            ) {
            item {
                Spacer(modifier = Modifier.height(AuraSpacing.xs))
                Text(
                    text = stringResource(R.string.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.what_aura_should_call_you)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    onClick = { viewModel.setName(name) },
                    enabled = name != state.name,
                    modifier = Modifier.padding(top = AuraSpacing.xxs),
                ) {
                    Text(stringResource(R.string.save_name))
                }
            }

            item {
                Text(
                    text = stringResource(R.string.traits),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.short_labels_that_shape_aura_s),
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                    modifier = Modifier.padding(top = AuraSpacing.xs),
                ) {
                    state.traits.forEach { trait ->
                        InputChip(
                            selected = false,
                            onClick = { },
                            label = { Text(trait) },
                            trailingIcon = {
                                IconButton(onClick = { viewModel.removeTrait(trait) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove trait")
                                }
                            }
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = AuraSpacing.xs),
                ) {
                    OutlinedTextField(
                        value = traitInput,
                        onValueChange = { traitInput = it },
                        label = { Text(stringResource(R.string.new_trait)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            viewModel.addTrait(traitInput)
                            traitInput = ""
                        },
                        enabled = traitInput.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add trait")
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.facts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.things_aura_has_learned_about_you),
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
                )
                Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                    state.facts.forEach { fact ->
                        Surface(
                            color = AuraThemeTokens.colors.surface1,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(AuraSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "• $fact",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                IconButton(onClick = { viewModel.removeFact(fact) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove fact")
                                }
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = AuraSpacing.xs),
                ) {
                    OutlinedTextField(
                        value = factInput,
                        onValueChange = { factInput = it },
                        label = { Text(stringResource(R.string.new_fact)) },
                        modifier = Modifier.weight(1f),
                        minLines = 2,
                        maxLines = 4,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            viewModel.addFact(factInput)
                            factInput = ""
                        },
                        enabled = factInput.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add fact")
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(AuraSpacing.lg)) }
        }
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_profile)) },
            text = { Text(stringResource(R.string.this_removes_your_learned_name_traits)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clear()
                        name = ""
                        showClearDialog = false
                    }
                ) {
                    Text(stringResource(R.string.clear), color = AuraThemeTokens.colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
