package com.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.AgentEntity
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.util.agentDisplayName

/**
 * Bottom sheet that lets the user pick an active [AgentEntity] for the current
 * chat. A "No agent" row returns to the default persona.
 *
 * Built to read like [ModelPickerSheet], which is the other thing reached from
 * the same header — same title-and-count heading, same accent-and-tick
 * selection, same absence of dividers. This one used to be a Material radio
 * list with a rule under every row and the same grey person glyph seven times,
 * which made a screen of near-identical rows out of seven agents that each
 * already carry their own emoji.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPickerSheet(
    currentAgent: AgentEntity?,
    agents: List<AgentEntity>,
    onPick: (AgentEntity?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AuraThemeTokens.colors.surface1,
    ) {
        AgentPickerContent(
            currentAgent = currentAgent,
            agents = agents,
            onPick = onPick,
            onDismiss = onDismiss,
        )
    }
}

/** Picker body without the modal window, for previews and UI tests. */
@Composable
fun AgentPickerContent(
    currentAgent: AgentEntity?,
    agents: List<AgentEntity>,
    onPick: (AgentEntity?) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp, max = 560.dp)
            .padding(horizontal = AuraSpacing.xxl2, vertical = AuraSpacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = AuraSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Select agent",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AuraThemeTokens.colors.textPrimary,
                )
                Text(
                    text = currentAgent?.let { "Answering as ${agentDisplayName(it.name)}" }
                        ?: "${agents.size} available · answering as Aura",
                    style = MaterialTheme.typography.labelMedium,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = AuraSpacing.xs)) {
            item(key = "no-agent") {
                AgentRow(
                    emoji = null,
                    name = "No agent",
                    description = "Aura's own voice, with every tool available",
                    accent = AuraThemeTokens.colors.actionPrimary,
                    selected = currentAgent == null,
                    onClick = {
                        onPick(null)
                        onDismiss()
                    },
                )
            }
            items(agents, key = { it.id }) { agent ->
                AgentRow(
                    emoji = agent.icon.takeIf { it.isNotBlank() },
                    name = agentDisplayName(agent.name),
                    description = agent.description,
                    accent = agentAccent(agent.color),
                    selected = agent.id == currentAgent?.id,
                    onClick = {
                        onPick(agent)
                        onDismiss()
                    },
                )
            }
        }
        Spacer(Modifier.height(AuraSpacing.xs))
    }
}

/**
 * A colour per agent, so the avatars are not seven identical circles.
 *
 * [AgentEntity.color] is seeded as the specialist's *index*, not an ARGB value
 * — reading it as a colour would paint every avatar near-black. Treating it as
 * what it is, an index into a palette, is why this takes an Int and wraps.
 */
private val AGENT_ACCENTS = listOf(
    Color(0xFF6EA8FE), // general
    Color(0xFF7BD88F), // coder
    Color(0xFFFFC663), // researcher
    Color(0xFFB58BFF), // writer
    Color(0xFFFF8FA3), // creative
    Color(0xFF5BD6C8), // executive
    Color(0xFFFF9F6E), // phone
)

private fun agentAccent(index: Int): Color =
    AGENT_ACCENTS[((index % AGENT_ACCENTS.size) + AGENT_ACCENTS.size) % AGENT_ACCENTS.size]

@Composable
private fun AgentRow(
    emoji: String?,
    name: String,
    description: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AuraSpacing.xxs)
            .clip(RoundedCornerShape(AuraSpacing.sm))
            .then(
                if (selected) {
                    Modifier
                        .background(accent.copy(alpha = 0.10f))
                        .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(AuraSpacing.sm))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(AuraSpacing.sm))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (emoji != null) {
                Text(text = emoji, fontSize = 20.sp)
            } else {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) accent else AuraThemeTokens.colors.textPrimary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "selected",
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
