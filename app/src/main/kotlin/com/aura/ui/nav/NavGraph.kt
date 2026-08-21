package com.aura.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aura.AuraLaunchRequest
import com.aura.ui.screens.agentrun.AgentRunsScreen
import com.aura.ui.screens.chat.ChatRoute
import com.aura.ui.screens.production.ProductionPipelineScreen
import com.aura.ui.evolution.EvolutionInboxScreen
import com.aura.ui.evolution.EvolutionRollbackScreen
import com.aura.ui.screens.DiagnosticsScreen
import com.aura.ui.screens.CapabilitiesScreen
import com.aura.ui.screens.HandsScreen
import com.aura.ui.screens.hands.RecordedHandReviewScreen
import com.aura.ui.screens.HistoryScreen
import com.aura.ui.screens.creative.CreativeProjectScreen
import com.aura.ui.screens.creative.CreativeStudioScreen
import com.aura.ui.screens.game.PlayScreen
import com.aura.ui.screens.home.HomeRoute
import com.aura.ui.screens.skills.SkillsScreen
import com.aura.ui.screens.KnowledgeGraphScreen
import com.aura.ui.screens.ToolsScreen
import com.aura.ui.screens.MemoryScreen
import com.aura.ui.screens.ProactiveHistoryScreen
import com.aura.ui.screens.RemindersScreen
import com.aura.ui.screens.DreamsScreen
import com.aura.ui.screens.ProfileScreen
import com.aura.ui.screens.SettingsScreen
import com.aura.ui.screens.AgentEditorScreen
import com.aura.ui.screens.council.CouncilScreen
import com.aura.ui.screens.council.DreamLogScreen
import com.aura.ui.screens.council.AgentProfileScreen
import com.aura.ui.screens.schedule.ScheduleScreen
import com.aura.ui.viewmodel.ScheduleViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.ui.screens.MindScreen
import com.aura.ui.screens.TasksScreen

