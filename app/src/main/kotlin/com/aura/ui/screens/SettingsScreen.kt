package com.aura.ui.screens

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.BuildConfig
import com.aura.ui.settings.BackupViewModel
import com.aura.ui.settings.ChatGptAccountCard
import com.aura.ui.settings.SettingsClickableRow
import com.aura.ui.settings.SettingsViewModel
import com.aura.ui.settings.UsageViewModel
import com.aura.ui.settings.sections.AiAndModelsSection
import com.aura.ui.settings.sections.AppearanceSection
import com.aura.ui.settings.sections.DataAndBackupSection
import com.aura.ui.settings.sections.DreamConsolidationSection
import com.aura.ui.settings.sections.EmotionDaemonSection
import com.aura.ui.settings.sections.IntegrationsSection
import com.aura.ui.settings.sections.ReasoningSection
import com.aura.ui.settings.sections.EvolutionSettingsSection
import com.aura.ui.settings.sections.McpServersSection
import com.aura.ui.settings.sections.ModelRolesSection
import com.aura.ui.settings.sections.PersonaSection
import com.aura.ui.settings.sections.PrivacySection
import com.aura.ui.settings.sections.ToolPermissionsSection
import com.aura.ui.settings.sections.TriggersSection
import com.aura.ui.settings.sections.UsageSection
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.Icons

