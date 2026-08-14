package com.aura.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.ui.theme.AuraDimensions
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

sealed class TopLevelRoute(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    data object Home : TopLevelRoute("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    data object Chat : TopLevelRoute(
        "chat",
        "Chat",
        Icons.AutoMirrored.Filled.Chat,
        Icons.AutoMirrored.Outlined.Chat,
    )
    data object Memory : TopLevelRoute("memory", "Memory", Icons.Filled.Memory, Icons.Outlined.Memory)
    data object Tasks : TopLevelRoute("tasks", "Tasks", Icons.Filled.TaskAlt, Icons.Outlined.TaskAlt)
    data object Settings : TopLevelRoute("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
    data object Evolution : TopLevelRoute("evolution/inbox", "Evolve", Icons.Filled.AutoFixHigh, Icons.Outlined.AutoFixHigh)
}

/**
 * Secondary route constants. Every route string used in [NavGraph] should
 * reference these constants so a typo is a compile error, not a silent
 * navigation no-op.
 */
sealed class Route(val path: String) {
    data object History : Route("history")
    data object Hands : Route("hands")
    data object Tasks : Route("tasks")
    data object Reminders : Route("reminders")
    data object Tools : Route("tools")
    data object Skills : Route("skills")
    data object Creative : Route("creative")
    data object CreativeProject : Route("creative/{projectId}")
    data object Production : Route("production")
    data object Proactive : Route("proactive")
    data object AgentRuns : Route("agent_runs")
    data object AgentRunDetail : Route("agent_runs/{runId}")
    data object Capabilities : Route("capabilities")
    data object Council : Route("council?convId={convId}")
    data object Dreams : Route("dreams")
    data object DreamLog : Route("dream_log")
    data object AgentProfiles : Route("agent_profiles")
    /**
     * One place for what Aura currently believes about the user.
     *
     * Replaced `world_model`, `taste_profile` and `evolution/beliefs` — the
     * last two of which both listed beliefs from the same DAO with the same
     * actions.
     */
    data object Mind : Route("mind")
    data object KnowledgeGraph : Route("knowledge_graph")
    data object Profile : Route("profile")
    data object IdentityEditor : Route("identity_editor")
    data object Diagnostics : Route("diagnostics")
    data object CrashLogs : Route("crash_logs")
    data object Schedule : Route("schedule")
    data object EvolutionInbox : Route("evolution/inbox")
    data object EvolutionRollback : Route("evolution/rollback/{proposalId}")
    data object AgentEditor : Route("agent_editor?agentId={agentId}")
}

internal val topLevelRoutes = listOf(
    TopLevelRoute.Home,
    TopLevelRoute.Chat,
    TopLevelRoute.Memory,
    TopLevelRoute.Tasks,
    TopLevelRoute.Settings,
)

internal fun normalizedBaseRoute(route: String?): String? = route?.substringBefore('?')

@Composable
fun AuraBottomNavigation(
    currentRoute: String?,
    onRouteSelected: (TopLevelRoute) -> Unit,
    modifier: Modifier = Modifier,
    navigationBarInsets: WindowInsets = WindowInsets.navigationBars,
    badgeCounts: Map<String, Int> = emptyMap(),
) {
    val colors = AuraThemeTokens.colors
    val baseRoute = normalizedBaseRoute(currentRoute)
    Surface(
        color = colors.surface0,
        shape = RoundedCornerShape(AuraSpacing.xxl2),
        shadowElevation = AuraSpacing.xs,
        border = BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
        modifier = modifier
            .fillMaxWidth()
            // Tagged before the padding modifiers, not after. Modifiers wrap
            // inward, so a testTag at the end of the chain attaches to the
            // *padded content* — the tagged node measured 56dp, the row's
            // height, rather than the bar's. The name says "bottom-navigation",
            // so it should identify the bar including its inset and padding,
            // which is also what BottomNavigationTest was written to assert:
            // the design height on the bar, and that the navigation inset lives
            // below the interactive row. Both failed against a layout that was
            // correct.
            .testTag("bottom-navigation")
            .windowInsetsPadding(navigationBarInsets)
            .padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xxs),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AuraDimensions.bottomNavigationHeight - AuraSpacing.xs)
                    .testTag("bottom-navigation-row")
                    .padding(horizontal = AuraSpacing.xs, vertical = AuraSpacing.tiny),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                topLevelRoutes.forEach { route ->
                    val selected = baseRoute == route.route
                    val containerColor by animateColorAsState(
                        targetValue = if (selected) colors.surface1 else Color.Transparent,
                        animationSpec = tween(durationMillis = AuraDimensions.motionStandardMs),
                        label = "bar-item-bg",
                    )
                    val contentColor = if (selected) colors.textPrimary else colors.textTertiary
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = AuraDimensions.minimumTouchTarget)
                            .clip(RoundedCornerShape(AuraDimensions.controlRadius))
                            .background(containerColor)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { if (!selected) onRouteSelected(route) },
                            )
                            .semantics { contentDescription = route.label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(AuraSpacing.tiny),
                        ) {
                            val badgeCount = badgeCounts[route.route] ?: 0
                            val iconWithBadge: @Composable () -> Unit = {
                                Icon(
                                    imageVector = if (selected) route.selectedIcon else route.unselectedIcon,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            if (badgeCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = colors.actionPrimary,
                                            contentColor = colors.onActionPrimary,
                                        ) {
                                            Text(
                                                text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                                fontSize = 10.sp,
                                            )
                                        }
                                    },
                                ) {
                                    iconWithBadge()
                                }
                            } else {
                                iconWithBadge()
                            }
                            Text(
                                text = route.label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = contentColor,
                            )
                        }
                    }
                }
            }
            // Inset spacer no longer needed — the Surface itself now
            // applies windowInsetsPadding(navigationBarInsets) which
            // pushes the entire bar above the system navigation bar.
        }
    }
}
