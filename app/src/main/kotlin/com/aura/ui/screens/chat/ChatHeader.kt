package com.aura.ui.screens.chat

import androidx.compose.ui.res.stringResource
import com.aura.R
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
import com.aura.ui.theme.AuraDimensions
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.InterDisplay
import com.aura.ui.util.agentDisplayName
import com.aura.ui.util.modelDisplayName
import com.aura.ui.theme.AuraSpacing
import androidx.compose.material3.HorizontalDivider
import com.aura.ui.components.AuraDropdownMenu
import com.aura.ui.components.AuraDropdownItem

@Composable
fun ChatHeader(
    activeModel: String,
    conversationModel: String? = null,
    activeAgent: AgentEntity? = null,
    availableAgents: List<AgentEntity> = emptyList(),
    /** Name of the project this conversation is attributed to, or null. */
    activeProject: String? = null,
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
    /**
     * Takes no default.
     *
     * A defaulted callback here would render a chip that opens nothing and looks
     * exactly like a working one — the shape that left `storySoFar` and
     * `retrieved` unsupplied in `SceneContextBuilder` for months. Removing the
     * wire is a compile error instead.
     */
    onShowProjectPicker: () -> Unit,
    onOpenCouncil: () -> Unit = {},
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    val selectedModel = conversationModel ?: activeModel
    val missingModel = selectedModel.isBlank()
    val displayModel = if (missingModel) "Choose model" else modelDisplayName(selectedModel)
    val sessionOverride = !conversationModel.isNullOrBlank() && conversationModel != activeModel
    val modesActive = deepModeEnabled || incognitoMode
    // The header is what opens the picker, so it has to read the same way the
    // picker does — otherwise tapping `phone_native` opens a sheet titled
    // "Phone Native".
    val activeAgentName = activeAgent?.name?.let(::agentDisplayName)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("chat-header"),
    ) {
        // What the two pills may occupy, after the controls that cannot shrink.
        //
        // `maxWidth * 0.55f` was the whole budget, applied to the model pill
        // *and* the project chip — 110% of the header between them, which only
        // ever fit because real model names are short. Given a long one the
        // model pill claimed its full 176dp at 320dp wide, and the three
        // fixed-width trailing buttons (history, new conversation, overflow)
        // were pushed off the edge: present in the tree, unreachable by thumb.
        //
        // `ChatHeaderTest` was written to catch exactly this — its name says
        // compact width keeps new-chat and overflow visible — and had never
        // been executed until the `app-instrumented` CI job existed.
        //
        // Subtracting the trailing controls first makes the pills yield to
        // them rather than the other way round. The 0.55 cap still applies on
        // wide screens, where there is room and halving the pill would be a
        // regression for no reason.
        //
        // Counted from what the Row actually draws, not from a literal. The
        // literal was 3, and the Row draws a fourth fixed-width control
        // whenever there are agents to pick from — which is always, because
        // `ProactiveBootstrap` seeds seven builtins on every startup. That put
        // the budget 48dp plus one gap short on every real install, and left
        // the overflow button at 0dp: present in the tree, unreachable by
        // thumb, which is the exact defect this budget was added to fix.
        //
        // It survived because `availableAgents` defaults to empty and
        // `ChatHeaderTest` never set it, so the test exercised the one
        // configuration production never has. The test now covers both.
        val agentPickerShown = availableAgents.isNotEmpty()
        val fixedControls = if (agentPickerShown) 4 else 3
        val gapCount = if (agentPickerShown) 6 else 5
        val trailingControls = AuraDimensions.minimumTouchTarget * fixedControls
        val headerChrome = AuraSpacing.xs * 2 + AuraSpacing.xxs * gapCount
        // No floor. `coerceAtLeast(112.dp)` was here so the pills could not
        // vanish on a narrow screen, and it is precisely what kept the defect
        // alive after the control count was corrected: at 320dp there is 88dp
        // left for both pills, the floor handed them 112dp anyway, and a Row
        // measures unweighted children in order — so the pills took width that
        // did not exist and the last child, the overflow button, absorbed the
        // shortfall. Measured on an emulator at 28dp, and 0dp at 280dp.
        //
        // A floor that can exceed the space available is not a floor, it is an
        // overdraft. The buttons cannot shrink and the pills can, so the pills
        // yield — which is what the paragraph above always claimed this did.
        val pillBudget = (maxWidth - trailingControls - headerChrome).coerceAtLeast(0.dp)
        val maxPillWidth = minOf(maxWidth * 0.55f, pillBudget / 2)
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

            // The project this conversation is attributed to.
            //
            // Always rendered, including when nothing is attributed, and that is
            // the point: attribution is sticky, so the one failure mode that
            // matters is a conversation quietly inheriting the wrong project and
            // writing into its ledger. A chip that disappears when unset would
            // make "no project" and "some project you have forgotten about" look
            // identical. Reads "Project" when unset so the affordance is legible
            // before there is anything to show.
            Surface(
                modifier = Modifier
                    .widthIn(max = maxPillWidth)
                    .heightIn(min = AuraSpacing.xxl)
                    .testTag("chat-project-pill")
                    .clickable(onClick = onShowProjectPicker),
                color = AuraThemeTokens.colors.surface2,
                shape = RoundedCornerShape(AuraSpacing.xl2),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AuraSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = activeProject ?: stringResource(R.string.project),
                        modifier = Modifier
                            .widthIn(max = maxLabelWidth)
                            .testTag(if (activeProject != null) "chat-project-set" else "chat-project-none"),
                        color = if (activeProject != null) AuraThemeTokens.colors.textPrimary
                        else AuraThemeTokens.colors.textTertiary,
                        fontFamily = InterDisplay,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.change_project),
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
                AuraDropdownMenu(
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
) = AuraDropdownItem(
    label = label,
    icon = icon,
    onClick = onClick,
    supporting = supporting,
    destructive = destructive,
)