@Composable
fun SettingsScreen(
    onNavigateProfile: () -> Unit,
    onNavigateIdentity: () -> Unit = {},
    onNavigateDiagnostics: () -> Unit = {},
    onNavigateCrashLogs: () -> Unit = {},
    onNavigateEvolutionInbox: () -> Unit = {},
    onNavigateBeliefs: () -> Unit = {},
    onNavigateAgentEditor: () -> Unit = {},
    onNavigateWorldModel: () -> Unit = {},
    onNavigateTasteProfile: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    backupViewModel: BackupViewModel = hiltViewModel(),
    usageViewModel: UsageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val usage by usageViewModel.usage.collectAsStateWithLifecycle()
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val emotionSnapshot by viewModel.emotionSnapshot.collectAsStateWithLifecycle()
    val daemonThoughtsCount by viewModel.daemonThoughtsCount.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AuraSpacing.xxl2),
    ) {
        // Header
        Column(modifier = Modifier.padding(top = AuraSpacing.md, bottom = AuraSpacing.small)) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = AuraThemeTokens.colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            Text(
                text = stringResource(R.string.connect_providers_manage_memory_customize_aura),
                style = MaterialTheme.typography.bodyLarge,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
            )
        }

        Spacer(modifier = Modifier.height(AuraSpacing.xxs))

        // ── AI & MODELS ──────────────────────────────────────────
        SettingsGroupHeader("AI & Models")

        AiAndModelsSection(
            state = state,
            chatGptCard = {
                val chatgptConnected by viewModel.chatgptConnected.collectAsStateWithLifecycle()
                val chatgptAccount by viewModel.chatgptAccount.collectAsStateWithLifecycle()
                val chatgptExpired by viewModel.chatgptSessionExpired.collectAsStateWithLifecycle()
                val chatgptPaste by viewModel.chatgptPaste.collectAsStateWithLifecycle()
                val chatgptError by viewModel.chatgptSignInError.collectAsStateWithLifecycle()
                ChatGptAccountCard(
                    connected = chatgptConnected,
                    account = chatgptAccount,
                    sessionExpired = chatgptExpired,
                    paste = chatgptPaste,
                    error = chatgptError,
                    onPasteChange = viewModel::updateChatGptPaste,
                    onSignIn = viewModel::signInChatGpt,
                    onDisconnect = viewModel::disconnectChatGpt,
                )
            },
            onCustomBaseUrlChange = viewModel::updateCustomBaseUrl,
            onCustomApiKeyChange = viewModel::updateCustomApiKey,
            onCustomTest = viewModel::saveAndTestCustomEndpoint,
            onCustomClear = viewModel::clearCustomEndpoint,
            onSmtpHostChange = viewModel::updateSmtpHost,
            onSmtpPortChange = viewModel::updateSmtpPort,
            onSmtpUsernameChange = viewModel::updateSmtpUsername,
            onSmtpPasswordChange = viewModel::updateSmtpPassword,
            onSmtpFromChange = viewModel::updateSmtpFrom,
            onSmtpSave = viewModel::saveSmtpConfig,
            onUpdateCredential = viewModel::updateCredentialDraft,
            onVerifyKey = viewModel::verifyKey,
            onSetDefaultModel = viewModel::setDefaultModel,
            onSetEmbeddingModel = viewModel::setEmbeddingModel,
            onSetImageModel = viewModel::setImageModel,
            onSetVideoModel = viewModel::setVideoModel,
            onSetVoiceModel = viewModel::setVoiceModel,
            onSetVisionModel = viewModel::setVisionModel,
            onSetBackgroundModel = viewModel::setBackgroundModel,
            onSetDeepModeModel = viewModel::setDeepModeModel,
            onSetMoaReferenceModels = viewModel::setMoaReferenceModels,
            onSetMoaAggregatorModel = viewModel::setMoaAggregatorModel,
            onSetPlanningEnabled = viewModel::setPlanningEnabled,
            onSetPromptCachingEnabled = viewModel::setPromptCachingEnabled,
            onRefreshModels = viewModel::refreshModels,
        )

        ModelRolesSection(
            roleModels = state.roleModels,
            roleFallbacks = state.roleFallbacks,
            availableModels = state.availableModels,
            onSetRoleModel = viewModel::setRoleModel,
        )

        ReasoningSection(viewModel = viewModel)

        IntegrationsSection(viewModel = viewModel)

        McpServersSection(
            mcpServers = state.mcpServers,
            mcpDiscoveredTools = state.mcpDiscoveredTools,
            onTestConnection = viewModel::testMcpConnection,
            onDisconnect = viewModel::disconnectMcpServer,
        )

        // ── MEMORY & KNOWLEDGE ───────────────────────────────────
        SettingsGroupHeader("Memory & Knowledge")

        PersonaSection(
            identityCustomized = state.identityCustomized,
            specialistOverrides = state.specialistOverrides,
            onNavigateIdentity = onNavigateIdentity,
            onSetSpecialistOverrides = viewModel::setSpecialistOverrides,
        )

        DreamConsolidationSection(
            enabled = state.dreamEnabled,
            lastRunAt = state.dreamLastRunAt,
            lastRunStats = state.dreamLastRunStats,
            totalSummaries = state.dreamTotalSummaries,
            isRunning = state.dreamRunning,
            onSetEnabled = viewModel::setDreamEnabled,
            onRunNow = viewModel::runDreamNow,
        )

        EmotionDaemonSection(
            emotionSnapshot = emotionSnapshot,
            daemonEnabled = state.daemonEnabled,
            daemonThoughtsCount = daemonThoughtsCount,
            daemonIntervalMinutes = state.daemonIntervalMinutes,
            onSetDaemonEnabled = viewModel::setDaemonEnabled,
            onSetDaemonInterval = viewModel::setDaemonIntervalMinutes,
        )

        TriggersSection(
            triggersEnabled = state.triggersEnabled,
            triggers = state.triggers,
            onSetEnabled = viewModel::setTriggersEnabled,
            onSave = viewModel::saveTrigger,
            onRemove = viewModel::removeTrigger,
        )

        // ── PRIVACY & DATA ──────────────────────────────────────
        SettingsGroupHeader("Privacy & Data")

        PrivacySection(
            appLockEnabled = state.appLockEnabled,
            morningBriefEnabled = state.morningBriefEnabled,
            morningBriefHour = state.morningBriefHour,
            calendarMonitorEnabled = state.calendarMonitorEnabled,
            decayEnabled = state.decayEnabled,
            screenControlEnabled = state.screenControlEnabled,
            onSetScreenControlEnabled = viewModel::setScreenControlEnabled,
            onSetAppLock = viewModel::setAppLockEnabled,
            onSetMorningBrief = viewModel::setMorningBriefEnabled,
            onSetMorningBriefHour = viewModel::setMorningBriefHour,
            onSetCalendarMonitor = viewModel::setCalendarMonitorEnabled,
            onSetDecayEnabled = viewModel::setDecayEnabled,
            onNavigateProfile = onNavigateProfile,
        )

        ToolPermissionsSection(
            toolPolicies = state.toolPolicies,
            onSetToolEnabled = viewModel::setToolEnabled,
            onSetToolConfirmation = viewModel::setToolConfirmation,
        )

        val context = androidx.compose.ui.platform.LocalContext.current
        val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
        DataAndBackupSection(
            backupState = backupState,
            onExport = {
                coroutineScope.launch {
                    val file = backupViewModel.prepareExportFile()
                    if (file != null) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(share, "Share Aura backup")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            },
            onStageImport = backupViewModel::stageImport,
            onConfirmImport = { replace -> backupViewModel.confirmImport(replace = replace) },
            onCancelImport = backupViewModel::cancelImport,
            onClearResult = backupViewModel::clearResult,
            onNavigateDiagnostics = onNavigateDiagnostics,
            onNavigateCrashLogs = onNavigateCrashLogs,
        )

        AppearanceSection(
            themeMode = state.themeMode,
            onSetThemeMode = viewModel::setThemeMode,
        )

        // ── SYSTEM ───────────────────────────────────────────────
        SettingsGroupHeader("System")

        SettingsClickableRow(
            title = "Agents",
            subtitle = "Create custom AI agents with their own personality, tools, and memory",
            onClick = onNavigateAgentEditor,
            icon = Icons.Filled.Groups,
        )

        EvolutionSettingsSection(
            onNavigateEvolutionInbox = onNavigateEvolutionInbox,
            onNavigateBeliefs = onNavigateBeliefs,
            onNavigateWorldModel = onNavigateWorldModel,
            onNavigateTasteProfile = onNavigateTasteProfile,
        )

        UsageSection(
            usage = usage,
            onReset = usageViewModel::reset,
        )

        // Footer
        Spacer(modifier = Modifier.height(AuraSpacing.xxl2))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(bottom = AuraSpacing.lg, top = AuraSpacing.xs),
        ) {
            Surface(
                color = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.12f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(AuraSpacing.lg),
            ) {
                Text(
                    text = "\u2726",
                    style = MaterialTheme.typography.titleLarge,
                    color = AuraThemeTokens.colors.actionPrimary,
                    modifier = Modifier.padding(AuraSpacing.sm),
                )
            }
            Spacer(modifier = Modifier.height(AuraSpacing.xs))
            Text(
                text = stringResource(R.string.aura),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
            )
            Text(
                text = "v" + BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.4f),
            )
        }
        Spacer(modifier = Modifier.height(AuraSpacing.md))
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = AuraThemeTokens.colors.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AuraSpacing.lg, bottom = AuraSpacing.xs),
    )
}