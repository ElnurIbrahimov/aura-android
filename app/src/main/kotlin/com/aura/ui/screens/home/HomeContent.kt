package com.aura.ui.screens.home

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.defaultMinSize

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.AutoAwesome

import androidx.compose.material.icons.filled.Psychology

import androidx.compose.material.icons.filled.Search

import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.CalendarMonth

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Surface

import androidx.compose.material3.Text

import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.platform.testTag

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp

import com.aura.proactive.ProactiveEventBus

import com.aura.ui.components.AuraErrorState

import com.aura.ui.components.AuraInlineStatus

import com.aura.ui.components.AuraSkeleton

import com.aura.ui.components.InlineStatusTone

import com.aura.ui.components.ResponsiveContainer

import com.aura.ui.theme.AuraSpacing

import com.aura.ui.theme.AuraThemeTokens

import com.aura.ui.viewmodel.HomeLoadState

import com.aura.ui.viewmodel.HomeUiState

@Composable

fun HomeContent(

    state: HomeUiState,

    greeting: String,

    dateLabel: String,

    onAskAura: (String) -> Unit = {},

    onOpenSearch: () -> Unit = {},

    onRetry: () -> Unit = {},

    onDismissProactive: () -> Unit = {},

    onOpenChatWithBrief: (Long) -> Unit = {},

    onOpenMemory: () -> Unit = {},

    onOpenTasks: () -> Unit = {},

    onOpenCalendar: () -> Unit = {},
    /**
     * Dispatch for a proactive suggestion's action. Implemented in `NavGraph`,
     * which is where the nav controller lives; the card only knows what it is
     * proposing, not how to get there.
     */
    onProactiveAction: (com.aura.proactive.ProactiveAction) -> Unit = {},
    /** Records that a suggestion was tapped, and whether it led anywhere. */
    onProactiveTap: (Long, Boolean) -> Unit = { _, _ -> },

    onOpenReminders: () -> Unit = {},

    onOpenHands: () -> Unit = {},

    onOpenTools: () -> Unit = {},

    onOpenSkills: () -> Unit = {},

    onOpenCreative: () -> Unit = {},

    onOpenProactive: () -> Unit = {},

    onOpenAgentRuns: () -> Unit = {},

    onOpenProduction: () -> Unit = {},
    onOpenCapabilities: () -> Unit = {},
    onOpenEvolution: () -> Unit = {},
    onOpenCouncil: () -> Unit = {},

    modifier: Modifier = Modifier,

) {

    val colors = AuraThemeTokens.colors

    ResponsiveContainer(modifier = modifier.fillMaxSize()) {

        LazyColumn(

            modifier = Modifier.fillMaxSize(),

            contentPadding = PaddingValues(vertical = AuraSpacing.md),

            verticalArrangement = Arrangement.spacedBy(AuraSpacing.md),

        ) {

            item(key = "presence") {
                AgentPresence(
                    agentName = state.activeAgentName ?: state.activeAgentId,
                    memoryCallback = state.memoryCallback,
                    emotionSnapshot = state.emotionSnapshot,
                    affinityLevel = state.affinityLevel,
                    affinityProgress = state.affinityProgress,
                    onTap = { onAskAura("") },
                    modifier = Modifier.padding(top = AuraSpacing.lg, bottom = AuraSpacing.md),
                )
            }

            item(key = "search") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = colors.textSecondary,
                        )
                    }
                }
            }

            when (val loadState = state.loadState) {

                HomeLoadState.Loading -> item(key = "loading") {

                    Column(

                        modifier = Modifier.testTag("home-loading"),

                        verticalArrangement = Arrangement.spacedBy(AuraSpacing.md),

                    ) {

                        AuraSkeleton(height = 176.dp)

                        AuraSkeleton(height = 142.dp)

                        AuraSkeleton(height = 88.dp)

                    }

                }

                is HomeLoadState.Error -> {

                    if (!loadState.hasPartialContent) {

                        item(key = "full-error") {

                            AuraErrorState(

                                title = "Home unavailable",

                                message = loadState.message,

                                onRetry = onRetry,

                                retryLabel = "Retry",

                                modifier = Modifier.fillMaxWidth(),

                            )

                        }

                    } else {

                        item(key = "partial-error") {

                            Row(

                                modifier = Modifier.fillMaxWidth(),

                                verticalAlignment = Alignment.CenterVertically,

                                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),

                            ) {

                                AuraInlineStatus(

                                    text = loadState.message,

                                    tone = InlineStatusTone.Warning,

                                    modifier = Modifier.weight(1f),

                                )

                                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }

                            }

                        }

                        homeResolvedItems(

                            state = state,

                            isEmpty = false,

                            onAskAura = onAskAura,

                            onDismissProactive = onDismissProactive,

                            onOpenChatWithBrief = onOpenChatWithBrief,

                            onOpenMemory = onOpenMemory,

                            onOpenTasks = onOpenTasks,

                            onOpenCalendar = onOpenCalendar,

                            onOpenReminders = onOpenReminders,

                            onOpenHands = onOpenHands,

                            onOpenTools = onOpenTools,

                            onOpenSkills = onOpenSkills,

                            onOpenCreative = onOpenCreative,

                            onOpenProactive = onOpenProactive,

                            onOpenAgentRuns = onOpenAgentRuns,

                            onOpenProduction = onOpenProduction,

                            onOpenCapabilities = onOpenCapabilities,
                            onOpenEvolution = onOpenEvolution,
                            onOpenCouncil = onOpenCouncil,
                            onProactiveAction = onProactiveAction,
                            onProactiveTap = onProactiveTap,
                        )

                    }

                }

                HomeLoadState.Empty -> homeResolvedItems(

                    state = state,

                    isEmpty = true,

                    onAskAura = onAskAura,

                    onDismissProactive = onDismissProactive,

                    onOpenChatWithBrief = onOpenChatWithBrief,

                    onOpenMemory = onOpenMemory,

                    onOpenTasks = onOpenTasks,

                    onOpenCalendar = onOpenCalendar,

                    onOpenReminders = onOpenReminders,

                    onOpenHands = onOpenHands,

                    onOpenTools = onOpenTools,

                    onOpenSkills = onOpenSkills,

                    onOpenCreative = onOpenCreative,

                    onOpenProactive = onOpenProactive,

                            onOpenAgentRuns = onOpenAgentRuns,

                            onOpenProduction = onOpenProduction,

                onOpenCapabilities = onOpenCapabilities,
                onOpenEvolution = onOpenEvolution,
                onOpenCouncil = onOpenCouncil,
            onProactiveAction = onProactiveAction,
            onProactiveTap = onProactiveTap,

                )

                HomeLoadState.Content -> homeResolvedItems(

                    state = state,

                    isEmpty = false,

                    onAskAura = onAskAura,

                    onDismissProactive = onDismissProactive,

                    onOpenChatWithBrief = onOpenChatWithBrief,

                    onOpenMemory = onOpenMemory,

                    onOpenTasks = onOpenTasks,

                    onOpenCalendar = onOpenCalendar,

                    onOpenReminders = onOpenReminders,

                    onOpenHands = onOpenHands,

                    onOpenTools = onOpenTools,

                    onOpenSkills = onOpenSkills,

                    onOpenCreative = onOpenCreative,

                    onOpenProactive = onOpenProactive,

                            onOpenAgentRuns = onOpenAgentRuns,

                            onOpenProduction = onOpenProduction,

                onOpenCapabilities = onOpenCapabilities,
                onOpenEvolution = onOpenEvolution,
                onOpenCouncil = onOpenCouncil,
            onProactiveAction = onProactiveAction,
            onProactiveTap = onProactiveTap,

                )

            }

        }

    }

}

