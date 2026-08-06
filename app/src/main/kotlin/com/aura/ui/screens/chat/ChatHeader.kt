package com.aura.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.People
import com.aura.agent.AgentEntity
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.ui.components.AuraIconButton
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.InterDisplay
import com.aura.ui.util.modelDisplayName
import com.aura.ui.theme.AuraSpacing
import androidx.compose.material3.HorizontalDivider

@Composable
fun ChatHeader(
    activeModel: String,
    conversationModel: String? = null,
    activeAgent: AgentEntity? = null,
    availableAgents: List<AgentEntity> = emptyList(),
    streaming: Boolean = false,
    ttsEnabled: Boolean = false,
    deepModeEnabled: Boolean = false,
    deepModeActive: Boolean = false,
    incognitoMode: Boolean = false,
    onToggleTts: () -> Unit = {},
    onHistory: () -> Unit = {},
    onNewConversation: () -> Unit = {},
    onDeleteConversation: () -> Unit = {},
    onToggleDeepMode: () -> Unit = {},
    onToggleIncognito: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onExport: () -> Unit = {},
    onClear: () -> Unit = {},
    onShowModelPicker: () -> Unit = {},
    onShowAgentPicker: () -> Unit = {},
    onOpenCouncil: () -> Unit = {},
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    val selectedModel = conversationModel ?: activeModel
    val missingModel = selectedModel.isBlank()
    val displayModel = if (missingModel) "Choose model" else modelDisplayName(selectedModel)
    val sessionOverride = !conversationModel.isNullOrBlank() && conversationModel != activeModel
    val modesActive = deepModeEnabled || incognitoMode
    val activeAgentName = activeAgent?.name

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("chat-header"),
    ) {
        val maxPillWidth = maxWidth * 0.55f
        val maxLabelWidth = (maxPillWidth - 52.dp).coerceAtLeast(56.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = AuraSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = maxPillWidth)
                    .heightIn(min = AuraSpacing.xxl)
                    .testTag("chat-model-pill")
                    .clickable(onClick = onShowModelPicker),
                color = AuraThemeTokens.colors.surface2,
                shape = RoundedCornerShape(AuraSpacing.xl2),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AuraSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    missingModel -> AuraThemeTokens.colors.warning
                                    sessionOverride -> AuraThemeTokens.colors.info
                                    else -> AuraThemeTokens.colors.actionPrimary
                                },
                            )
                            .testTag(
                                when {
                                    missingModel -> "chat-model-missing"
                                    sessionOverride -> "chat-session-override"
                                    else -> "chat-model-ready"
                                },
                            ),
                    )
                    Spacer(Modifier.width(AuraSpacing.small))
                    Column {
                        Text(
                            text = activeAgentName ?: displayModel,
                            modifier = Modifier.widthIn(max = maxLabelWidth),
                            color = if (sessionOverride) AuraThemeTokens.colors.info
                            else AuraThemeTokens.colors.textPrimary,
                            fontFamily = InterDisplay,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (activeAgentName != null) {
                            Text(
                                text = displayModel,
                                modifier = Modifier.widthIn(max = maxLabelWidth),
                                color = AuraThemeTokens.colors.textTertiary,
                                fontFamily = InterDisplay,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = if (activeAgentName != null) "Change agent" else "Change model",
                        tint = AuraThemeTokens.colors.textTertiary,
                        modifier = Modifier.size(AuraSpacing.xl2),
                    )
                }
            }

            if (availableAgents.isNotEmpty()) {
                AuraIconButton(
                    onClick = onShowAgentPicker,
                    containerColor = AuraThemeTokens.colors.surface1,
                ) {
                    Icon(
                        imageVector = Icons.Filled.People,
                        contentDescription = "Select agent",
                        tint = if (activeAgent != null) AuraThemeTokens.colors.actionPrimary
                        else AuraThemeTokens.colors.textPrimary,
                        modifier = Modifier.size(AuraSpacing.xxl2),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Past conversations get their own control rather than sitting
            // fifth in an overflow menu. Reaching earlier chats is a
            // primary move in any assistant, and buried under "⋮" it read
            // as though the app simply had no history.
            AuraIconButton(
                onClick = onHistory,
                containerColor = AuraThemeTokens.colors.surface1,
                contentDescription = "Conversation history",
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = AuraThemeTokens.colors.textPrimary,
                    modifier = Modifier.size(AuraSpacing.xxl2),
                )
            }

            AuraIconButton(
                onClick = onNewConversation,
                enabled = !streaming,
                containerColor = AuraThemeTokens.colors.surface1,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "New conversation",
                    tint = AuraThemeTokens.colors.textPrimary,
                    modifier = Modifier.size(AuraSpacing.xxl2),
                )
            }

            Box {
                AuraIconButton(
                    onClick = { overflowExpanded = true },
                    containerColor = if (modesActive) {
                        AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.14f)
                    } else {
                        AuraThemeTokens.colors.surface1
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More chat actions",
                        tint = if (modesActive) AuraThemeTokens.colors.actionPrimary
                        else AuraThemeTokens.colors.textPrimary,
                        modifier = Modifier.size(AuraSpacing.xxl2),
                    )
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                ) {
                    ChatMenuItem(
                        label = if (ttsEnabled) "Turn off read aloud" else "Read responses aloud",
                        icon = if (ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        onClick = {
                            overflowExpanded = false
                            onToggleTts()
                        },
                    )
                    ChatMenuItem(
                        label = if (deepModeEnabled) "Turn off Deep mode" else "Use Deep mode",
                        icon = Icons.Filled.AutoAwesome,
                        supporting = if (deepModeActive) "Thinking now" else null,
                        onClick = {
                            overflowExpanded = false
                            onToggleDeepMode()
                        },
                    )
                    ChatMenuItem(
                        label = if (incognitoMode) "Leave Incognito" else "Use Incognito",
                        icon = Icons.Filled.VisibilityOff,
                        onClick = {
                            overflowExpanded = false
                            onToggleIncognito()
                        },
                    )
                    // Modes above, everything else below. Nine items ran as
                    // one undifferentiated column, so toggles, navigation
                    // and destructive actions all looked alike.
                    HorizontalDivider(color = AuraThemeTokens.colors.borderSubtle)
                    ChatMenuItem(
                        label = "Agent council",
                        icon = Icons.Filled.Groups,
                        onClick = {
                            overflowExpanded = false
                            onOpenCouncil()
                        },
                    )
                    ChatMenuItem(
                        label = "Regenerate",
                        icon = Icons.Filled.Refresh,
                        onClick = {
                            overflowExpanded = false
                            onRegenerate()
                        },
                    )
                    ChatMenuItem(
                        label = "Export as Markdown",
                        icon = Icons.Filled.FileDownload,
                        onClick = {
                            overflowExpanded = false
                            onExport()
                        },
                    )
                    // Destructive actions sit below their own rule. Both of
                    // these discard the conversation and were previously
                    // flush against Export, one slip away from a toggle.
                    HorizontalDivider(color = AuraThemeTokens.colors.borderSubtle)
                    ChatMenuItem(
                        label = "Clear chat",
                        icon = Icons.Filled.CleaningServices,
                        destructive = true,
                        onClick = {
                            overflowExpanded = false
                            onClear()
                        },
                    )
                    ChatMenuItem(
                        label = "Delete conversation",
                        icon = Icons.Filled.DeleteOutline,
                        destructive = true,
                        onClick = {
                            overflowExpanded = false
                            onDeleteConversation()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    supporting: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) AuraThemeTokens.colors.error else AuraThemeTokens.colors.textPrimary
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = tint)
                if (supporting != null) {
                    Text(
                        text = " · $supporting",
                        color = AuraThemeTokens.colors.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, tint = tint)
        },
        onClick = onClick,
    )
}
