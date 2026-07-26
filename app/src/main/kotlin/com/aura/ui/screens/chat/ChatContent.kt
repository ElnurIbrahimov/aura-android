package com.aura.ui.screens.chat

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.Specialist
import com.aura.ui.components.AgentChip
import com.aura.ui.components.EmptyChatState
import com.aura.ui.components.FollowUpSuggestionChips
import com.aura.ui.components.MoaThinkingIndicator
import com.aura.ui.components.QuickChipRow
import com.aura.ui.components.SpecialistChips
import com.aura.ui.components.VisionPromptChips
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.InterDisplay
import com.aura.ui.viewmodel.ChatUiState
import com.aura.ui.viewmodel.ModelSelectionState

@Composable
fun ChatContent(
    state: ChatUiState,
    listState: LazyListState,
    showJumpToLatest: Boolean,
    onJumpToLatest: () -> Unit,
    onShowModelPicker: () -> Unit,
    onToggleTts: () -> Unit,
    onHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: () -> Unit,
    onToggleDeepMode: () -> Unit,
    onToggleIncognito: () -> Unit,
    onRegenerate: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onEditMessage: (Int, String) -> Unit = { _, _ -> },
    onShareMessage: (String) -> Unit = {},
    onStopTts: () -> Unit = {},
    onSendSuggestion: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onDismissProviderWarning: () -> Unit,
    onDismissSaveWarning: () -> Unit,
    onShowAgentPicker: () -> Unit = {},
    onOpenCouncil: () -> Unit = {},
    onRunVisionPrompt: (android.graphics.Bitmap, String) -> Unit,
    onDismissVision: () -> Unit,
    onShowSources: () -> Unit,
    onReact: (Long, com.aura.agent.Reaction) -> Unit = { _, _ -> },
    composer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(AuraThemeTokens.colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                activeModel = state.activeModel,
                conversationModel = state.conversation.model,
                activeAgent = state.activeAgent,
                availableAgents = state.availableAgents,
                streaming = state.streaming,
                ttsEnabled = state.ttsEnabled,
                deepModeEnabled = state.deepModeEnabled,
                deepModeActive = state.deepModeActive,
                incognitoMode = state.incognitoMode,
                onToggleTts = onToggleTts,
                onHistory = onHistory,
                onNewConversation = onNewConversation,
                onDeleteConversation = onDeleteConversation,
                onToggleDeepMode = onToggleDeepMode,
                onToggleIncognito = onToggleIncognito,
                onRegenerate = onRegenerate,
                onExport = onExport,
                onClear = onClear,
                onShowModelPicker = onShowModelPicker,
                onShowAgentPicker = onShowAgentPicker,
                onOpenCouncil = onOpenCouncil,
            )

            if (!state.isOnline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AuraThemeTokens.colors.warning.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = AuraThemeTokens.colors.warning,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.you_re_offline),
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraThemeTokens.colors.warning,
                    )
                }
            }

            if (state.deepModeActive) {
                MoaThinkingIndicator(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            if (state.incognitoMode) IncognitoBanner()
            if (state.ttsState is com.aura.voice.TextToSpeech.State.Speaking) {
                TtsStopPill(onStop = onStopTts)
            }

            when {
                state.conversationLoading -> ChatTimeline(
                    state = state,
                    listState = listState,
                    modifier = Modifier.weight(1f),
                )
                state.conversation.turns.isEmpty() && !state.streaming -> {
                    EmptyChatState(Modifier.weight(1f))
                }
                else -> ChatTimeline(
                    state = state,
                    listState = listState,
                    onShowSourcesForLastTurn = onShowSources,
                    onSendSuggestion = onSendSuggestion,
                    onReact = onReact,
                    onEditMessage = onEditMessage,
                    onShareMessage = onShareMessage,
                    modifier = Modifier.weight(1f),
                )
            }

            state.error?.let { error ->
                ErrorBanner(
                    error = error,
                    retryable = state.errorRetryable,
                    typedError = state.errorTyped,
                    onRetry = onRetry,
                    onSwitchModel = onShowModelPicker,
                    onDismiss = onDismissError,
                )
            }
            state.providerWarning?.let { warning ->
                SaveWarningBanner(warning, onDismissProviderWarning)
            }
            state.saveWarning?.let { warning ->
                SaveWarningBanner(warning, onDismissSaveWarning)
            }

            if (state.draft.isNotBlank() || state.activeAgent != null || state.selectedSpecialist != null) {
                if (state.activeAgent != null) {
                    AgentChip(
                        agent = state.activeAgent,
                        onClick = onShowAgentPicker,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                SpecialistChips(
                    selected = state.selectedSpecialist
                        ?: state.availableAgents.find { it.id == state.activeAgentId }?.let { agent ->
                            com.aura.agent.Specialist.ALL.find { it.name.equals(agent.name, ignoreCase = true) }
                        },
                    suggested = state.suggestedSpecialist,
                    onSelect = { _ ->
                        // Specialist chips are now visualized as agent mappings.
                        // The agent picker sheet is the canonical selection surface.
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            state.pendingVisionBitmap?.let { bitmap ->
                VisionPromptChips(
                    onPick = { prompt -> onRunVisionPrompt(bitmap, prompt) },
                    onDismiss = onDismissVision,
                )
            }

            if (state.conversation.turns.isEmpty() && !state.streaming && !state.conversationLoading) {
                QuickChipRow(onPick = onSendSuggestion)
            } else if (showJumpToLatest) {
                JumpToLatest(onClick = onJumpToLatest)
            }

            when (val modelState = state.modelSelection) {
                is ModelSelectionState.Missing -> ModelSelectionBanner(
                    message = "Choose a model before sending.",
                    onChooseModel = onShowModelPicker,
                )
                is ModelSelectionState.Failed -> ModelSelectionBanner(
                    message = modelState.message,
                    onChooseModel = onShowModelPicker,
                )
                is ModelSelectionState.Loading -> ModelSelectionBanner(
                    message = "Refreshing available models…",
                    onChooseModel = onShowModelPicker,
                )
                is ModelSelectionState.Ready -> Unit
            }

            composer()
        }
    }
}

@Composable
private fun JumpToLatest(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            onClick = onClick,
            color = AuraThemeTokens.colors.surface2,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 4.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = AuraThemeTokens.colors.textSecondary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.jump_to_latest),
                    fontFamily = InterDisplay,
                    fontSize = 12.sp,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun TtsStopPill(onStop: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            onClick = onStop,
            shape = RoundedCornerShape(999.dp),
            color = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.12f),
            contentColor = AuraThemeTokens.colors.actionPrimary,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.3f),
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Stop,
                    contentDescription = "Stop reading",
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.tap_to_stop_reading),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun IncognitoBanner() {
    Surface(
        color = AuraThemeTokens.colors.warning.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.incognito_this_chat_is_not_saved),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ErrorBanner(
    error: String,
    retryable: Boolean,
    typedError: com.aura.core.error.AuraError?,
    onRetry: () -> Unit,
    onSwitchModel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val display = typedError?.formatUserMessage() ?: error
    Surface(
        color = AuraThemeTokens.colors.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = display,
                modifier = Modifier.weight(1f),
                color = AuraThemeTokens.colors.textPrimary,
                style = MaterialTheme.typography.bodySmall,
            )
            if (retryable || typedError?.retryable == true) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                TextButton(onClick = onSwitchModel) { Text(stringResource(R.string.switch_model)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

@Composable
private fun SaveWarningBanner(warning: String, onDismiss: () -> Unit) {
    Surface(
        color = AuraThemeTokens.colors.warning.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = warning,
                modifier = Modifier.weight(1f),
                color = AuraThemeTokens.colors.textPrimary,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

@Composable
private fun ModelSelectionBanner(message: String, onChooseModel: () -> Unit) {
    Surface(
        color = AuraThemeTokens.colors.error,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary,
            )
            TextButton(onClick = onChooseModel) { Text(stringResource(R.string.choose_model)) }
        }
    }
}