private fun androidx.compose.foundation.lazy.LazyListScope.homeResolvedItems(
    state: HomeUiState,
    isEmpty: Boolean,
    onAskAura: (String) -> Unit,
    onDismissProactive: () -> Unit,
    onOpenChatWithBrief: (Long) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenHands: () -> Unit = {},
    onOpenTools: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenCreative: () -> Unit = {},
    onOpenProactive: () -> Unit = {},
    onOpenAgentRuns: () -> Unit = {},
    onOpenProduction: () -> Unit = {},
    onOpenCapabilities: () -> Unit = {},
    onOpenEvolution: () -> Unit = {},
    onOpenCouncil: () -> Unit = {},
    onProactiveAction: (com.aura.proactive.ProactiveAction) -> Unit = {},
    onProactiveTap: (Long, Boolean) -> Unit = { _, _ -> },
) {
    val priority = selectHomePriority(state)

    item(key = "primary") {

        Column(
            modifier = Modifier.testTag(if (isEmpty) "home-empty" else "home-content"),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.lg),
        ) {

            HomeGreeting(
                hour = state.hour,
                userName = state.userName,
                subtitle = if (state.recentMemories.isNotEmpty()) {
                    "Pick up where you left off."
                } else {
                    "Start with what matters right now."
                },
            )

            HomePrimaryAction(
                onAskAura = onAskAura,
                label = if (state.recentMemories.isNotEmpty()) "Continue" else "Ask Aura",
            )

        }

    }

    item(key = "priority") {

        HomeBriefCard(

            priority = priority,

            onAction = {

                when (priority) {

                    is HomePriority.Proactive -> when (val event = priority.event) {

                        // Pass only the persisted event id — the chat
                        // ViewModel loads the brief body from Room.
                        is ProactiveEventBus.Event.MorningBriefReady -> onOpenChatWithBrief(event.id)

                        is ProactiveEventBus.Event.MorningBriefStructured ->

                            onOpenChatWithBrief(event.id)

                        is ProactiveEventBus.Event.CalendarEventSoon -> onOpenCalendar()

                        is ProactiveEventBus.Event.MemoryDecayWarning -> onOpenMemory()

                        // Was `else -> {}`: a card you could tap that did
                        // nothing, rendering an empty action label. The
                        // finding type is the only key a persisted row
                        // carries, so the action is derived from it.
                        is ProactiveEventBus.Event.DaemonInsight -> {
                            val action = com.aura.proactive.ProactiveFindingType.from(event.findingType)
                                ?.action ?: com.aura.proactive.ProactiveAction.None
                            onProactiveTap(event.id, action != com.aura.proactive.ProactiveAction.None)
                            onProactiveAction(action)
                        }

                        is ProactiveEventBus.Event.LivingWorldReport -> {
                            onProactiveTap(event.id, true)
                            onProactiveAction(com.aura.proactive.ProactiveAction.Navigate("creative"))
                        }
                    }

                    is HomePriority.Calendar -> onOpenCalendar()

                    is HomePriority.Task -> onOpenTasks()

                    is HomePriority.Reminder -> onOpenReminders()

                    is HomePriority.Memory -> onOpenMemory()

                    HomePriority.None -> onOpenTasks()

                }

            },

            onDismiss = onDismissProactive.takeIf { priority is HomePriority.Proactive },

        )

    }

    item(key = "secondary") {

        HomeSecondaryActions(

            memoryCount = state.recentMemories.size,

            tasksCount = state.pendingTasks.size,

            calendarCount = state.today.size,

            handsCount = state.handsCount,

            toolsCount = state.toolsCount,

            proactiveCount = maxOf(state.proactiveCount, state.proactiveUnreadCount),

            skillsCount = state.skillsCount,
            creativeCount = state.creativeProjectCount,
            activeCapabilities = state.activeCapabilities,

            onOpenMemory = onOpenMemory,

            onOpenTasks = onOpenTasks,

            onOpenCalendar = onOpenCalendar,

            onOpenHands = onOpenHands,

            onOpenTools = onOpenTools,

            onOpenSkills = onOpenSkills,

            onOpenCreative = onOpenCreative,

            onOpenProactive = onOpenProactive,

            onOpenAgentRuns = onOpenAgentRuns,

            onOpenProduction = onOpenProduction,
            onOpenCapabilities = onOpenCapabilities,
            onOpenEvolution = onOpenEvolution,
            onOpenCouncil = onOpenCouncil,
        )

    }

    if (isEmpty) {

        item(key = "fresh-start") {

            HomeFreshStart(

                onAskAura = onAskAura,

                onOpenMemory = onOpenMemory,

                onOpenTasks = onOpenTasks,

            )

        }

    }

    if (!isEmpty) {

        item(key = "at-a-glance") {

            HomeAtAGlance(state)

        }

    }

}

