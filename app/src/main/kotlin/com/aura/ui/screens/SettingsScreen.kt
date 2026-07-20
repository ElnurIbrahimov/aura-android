package com.aura.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.aura.ui.settings.SettingsClickableRow
import com.aura.ui.settings.SettingsViewModel
import com.aura.ui.settings.UsageViewModel
import com.aura.ui.settings.sections.AiAndModelsSection
import com.aura.ui.settings.sections.AppearanceSection
import com.aura.ui.settings.sections.DataAndBackupSection
import com.aura.ui.settings.sections.EmotionDaemonSection
import com.aura.ui.settings.sections.EvolutionSettingsSection
import com.aura.ui.settings.sections.McpServersSection
import com.aura.ui.settings.sections.ModelRolesSection
import com.aura.ui.settings.sections.PersonaSection
import com.aura.ui.settings.sections.PrivacySection
import com.aura.ui.settings.sections.ToolPermissionsSection
import com.aura.ui.settings.sections.UsageSection
import com.aura.ui.theme.AuraThemeTokens
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    onNavigateProfile: () -> Unit,
    onNavigateIdentity: () -> Unit = {},
    onNavigateDiagnostics: () -> Unit = {},
    onNavigateEvolutionInbox: () -> Unit = {},
    onNavigateBeliefs: () -> Unit = {},
    onNavigateAgentEditor: () -> Unit = {},
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
            .padding(horizontal = 20.dp),
    ) {
        // Header
        Column(modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = AuraThemeTokens.colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Connect providers, manage memory, customize Aura",
                style = MaterialTheme.typography.bodyLarge,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 1. AI & Models
        AiAndModelsSection(
            state = state,
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
            onSetVisionModel = viewModel::setVisionModel,
            onSetBackgroundModel = viewModel::setBackgroundModel,
            onSetDeepModeModel = viewModel::setDeepModeModel,
            onSetMoaReferenceModels = viewModel::setMoaReferenceModels,
            onSetMoaAggregatorModel = viewModel::setMoaAggregatorModel,
            onRefreshModels = viewModel::refreshModels,
        )

        // 2. Usage
        UsageSection(
            usage = usage,
            onReset = usageViewModel::reset,
        )

        // 3. Appearance
        AppearanceSection(
            themeMode = state.themeMode,
            onSetThemeMode = viewModel::setThemeMode,
        )

        // 4. Persona
        PersonaSection(
            identityCustomized = state.identityCustomized,
            specialistOverrides = state.specialistOverrides,
            onNavigateIdentity = onNavigateIdentity,
            onSetSpecialistOverrides = viewModel::setSpecialistOverrides,
        )

        // 5. Tool Permissions
        ToolPermissionsSection(
            toolPolicies = state.toolPolicies,
            onSetToolEnabled = viewModel::setToolEnabled,
            onSetToolConfirmation = viewModel::setToolConfirmation,
        )

        // 6. Model Roles
        ModelRolesSection(
            roleModels = state.roleModels,
            availableModels = state.availableModels,
            onSetRoleModel = viewModel::setRoleModel,
        )

        // 7. MCP Servers
        McpServersSection(
            mcpServers = state.mcpServers,
            mcpDiscoveredTools = state.mcpDiscoveredTools,
            onTestConnection = viewModel::testMcpConnection,
            onDisconnect = viewModel::disconnectMcpServer,
        )

        // 8. Privacy
        PrivacySection(
            appLockEnabled = state.appLockEnabled,
            morningBriefEnabled = state.morningBriefEnabled,
            morningBriefHour = state.morningBriefHour,
            calendarMonitorEnabled = state.calendarMonitorEnabled,
            onSetAppLock = viewModel::setAppLockEnabled,
            onSetMorningBrief = viewModel::setMorningBriefEnabled,
            onSetMorningBriefHour = viewModel::setMorningBriefHour,
            onSetCalendarMonitor = viewModel::setCalendarMonitorEnabled,
            onNavigateProfile = onNavigateProfile,
        )

        // 9. Emotion & Daemon
        EmotionDaemonSection(
            emotionSnapshot = emotionSnapshot,
            daemonEnabled = state.daemonEnabled,
            daemonThoughtsCount = daemonThoughtsCount,
            onSetDaemonEnabled = viewModel::setDaemonEnabled,
        )

        // 9b. Agents — create and manage AI agents
        SettingsClickableRow(
            title = "Agents",
            subtitle = "Create custom AI agents with their own personality, tools, and memory",
            onClick = onNavigateAgentEditor,
        )

        // 10. Evolution
        EvolutionSettingsSection(
            onNavigateEvolutionInbox = onNavigateEvolutionInbox,
            onNavigateBeliefs = onNavigateBeliefs,
        )

        // 11. Data & Backup
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
            onConfirmImport = { backupViewModel.confirmImport(purgeFirst = false) },
            onCancelImport = backupViewModel::cancelImport,
            onClearResult = backupViewModel::clearResult,
            onNavigateDiagnostics = onNavigateDiagnostics,
        )

        // Footer
        Spacer(modifier = Modifier.height(20.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(bottom = 24.dp, top = 8.dp),
        ) {
            Surface(
                color = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.12f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            ) {
                Text(
                    text = "\u2726",
                    style = MaterialTheme.typography.titleLarge,
                    color = AuraThemeTokens.colors.actionPrimary,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Aura",
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
        Spacer(modifier = Modifier.height(16.dp))
    }
}