@Composable
fun NavGraph(
    launchRequest: AuraLaunchRequest = AuraLaunchRequest(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    // Show the bottom bar on all screens — not just top-level routes.
    // The previous check (`topLevelRoutes.any { it.route == baseRoute }`)
    // hid the bar on every secondary screen (history, hands, tasks, tools,
    // proactive, reminders, profile, identity_editor), forcing the user
    // to press the system back button to return to a tab. Keeping the bar
    // always visible matches the Aura Web pattern where the left sidebar
    // persists across all views.
    val showBottomBar = true
    var showSearch by remember { mutableStateOf(false) }

    LaunchedEffect(launchRequest.sequence) {
        if (launchRequest.sequence == 0) return@LaunchedEffect
        val briefEventId = launchRequest.morningBriefEventId
        val route = when {
            briefEventId != null && briefEventId > 0L -> {
                // Only the event id travels through the nav route — the
                // chat ViewModel loads the brief body from Room. Full
                // text in a route argument risked
                // TransactionTooLargeException on long briefs.
                "chat?briefId=$briefEventId"
            }
            !launchRequest.chatPrefillDraft.isNullOrBlank() -> {
                "chat?draft=${android.net.Uri.encode(launchRequest.chatPrefillDraft)}"
            }
            launchRequest.openChat -> TopLevelRoute.Chat.route
            launchRequest.openMemory -> TopLevelRoute.Memory.route
            else -> return@LaunchedEffect
        }
        navController.navigateTopLevel(route)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                val evolutionBadgeVm: com.aura.ui.evolution.EvolutionBadgeViewModel = hiltViewModel()
                val pendingProposals by evolutionBadgeVm.pendingCount.collectAsStateWithLifecycle()
                AuraBottomNavigation(
                    currentRoute = backStackEntry?.destination?.route,
                    onRouteSelected = { route ->
                        navController.navigateTopLevel(route.route)
                    },
                    badgeCounts = if (pendingProposals > 0) {
                        mapOf(TopLevelRoute.Evolution.route to pendingProposals)
                    } else {
                        emptyMap()
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelRoute.Home.route,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable(TopLevelRoute.Home.route) {
                HomeRoute(
                    onOpenSearch = { showSearch = true },
                    onOpenChat = { prefill ->
                        // Pre-fill the chat draft via a query param.
                        // Empty string means "just open chat" — no prefill.
                        val route = if (prefill.isNotBlank()) {
                            "chat?draft=${android.net.Uri.encode(prefill)}"
                        } else {
                            TopLevelRoute.Chat.route
                        }
                        navController.navigateTopLevel(route)
                    },
                    onOpenChatWithBrief = { briefEventId ->
                        // Morning-brief proactive card. Pass only the
                        // persisted event id — ChatViewModel loads the
                        // body from Room and auto-sends it once.
                        val route = if (briefEventId > 0L) {
                            "chat?briefId=$briefEventId"
                        } else {
                            TopLevelRoute.Chat.route
                        }
                        navController.navigateTopLevel(route)
                    },
                    onOpenMemory = {
                        navController.navigateTopLevel(TopLevelRoute.Memory.route)
                    },
                    onOpenTasks = { navController.navigate(Route.Tasks.path) },
                    onOpenReminders = { navController.navigate(Route.Reminders.path) },
                    onOpenHands = { navController.navigate(Route.Hands.path) },
                    onOpenTools = { navController.navigate(Route.Tools.path) },
                    onOpenSkills = { navController.navigate(Route.Skills.path) },
                    onOpenLibrary = { navController.navigate(Route.Library.path) },
                    onOpenCreative = { navController.navigate(Route.Creative.path) },
                    onOpenProactive = { navController.navigate(Route.Proactive.path) },
                    onOpenAgentRuns = { navController.navigate(Route.AgentRuns.path) },
                    onOpenProduction = { navController.navigate(Route.Production.path) },
                    onOpenCapabilities = { navController.navigate(Route.Capabilities.path) },
                    onOpenEvolution = { navController.navigate(Route.EvolutionInbox.path) },
            onOpenCouncil = { navController.navigate(Route.Council.path) },
                    onProactiveAction = { action -> dispatchProactiveAction(action, navController) },
                    onOpenCalendar = { openCalendarApp(navController) },
                )
            }
            composable(
                route = "chat?convId={convId}&draft={draft}&briefId={briefId}&focusTurn={focusTurn}",
                arguments = listOf(
                    navArgument("convId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("draft") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("briefId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("focusTurn") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                val convId = it.arguments?.getString("convId")
                val briefId = it.arguments?.getString("briefId")?.toLongOrNull()
                val draft = it.arguments?.getString("draft")
                val focusTurn = it.arguments?.getString("focusTurn")?.toLongOrNull()
                ChatRoute(
                    navController = navController,
                    resumeConversationId = convId,
                    morningBriefEventId = briefId,
                    initialDraft = draft,
                    focusTurnTimestamp = focusTurn,
                    onNavigateHistory = { navController.navigate(Route.History.path) },
                )
            }
            composable(TopLevelRoute.Memory.route) {
                MemoryScreen(
                    onOpenKnowledgeGraph = { navController.navigate(Route.KnowledgeGraph.path) },
                    onOpenDreams = { navController.navigate(Route.Dreams.path) },
                    onOpenSourceConversation = { convId, turnTimestamp ->
                        navController.navigate(
                            "chat?convId=${android.net.Uri.encode(convId)}&focusTurn=$turnTimestamp"
                        ) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(TopLevelRoute.Settings.route) {
                SettingsScreen(
                    onNavigateProfile = { navController.navigate(Route.Profile.path) },
                    onNavigateIdentity = { navController.navigate(Route.IdentityEditor.path) },
                    onNavigateDiagnostics = { navController.navigate(Route.Diagnostics.path) },
                    onNavigateCrashLogs = { navController.navigate(Route.CrashLogs.path) },
                    onNavigateEvolutionInbox = { navController.navigate(Route.EvolutionInbox.path) },
                    onNavigateAgentEditor = { navController.navigate(Route.AgentEditor.path) },
                    onNavigateMind = { navController.navigate(Route.Mind.path) },
                )
            }
            composable(Route.Diagnostics.path) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
            composable(Route.CrashLogs.path) {
                com.aura.ui.screens.CrashLogScreen(onBack = { navController.popBackStack() })
            }
            composable(Route.IdentityEditor.path) {
                com.aura.ui.screens.IdentityEditorScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Route.KnowledgeGraph.path) {
                KnowledgeGraphScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSourceConversation = { convId, turnTimestamp ->
                        navController.navigate(
                            "chat?convId=${android.net.Uri.encode(convId)}&focusTurn=$turnTimestamp"
                        )
                    },
                )
            }
            composable(Route.History.path) {
                HistoryScreen(onSelect = { convId ->
                    // No restoreState/saveState here. Those belong to
                    // bottom-tab switching, where returning to a tab should
                    // put back exactly what you left. On an
                    // argument-carrying route they restore the *previous*
                    // chat entry along with its original arguments, so the
                    // convId just chosen was discarded and picking a
                    // conversation from history reopened the current one.
                    // Matches how onOpenSourceConversation already opens a
                    // specific chat.
                    navController.navigate(
                        "chat?convId=${android.net.Uri.encode(convId)}"
                    ) {
                        launchSingleTop = true
                    }
                })
            }
            composable(Route.Hands.path) {
                HandsScreen(
                    onBack = { navController.popBackStack() },
                    onRecordHand = { navController.navigate(Route.RecordHand.path) },
                )
            }
            composable(Route.RecordHand.path) {
                RecordedHandReviewScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(Route.Tasks.path) { TasksScreen(onOpenSchedule = { navController.navigate(Route.Schedule.path) }) }
            composable(Route.Tools.path) { ToolsScreen(onBack = { navController.popBackStack() }) }
            composable(Route.Proactive.path) {
                ProactiveHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onProactiveAction = { action -> dispatchProactiveAction(action, navController) },
                )
            }
            composable(Route.Dreams.path) {
                DreamsScreen(onBack = { navController.popBackStack() })
            }
            composable(Route.Mind.path) {
                MindScreen(
                    onBack = { navController.popBackStack() },
                    onOpenKnowledgeGraph = { navController.navigate(Route.KnowledgeGraph.path) },
                    onOpenConsolidation = { navController.navigate(Route.Dreams.path) },
                    onProactiveAction = { action -> dispatchProactiveAction(action, navController) },
                )
            }
            composable(Route.Reminders.path) {
                RemindersScreen(onBack = { navController.popBackStack() })
            }
            composable(Route.Profile.path) {
                ProfileScreen(onBack = { navController.popBackStack() })
            }
            composable(Route.Creative.path) {
                CreativeStudioScreen(
                    onOpenProject = { id -> navController.navigate("creative/" + id) },
                )
            }
            composable(
                route = Route.CreativeProject.path,
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
                CreativeProjectScreen(
                    projectId = projectId,
                    onBack = { navController.popBackStack() },
                    onPlay = { worldId -> navController.navigate("play/" + worldId) },
                )
            }
            composable(
                route = Route.Play.path,
                arguments = listOf(
                    navArgument("worldId") { type = NavType.StringType },
                ),
            ) {
                // worldId is read by PlayViewModel out of SavedStateHandle,
                // so it is not threaded through the composable.
                PlayScreen(onBack = { navController.popBackStack() })
            }
            composable(Route.AgentRuns.path) {
                AgentRunsScreen(
                    runId = null,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Route.AgentRunDetail.path,
                arguments = listOf(navArgument("runId") { type = NavType.StringType }),
            ) { backStackEntry ->
                AgentRunsScreen(
                    runId = backStackEntry.arguments?.getString("runId"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Route.Library.path) {
                com.aura.ui.screens.LibraryScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { item ->
                        // Routed to whichever feature owns the item, because that feature is
                        // where it can actually be worked on. The Library lists; it does not
                        // reimplement four viewers.
                        when (item.source) {
                            com.aura.library.LibrarySource.CREATIVE_ARTIFACT ->
                                navController.navigate(Route.Creative.path)
                            com.aura.library.LibrarySource.DOCUMENT,
                            com.aura.library.LibrarySource.GENERATED_MEDIA -> Unit
                        }
                    },
                )
            }
            composable(Route.Skills.path) {
                SkillsScreen(onBack = { navController.popBackStack() })
            }
            composable(Route.Capabilities.path) {
                CapabilitiesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(TopLevelRoute.Settings.route) },
                )
            }
            composable(Route.Production.path) {
                ProductionPipelineScreen(
                    onOpenAgentRuns = { navController.navigate(Route.AgentRuns.path) },
                    onOpenCreative = { navController.navigate(Route.Creative.path) },
                )
            }
            composable(
                route = Route.AgentEditor.path,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId")
                AgentEditorScreen(
                    agentId = agentId,
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Route.EvolutionInbox.path) {
                EvolutionInboxScreen(
                    onBack = { navController.popBackStack() },
                    onRollback = { proposalId -> navController.navigate("evolution/rollback/" + proposalId) },
                )
            }
            composable(Route.Schedule.path) {
                val viewModel: ScheduleViewModel = hiltViewModel()
                ScheduleScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }

            composable(
                route = Route.Council.path,
                arguments = listOf(navArgument("convId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { backStackEntry ->
                CouncilScreen(
                    convId = backStackEntry.arguments?.getString("convId"),
                    onBack = { navController.popBackStack() },
                    onOpenDreamLog = { navController.navigate(Route.DreamLog.path) },
                    onOpenAgentProfiles = { navController.navigate(Route.AgentProfiles.path) },
                )
            }
            composable(Route.DreamLog.path) {
                DreamLogScreen(onBack = { navController.popBackStack() })
            }
            composable(Route.AgentProfiles.path) {
                AgentProfileScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Route.EvolutionRollback.path,
                arguments = listOf(navArgument("proposalId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val proposalId = backStackEntry.arguments?.getString("proposalId").orEmpty()
                EvolutionRollbackScreen(
                    proposalId = proposalId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    if (showSearch) {
        com.aura.ui.screens.search.GlobalSearchSheet(
            onNavigate = { route -> navController.navigate(route) },
            onDismiss = { showSearch = false },
        )
    }
}

/**
 * Hand off to the system calendar app.
 *
 * Extracted so the proactive dispatcher and the Home callback share one
 * implementation — including the ActivityNotFoundException guard, which devices
 * without a calendar app need and which is easy to forget on a second copy.
 */
private fun openCalendarApp(navController: androidx.navigation.NavHostController) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        data = android.net.Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")
        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        navController.context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(
            navController.context,
            "No calendar app found",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * The one place a proactive suggestion turns into something happening.
 *
 * Shared by the Home card and the proactive history, and reached by both
 * findings and world-model opportunities, so a route can only be wrong in one
 * place. `ProactiveActionRouteTest` checks every route this can be handed
 * actually exists — two of them ("graph", "calendar") did not, and stayed
 * harmless only because nothing had ever navigated.
 */
private fun dispatchProactiveAction(
    action: com.aura.proactive.ProactiveAction,
    navController: androidx.navigation.NavHostController,
) {
    when (action) {
        is com.aura.proactive.ProactiveAction.Navigate ->
            navController.navigate(action.route)
        is com.aura.proactive.ProactiveAction.OpenChat ->
            navController.navigate(
                if (action.draft.isBlank()) TopLevelRoute.Chat.route
                else "chat?draft=" + android.net.Uri.encode(action.draft),
            )
        com.aura.proactive.ProactiveAction.OpenCalendarApp -> openCalendarApp(navController)
        com.aura.proactive.ProactiveAction.None -> Unit
    }
}

/**
 * Whether navigating to [route] may restore that destination's saved back stack.
 *
 * `restoreState` restores the entry saved for the destination, and a restored entry keeps
 * the arguments it was created with — the ones passed now are dropped. The destination is
 * identified by its route *pattern*, so `chat?draft=hello` and `chat?briefId=7` both
 * resolve to the same saved `chat` entry.
 *
 * That is exactly what the bottom bar wants and exactly what everything else does not.
 * All five top-level navigates here used the same recipe, so once Chat had been opened and
 * a tab switched even once, Home's ask field silently dropped what was typed into it and
 * the morning-brief notification opened an empty chat.
 *
 * A route with arguments is the caller saying "this one, with these" — so it starts fresh.
 */
internal fun shouldRestoreState(route: String): Boolean = "?" !in route

/**
 * Navigate to a top-level destination with the bottom-bar back-stack behaviour, restoring
 * saved state only when [shouldRestoreState] allows it.
 */
private fun NavController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = shouldRestoreState(route)
    }
}