@Composable

private fun HomeFreshStart(

    onAskAura: (String) -> Unit,

    onOpenMemory: () -> Unit,

    onOpenTasks: () -> Unit,

) {

    val colors = AuraThemeTokens.colors

    Surface(

        modifier = Modifier.fillMaxWidth(),

        color = colors.surface0,

        shape = MaterialTheme.shapes.large,

        border = androidx.compose.foundation.BorderStroke(AuraSpacing.hairline, colors.borderSubtle),

    ) {

        Column(

            modifier = Modifier.padding(AuraSpacing.md),

            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),

        ) {

            Text(

                text = stringResource(R.string.build_useful_context),

                style = MaterialTheme.typography.titleSmall,

                fontWeight = FontWeight.SemiBold,

                color = colors.textPrimary,

            )

            FreshStartAction(

                icon = Icons.Filled.AutoAwesome,

                title = "Decide what matters today",

                message = "Turn an uncertain day into a concrete plan.",

                onClick = { onAskAura("Help me decide what matters most today") },

            )

            FreshStartAction(

                icon = Icons.Filled.TaskAlt,

                title = "Turn a goal into a task",

                message = "Give the day one concrete next step.",

                onClick = onOpenTasks,

            )

            FreshStartAction(

                icon = Icons.Filled.Psychology,

                title = "Capture something to remember",

                message = "Add context Aura can use in future conversations.",

                onClick = onOpenMemory,

            )

        }

    }

}

