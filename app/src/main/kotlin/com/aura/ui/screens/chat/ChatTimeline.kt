package com.aura.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aura.agent.Reaction
import com.aura.ui.components.AuraSkeleton
import com.aura.ui.components.FollowUpSuggestionChips
import com.aura.ui.components.FollowUpSuggestions
import com.aura.ui.components.MemoryRecallChip
import com.aura.ui.components.MessageBubble
import com.aura.ui.components.ThinkingShimmer
import com.aura.ui.components.ToolCallBadge
import com.aura.ui.components.ToolCallState
import com.aura.ui.viewmodel.ChatUiState
import kotlinx.coroutines.delay
import com.aura.ui.theme.AuraSpacing

@Composable
fun ChatTimeline(
    state: ChatUiState,
    listState: LazyListState,
    onSendSuggestion: (String) -> Unit = {},
    onShowSourcesForLastTurn: () -> Unit = {},
    onReact: (Long, Reaction) -> Unit = { _, _ -> },
    onEditMessage: (Int, String) -> Unit = { _, _ -> },
    onShareMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.conversationLoading) {
        ChatResumeLoading(modifier)
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .testTag("chat-timeline"),
        contentPadding = PaddingValues(horizontal = AuraSpacing.md, vertical = AuraSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.medium),
    ) {
        itemsIndexed(
            items = state.conversation.turns,
            key = { index, turn -> "${turn.timestamp}-$index" },
        ) { index, turn ->
            val alreadyShown = remember(index, state.conversation.turns.size) {
                index < state.conversation.turns.size - 1
            }
            val visible = remember { mutableStateOf(alreadyShown) }
            LaunchedEffect(turn, state.streaming) {
                if (!visible.value) {
                    delay(20L)
                    visible.value = true
                }
            }
            AnimatedVisibility(
                visible = visible.value,
                enter = fadeIn() + slideInVertically { it / 4 },
            ) {
                Column {
                    turn.user?.let {
                        MessageBubble(
                            text = it,
                            isUser = true,
                            timestamp = turn.timestamp,
                            onEdit = { onEditMessage(index, it) },
                        )
                    }
                    turn.assistant?.let { rawAssistant ->
                        val isLast = turn === state.conversation.turns.lastOrNull()
                        val isStreaming = state.streaming && isLast
                        val agentName = turn.agentId?.let { id ->
                            state.availableAgents.find { it.id == id }?.name
                        }
                        // Strip canvas blocks from chat text so they
                        // don't render as code blocks (they open as
                        // CanvasSheet instead).
                        val assistant = if (!isStreaming) {
                            com.aura.ui.screens.canvas.stripCanvasBlocks(rawAssistant)
                        } else rawAssistant
                        MessageBubble(
                            text = assistant,
                            isUser = false,
                            citations = turn.citations,
                            isStreaming = isStreaming,
                            timestamp = turn.timestamp,
                            modelLabel = state.conversation.model,
                            agentName = agentName,
                            durationMs = if (isLast) state.lastResponseDurationMs else 0L,
                            reaction = turn.reaction,
                            generatedImages = turn.generatedImages,
                            thinking = turn.thinking,
                            onShowSources = onShowSourcesForLastTurn,
                            onReact = { reaction -> onReact(turn.timestamp, reaction) },
                            onShare = { onShareMessage(assistant) },
                        )
                        if (isLast && !isStreaming) {
                            FollowUpSuggestionChips(
                                suggestions = FollowUpSuggestions.suggest(
                                    assistantText = assistant,
                                    isCodey = assistant.contains("```") || assistant.contains("`"),
                                ),
                                onPick = onSendSuggestion,
                            )
                        }
                        turn.recall?.let { MemoryRecallChip(recall = it) }
                    }
                    turn.toolTurns.forEach { toolTurn ->
                        if (toolTurn.result.isNotEmpty()) {
                            val toolState = if (toolTurn.result.startsWith("Tool errored:")) {
                                ToolCallState.Failed(
                                    name = toolTurn.name,
                                    args = toolTurn.args,
                                    error = toolTurn.result.removePrefix("Tool errored:").trim(),
                                )
                            } else {
                                ToolCallState.Done(
                                    name = toolTurn.name,
                                    args = toolTurn.args,
                                    result = toolTurn.result,
                                )
                            }
                            ToolCallBadge(state = toolState)
                        }
                    }
                    if (turn === state.conversation.turns.lastOrNull() && state.streaming) {
                        state.inFlightToolCalls.forEach { inFlight ->
                            ToolCallBadge(state = ToolCallState.Running(inFlight))
                        }
                    }
                }
            }
        }
        if (state.streaming && state.conversation.turns.lastOrNull()?.assistant.isNullOrBlank()) {
            item(key = "typing") { ThinkingShimmer() }
        }
    }
}

@Composable
private fun ChatResumeLoading(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xxl2)
            .testTag("chat-conversation-loading"),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.large),
    ) {
        AuraSkeleton(height = 42.dp, widthFraction = 0.58f)
        AuraSkeleton(height = 94.dp, widthFraction = 0.88f)
        AuraSkeleton(height = 36.dp, widthFraction = 0.46f)
        AuraSkeleton(height = 126.dp)
    }
}
