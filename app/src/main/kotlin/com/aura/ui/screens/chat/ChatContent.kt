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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.ui.theme.JetBrainsMono
import androidx.compose.ui.unit.sp
import com.aura.agent.Specialist
import com.aura.ui.components.AgentChip
import com.aura.ui.components.EmptyChatState
import com.aura.ui.components.FollowUpSuggestionChips
import com.aura.ui.components.MoaThinkingIndicator
import com.aura.ui.components.QuickChipRow
import com.aura.ui.components.VisionPromptChips
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.InterDisplay
import com.aura.ui.viewmodel.ChatUiState
import com.aura.ui.viewmodel.ModelSelectionState
import com.aura.ui.theme.AuraSpacing

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
    /** Idle-time prepared question (ProAct chip). Null hides the chip. */
    preparedQuestion: String? = null,
    /** Something Aura wants to know. Null hides the card. */
    openQuestion: String? = null,
    onAnswerOpenQuestion: (String) -> Unit = {},
    onSnoozeOpenQuestion: () -> Unit = {},
    onNeverAskOpenQuestion: () -> Unit = {},
    onSendPrepared: () -> Unit = {},
    onDismissPrepared: () -> Unit = {},
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
                        .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(AuraSpacing.md),
                        tint = AuraThemeTokens.colors.warning,
                    )
                    Spacer(Modifier.width(AuraSpacing.xs))
                    Text(
                        text = stringResource(R.string.you_re_offline),
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraThemeTokens.colors.warning,
                    )
                }
            }

            if (state.deepModeActive) {
                MoaThinkingIndicator(Modifier.padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.small))
            }
            if (state.streamingThinking.isNotBlank()) {
                ThinkingBlock(text = state.streamingThinking)
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

            if (state.draft.isNotBlank() || state.activeAgent != null) {
                state.activeAgent?.let { agent ->
                    AgentChip(
                        agent = agent,
                        onClick = onShowAgentPicker,
                        modifier = Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xxs),
                    )
                }
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

            preparedQuestion?.let { q ->
                PreparedQuestionChip(
                    question = q,
                    onSend = onSendPrepared,
                    onDismiss = onDismissPrepared,
                )
            }

            openQuestion?.let { q ->
                com.aura.ui.components.OpenQuestionCard(
                    question = q,
                    onAnswer = onAnswerOpenQuestion,
                    onNotNow = onSnoozeOpenQuestion,
                    onNeverAsk = onNeverAskOpenQuestion,
                )
            }

            composer()
        }
    }
}

@Composable
private fun PreparedQuestionChip(
    question: String,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AuraThemeTokens.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xxs),
        color = colors.surface1,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
        onClick = onSend,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = colors.assistantAccent,
                modifier = Modifier.size(AuraSpacing.md),
            )
            Spacer(modifier = Modifier.width(AuraSpacing.sm))
            Text(
                text = "Aura pre-researched: $question",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss prepared suggestion",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(AuraSpacing.md),
                )
            }
        }
    }
}

@Composable
private fun JumpToLatest(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = AuraSpacing.xxs),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            onClick = onClick,
            color = AuraThemeTokens.colors.surface2,
            shape = RoundedCornerShape(AuraSpacing.md),
            shadowElevation = AuraSpacing.xxs,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.small),
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(AuraSpacing.large),
                    tint = AuraThemeTokens.colors.textSecondary,
                )
                Spacer(Modifier.width(AuraSpacing.xxs))
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
            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.small),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            onClick = onStop,
            shape = RoundedCornerShape(999.dp),
            color = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.12f),
            contentColor = AuraThemeTokens.colors.actionPrimary,
            border = androidx.compose.foundation.BorderStroke(
                AuraSpacing.hairline,
                AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.3f),
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = AuraSpacing.large, vertical = AuraSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.small),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Stop,
                    contentDescription = "Stop reading",
                    modifier = Modifier.size(AuraSpacing.large),
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
        shape = RoundedCornerShape(AuraSpacing.xs),
        modifier = Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xxs),
    ) {
        Text(
            text = stringResource(R.string.incognito_this_chat_is_not_saved),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary,
            modifier = Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xs),
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
    val colors = AuraThemeTokens.colors
    val presented = remember(error, typedError) {
        typedError?.formatUserMessage()?.let { ErrorPresentation(it, error.takeIf { raw -> raw != it }) }
            ?: presentError(error)
    }
    var detailsOpen by remember(error) { mutableStateOf(false) }

    // A tinted surface with a coloured edge, not a saturated slab. The old
    // banner filled with colors.error at full strength, which made a
    // recoverable "pick another model" the loudest thing on the screen.
    Surface(
        color = colors.error.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xxs),
        shape = RoundedCornerShape(AuraSpacing.xs),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(AuraSpacing.tiny)
                    .fillMaxHeight()
                    .background(colors.error),
            )
            Column(
                Modifier.padding(
                    start = AuraSpacing.sm,
                    end = AuraSpacing.xxs,
                    top = AuraSpacing.small,
                    bottom = AuraSpacing.xxs,
                ),
            ) {
                Text(
                    text = presented.headline,
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (detailsOpen && presented.details != null) {
                    Text(
                        text = presented.details,
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMono,
                        modifier = Modifier.padding(top = AuraSpacing.xs),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (retryable || typedError?.retryable == true) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                        TextButton(onClick = onSwitchModel) { Text(stringResource(R.string.switch_model)) }
                    }
                    if (presented.details != null) {
                        TextButton(onClick = { detailsOpen = !detailsOpen }) {
                            Text(if (detailsOpen) "Hide details" else "Details")
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }
    }
}

@Composable
private fun SaveWarningBanner(warning: String, onDismiss: () -> Unit) {
    Surface(
        color = AuraThemeTokens.colors.warning.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xxs),
        shape = RoundedCornerShape(AuraSpacing.xs),
    ) {
        Row(
            Modifier.padding(start = AuraSpacing.sm, end = AuraSpacing.xxs, top = AuraSpacing.small, bottom = AuraSpacing.small),
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
        shape = RoundedCornerShape(AuraSpacing.sm),
        modifier = Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xxs),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xs),
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

@Composable
private fun ThinkingBlock(text: String) {
    var expanded by remember(text.isNotEmpty()) { mutableStateOf(false) }
    val colors = AuraThemeTokens.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xxs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
        ) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = colors.assistantAccent,
                modifier = Modifier.size(AuraSpacing.md),
            )
            Text(
                text = if (expanded) "Thinking" else "Thinking\u2026",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.textTertiary,
                modifier = Modifier.size(AuraSpacing.xl2),
            )
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = AuraSpacing.xxs),
                color = colors.surface0,
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        modifier = Modifier.padding(AuraSpacing.sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}