@Composable

private fun FreshStartAction(

    icon: ImageVector,

    title: String,

    message: String,

    onClick: () -> Unit,

) {

    val colors = AuraThemeTokens.colors

    Row(

        modifier = Modifier

            .fillMaxWidth()

            .defaultMinSize(minHeight = 64.dp)

            .clickable(onClick = onClick)

            .padding(vertical = AuraSpacing.xs),

        verticalAlignment = Alignment.CenterVertically,

        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),

    ) {

        Icon(

            imageVector = icon,

            contentDescription = null,

            tint = colors.actionPrimary,

            modifier = Modifier.size(22.dp),

        )

        Column(modifier = Modifier.weight(1f)) {

            Text(

                text = title,

                style = MaterialTheme.typography.bodyMedium,

                fontWeight = FontWeight.Medium,

                color = colors.textPrimary,

            )

            Text(

                text = message,

                style = MaterialTheme.typography.bodySmall,

                color = colors.textSecondary,

                maxLines = 2,

            )

        }

    }

}

@Composable

private fun HomeAtAGlance(state: HomeUiState) {

    val colors = AuraThemeTokens.colors

    data class GlanceItem(val icon: ImageVector, val title: String, val subtitle: String)

    val items = buildList {
        state.pendingTasks.drop(1).take(2).forEach {
            add(GlanceItem(Icons.Filled.TaskAlt, it, "Task"))
        }
        state.upcomingReminders.drop(if (state.pendingTasks.isEmpty()) 1 else 0).take(1).forEach {
            add(GlanceItem(Icons.Filled.NotificationsActive, it, "Reminder"))
        }
        state.today.drop(if (state.pendingTasks.isEmpty() && state.upcomingReminders.isEmpty()) 1 else 0).take(1).forEach {
            add(GlanceItem(Icons.Filled.CalendarMonth, it, "Calendar"))
        }
    }.take(3)

    if (items.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = AuraSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.at_a_glance),
            style = MaterialTheme.typography.labelLarge,
            color = colors.textSecondary,
        )
        items.forEach { item ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface1,
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = colors.actionPrimary,
                        modifier = Modifier.size(AuraSpacing.xl2),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                        )
                    }
                }
            }
        }
    }

}