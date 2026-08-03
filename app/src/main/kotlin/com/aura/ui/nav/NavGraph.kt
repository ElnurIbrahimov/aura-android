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
import com.aura.ui.evolution.BeliefsScreen
import com.aura.ui.evolution.EvolutionRollbackScreen
import com.aura.ui.screens.DiagnosticsScreen
import com.aura.ui.screens.CapabilitiesScreen
import com.aura.ui.screens.HandsScreen
import com.aura.ui.screens.HistoryScreen
import com.aura.ui.screens.creative.CreativeProjectScreen
import com.aura.ui.screens.creative.CreativeStudioScreen
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
import com.aura.ui.screens.schedule.ScheduleScreen
import com.aura.ui.viewmodel.ScheduleViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.ui.screens.TasteProfileScreen
import com.aura.ui.screens.WorldModelScreen
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
        val route = when {
            !launchRequest.morningBriefSummary.isNullOrBlank() -> {
                "chat?brief=${android.net.Uri.encode(launchRequest.morningBriefSummary)}"
            }
            !launchRequest.chatPrefillDraft.isNullOrBlank() -> {
                "chat?draft=${android.net.Uri.encode(launchRequest.chatPrefillDraft)}"
            }
            launchRequest.openChat -> TopLevelRoute.Chat.route
            launchRequest.openMemory -> TopLevelRoute.Memory.route
            else -> return@LaunchedEffect
        }
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
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
                        navController.navigate(route.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
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
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenChatWithBrief = { brief ->
                        // Morning-brief proactive card. Pass the brief text
                        // as `brief` query param so ChatScreen auto-sends
                        // it as a user message.
                        navController.navigate(
                            "chat?brief=${android.net.Uri.encode(brief)}"
                        ) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenMemory = {
                        navController.navigate(TopLevelRoute.Memory.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenTasks = { navController.navigate("tasks") },
                    onOpenReminders = { navController.navigate("reminders") },
                    onOpenHands = { navController.navigate("hands") },
                    onOpenTools = { navController.navigate("tools") },
                    onOpenSkills = { navController.navigate("skills") },
                    onOpenCreative = { navController.navigate("creative") },
                    onOpenProactive = { navController.navigate("proactive") },
                    onOpenAgentRuns = { navController.navigate("agent_runs") },
                    onOpenProduction = { navController.navigate("production") },
                    onOpenCapabilities = { navController.navigate("capabilities") },
                    onOpenEvolution = { navController.navigate("evolution/inbox") },
                    onOpenCalendar = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        // Guard: devices without a calendar app throw
                        // ActivityNotFoundException. Fail soft rather than crash.
                        try {
                            navController.context.startActivity(intent)
                        } catch (e: android.content.ActivityNotFoundException) {
                            android.widget.Toast.makeText(
                                navController.context,
                                "No calendar app found",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
            composable(
                route = "chat?convId={convId}&draft={draft}&brief={brief}&focusTurn={focusTurn}",
                arguments = listOf(
                    navArgument("convId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("draft") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("brief") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("focusTurn") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                val convId = it.arguments?.getString("convId")
                val summary = it.arguments?.getString("brief")
                val draft = it.arguments?.getString("draft")
                val focusTurn = it.arguments?.getString("focusTurn")?.toLongOrNull()
                ChatRoute(
                    navController = navController,
                    resumeConversationId = convId,
                    morningBriefSummary = summary,
                    initialDraft = draft,
                    focusTurnTimestamp = focusTurn,
                    onNavigateHistory = { navController.navigate("history") },
                )
            }
            composable(TopLevelRoute.Memory.route) {
                MemoryScreen(
                    onOpenKnowledgeGraph = { navController.navigate("knowledge_graph") },
                    onOpenDreams = { navController.navigate("dreams") },
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
                    onNavigateProfile = { navController.navigate("profile") },
                    onNavigateIdentity = { navController.navigate("identity_editor") },
                    onNavigateDiagnostics = { navController.navigate("diagnostics") },
                    onNavigateEvolutionInbox = { navController.navigate("evolution/inbox") },
                    onNavigateBeliefs = { navController.navigate("evolution/beliefs") },
                    onNavigateAgentEditor = { navController.navigate("agent_editor") },
                    onNavigateWorldModel = { navController.navigate("world_model") },
                    onNavigateTasteProfile = { navController.navigate("taste_profile") },
                )
            }
            composable("diagnostics") {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
            composable("identity_editor") {
                com.aura.ui.screens.IdentityEditorScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable("knowledge_graph") {
                KnowledgeGraphScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSourceConversation = { convId, turnTimestamp ->
                        navController.navigate(
                            "chat?convId=${android.net.Uri.encode(convId)}&focusTurn=$turnTimestamp"
                        )
                    },
                )
            }
            composable("history") {
                HistoryScreen(onSelect = { convId ->
                    navController.navigate("chat?convId=$convId") {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable("hands") { HandsScreen() }
            composable("tasks") { TasksScreen(onOpenSchedule = { navController.navigate("schedule") }) }
            composable("tools") { ToolsScreen() }
            composable("proactive") { ProactiveHistoryScreen() }
            composable("dreams") {
                DreamsScreen(onBack = { navController.popBackStack() })
            }
            composable("world_model") {
                WorldModelScreen(onBack = { navController.popBackStack() })
            }
            composable("taste_profile") {
                TasteProfileScreen(onBack = { navController.popBackStack() })
            }
            composable("reminders") {
                RemindersScreen(onBack = { navController.popBackStack() })
            }
            composable("profile") {
                ProfileScreen(onBack = { navController.popBackStack() })
            }
            composable("creative") {
                CreativeStudioScreen(
                    onOpenProject = { id -> navController.navigate("creative/$id") },
                )
            }
            composable(
                route = "creative/{projectId}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
                CreativeProjectScreen(
                    projectId = projectId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("agent_runs") {
                AgentRunsScreen(
                    runId = null,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "agent_runs/{runId}",
                arguments = listOf(navArgument("runId") { type = NavType.StringType }),
            ) { backStackEntry ->
                AgentRunsScreen(
                    runId = backStackEntry.arguments?.getString("runId"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("skills") {
                SkillsScreen(onBack = { navController.popBackStack() })
            }
            composable("capabilities") {
                CapabilitiesScreen(onBack = { navController.popBackStack() })
            }
            composable("production") {
                ProductionPipelineScreen(
                    onOpenAgentRuns = { navController.navigate("agent_runs") },
                    onOpenCreative = { navController.navigate("creative") },
                )
            }
            composable(
                route = "agent_editor?agentId={agentId}",
                arguments = listOf(navArgument("agentId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId")
                AgentEditorScreen(
                    agentId = agentId,
                    onDone = { navController.popBackStack() },
                )
            }
            composable(
                route = "council/{convId}",
                arguments = listOf(navArgument("convId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { backStackEntry ->
                val convId = backStackEntry.arguments?.getString("convId")
                CouncilScreen(
                    onBack = { navController.popBackStack() },
                    onSendToChat = { text ->
                        if (!convId.isNullOrBlank()) {
                            navController.previousBackStackEntry?.savedStateHandle?.set("council_result", text)
                            navController.popBackStack()
                        }
                    },
                )
            }
            composable("evolution/inbox") {
                EvolutionInboxScreen(
                    onBack = { navController.popBackStack() },
                    onRollback = { proposalId -> navController.navigate("evolution/rollback/$proposalId") },
                )
            }
            composable("schedule") {
                val viewModel: ScheduleViewModel = hiltViewModel()
                ScheduleScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }

            composable("evolution/beliefs") {
                BeliefsScreen()
            }
            composable(
                route = "evolution/rollback/{proposalId}",
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
