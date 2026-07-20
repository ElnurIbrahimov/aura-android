package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.ui.components.ModelPickerSheet
import com.aura.ui.settings.CustomEndpointCard
import com.aura.ui.settings.ProviderKeyField
import com.aura.ui.settings.SETTINGS_CREDENTIAL_SPECS
import com.aura.ui.settings.RoleModelRow
import com.aura.ui.settings.SettingsSection
import com.aura.ui.settings.SettingsUiState
import com.aura.ui.settings.SmtpConfigCard
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.util.modelDisplayName

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiAndModelsSection(
    state: SettingsUiState,
    onCustomBaseUrlChange: (String) -> Unit,
    onCustomApiKeyChange: (String) -> Unit,
    onCustomTest: () -> Unit,
    onCustomClear: () -> Unit,
    onSmtpHostChange: (String) -> Unit,
    onSmtpPortChange: (String) -> Unit,
    onSmtpUsernameChange: (String) -> Unit,
    onSmtpPasswordChange: (kotlin.String) -> Unit,
    onSmtpFromChange: (String) -> Unit,
    onSmtpSave: () -> Unit,
    onUpdateCredential: (String, String) -> Unit,
    onVerifyKey: (String) -> Unit,
    onSetDefaultModel: (String) -> Unit,
    onSetEmbeddingModel: (String) -> Unit,
    onSetVisionModel: (String) -> Unit,
    onSetBackgroundModel: (String) -> Unit,
    onSetDeepModeModel: (String) -> Unit,
    onSetMoaReferenceModels: (List<String>) -> Unit,
    onSetMoaAggregatorModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
) {
    var activeModelRole by remember { mutableStateOf<String?>(null) }

    SettingsSection(
        emoji = "\uD83E\uDD16",
        title = "AI & Models",
        subtitle = "Providers, API keys, default and embedding models",
        initialExpanded = true,
    ) {
        Surface(
            color = AuraThemeTokens.colors.surface1,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (state.configuredProviders.isEmpty())
                        "No providers configured yet."
                    else
                        "Configured providers:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (state.configuredProviders.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.configuredProviders.forEach { name ->
                            AssistChip(
                                onClick = {},
                                label = { Text(name) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = AuraThemeTokens.colors.actionPrimary,
                                    labelColor = AuraThemeTokens.colors.onActionPrimary,
                                ),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        CustomEndpointCard(
            baseUrl = state.customBaseUrl,
            apiKey = state.customApiKey,
            isConfigured = state.customIsConfigured,
            testing = state.customTesting,
            result = state.customResult,
            onBaseUrlChange = onCustomBaseUrlChange,
            onApiKeyChange = onCustomApiKeyChange,
            onTest = onCustomTest,
            onClear = onCustomClear,
        )

        Spacer(modifier = Modifier.height(12.dp))

        SmtpConfigCard(
            host = state.smtpHost,
            port = state.smtpPort,
            username = state.smtpUsername,
            password = state.smtpPassword,
            from = state.smtpFrom,
            testing = state.smtpTesting,
            result = state.smtpResult,
            onHostChange = onSmtpHostChange,
            onPortChange = onSmtpPortChange,
            onUsernameChange = onSmtpUsernameChange,
            onPasswordChange = onSmtpPasswordChange,
            onFromChange = onSmtpFromChange,
            onSave = onSmtpSave,
        )

        Text(
            text = "API Keys",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Stored locally. Model providers use Save & Test; tool services use Save.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(4.dp))

        SETTINGS_CREDENTIAL_SPECS.forEach { credential ->
            ProviderKeyField(
                label = credential.label,
                value = state.keyDrafts[credential.prefix].orEmpty(),
                onValueChange = { value -> onUpdateCredential(credential.prefix, value) },
                helperText = credential.helperText,
                onVerify = { onVerifyKey(credential.prefix) },
                verifyResult = state.verifyResults[credential.prefix],
                verifying = state.verifying == credential.prefix,
                credentialState = state.credentialStates[credential.prefix],
                actionLabel = if (credential.testsModelCatalog) "Save & Test" else "Save",
                requiresTest = credential.testsModelCatalog,
                enabled = credential.isConsumed,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Model roles",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Every role is selected from verified provider catalogs. Unset roles never invent a fallback model.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))

        RoleModelRow("Chat default", state.defaultModel) { activeModelRole = "chat" }
        RoleModelRow("Embedding", state.embeddingModel) { activeModelRole = "embedding" }
        RoleModelRow("Vision", state.visionModel) { activeModelRole = "vision" }
        RoleModelRow("Background tasks", state.backgroundModel) { activeModelRole = "background" }
        RoleModelRow("Deep Mode", state.deepModeModel) { activeModelRole = "deep" }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Mixture of Agents",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Choose at least two reference models and one aggregator. Aura then exposes the virtual MoA custom model.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.moaReferenceModels.forEach { model ->
                AssistChip(
                    onClick = { onSetMoaReferenceModels(state.moaReferenceModels.filterNot { it == model }) },
                    label = { Text("${modelDisplayName(model)} x") },
                )
            }
        }
        RoleModelRow(
            title = "Reference models (${state.moaReferenceModels.size}/4)",
            value = state.moaReferenceModels.firstOrNull().orEmpty(),
        ) { activeModelRole = "moa-reference" }
        RoleModelRow("Aggregator", state.moaAggregatorModel) { activeModelRole = "moa-aggregator" }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onRefreshModels, enabled = !state.modelsLoading) {
                Text(if (state.modelsLoading) "Refreshing..." else "Refresh catalog")
            }
            Text(
                text = when {
                    state.modelsError != null -> state.modelsError!!
                    state.availableModels.isEmpty() -> "Save & Test a provider to load models"
                    else -> "${state.availableModels.size} verified models"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.modelsError == null) AuraThemeTokens.colors.textPrimary else AuraThemeTokens.colors.error,
            )
        }

        activeModelRole?.let { role ->
            val selectableModels = when (role) {
                "embedding" -> state.availableModels.filter { it.startsWith("ollama:") }
                "moa-reference", "moa-aggregator" -> state.availableModels.filterNot { it.startsWith("moa:") }
                else -> state.availableModels
            }
            val current = when (role) {
                "chat" -> state.defaultModel
                "embedding" -> state.embeddingModel
                "vision" -> state.visionModel
                "background" -> state.backgroundModel
                "deep" -> state.deepModeModel
                "moa-aggregator" -> state.moaAggregatorModel
                "moa-reference" -> state.moaReferenceModels.firstOrNull().orEmpty()
                else -> ""
            }
            ModelPickerSheet(
                currentModel = current,
                models = selectableModels,
                isLoading = state.modelsLoading,
                errorMessage = state.modelsError,
                onPick = { model ->
                    when (role) {
                        "chat" -> onSetDefaultModel(model)
                        "embedding" -> onSetEmbeddingModel(model)
                        "vision" -> onSetVisionModel(model)
                        "background" -> onSetBackgroundModel(model)
                        "deep" -> onSetDeepModeModel(model)
                        "moa-aggregator" -> onSetMoaAggregatorModel(model)
                        "moa-reference" -> {
                            val selected = state.moaReferenceModels
                            onSetMoaReferenceModels(if (model in selected) selected - model else selected + model)
                        }
                    }
                    if (role != "moa-reference") activeModelRole = null
                },
                onRefresh = onRefreshModels,
                onDismiss = { activeModelRole = null },
            )
        }
    }
}