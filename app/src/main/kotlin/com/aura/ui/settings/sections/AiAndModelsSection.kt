package com.aura.ui.settings.sections

import com.aura.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material3.Switch
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
import com.aura.ui.theme.AuraSpacing
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.Icons

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiAndModelsSection(
    state: SettingsUiState,
    /**
     * The ChatGPT sign-in card. Passed as a slot rather than eight more
     * parameters on a function that already carries twenty, and deliberately
     * without a default — an empty default here would silently render nothing
     * at a call site that forgot it, which is how the Council and Evolution
     * buttons ended up dead.
     */
    chatGptCard: @Composable () -> Unit,
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
    onSetImageModel: (String) -> Unit,
    onSetVideoModel: (String) -> Unit,
    onSetVoiceModel: (String) -> Unit,
    onSetVisionModel: (String) -> Unit,
    onSetMoaReferenceModels: (List<String>) -> Unit,
    onSetMoaAggregatorModel: (String) -> Unit,
    onSetPlanningEnabled: (Boolean) -> Unit,
    onSetPromptCachingEnabled: (Boolean) -> Unit,
    onRefreshModels: () -> Unit,
) {
    var activeModelRole by remember { mutableStateOf<String?>(null) }

    SettingsSection(
        icon = Icons.Filled.SmartToy,
        title = "AI & Models",
        subtitle = "Providers, API keys, default and embedding models",
        initialExpanded = true,
    ) {
        Surface(
            color = AuraThemeTokens.colors.surface1,
            shape = RoundedCornerShape(AuraSpacing.medium),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(AuraSpacing.sm)) {
                Text(
                    text = if (state.configuredProviders.isEmpty())
                        "No providers configured yet."
                    else
                        "Configured providers:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (state.configuredProviders.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.small)) {
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

        Spacer(modifier = Modifier.height(AuraSpacing.sm))

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

        Spacer(modifier = Modifier.height(AuraSpacing.sm))

        chatGptCard()

        Spacer(modifier = Modifier.height(AuraSpacing.sm))

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
            text = stringResource(R.string.api_keys),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.stored_locally_model_providers_use_save),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(AuraSpacing.xxs))

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
                placeholder = credential.placeholder,
                actionLabel = if (credential.testsModelCatalog) "Save & Test" else "Save",
                requiresTest = credential.testsModelCatalog,
                enabled = credential.isConsumed,
            )
        }

        Spacer(modifier = Modifier.height(AuraSpacing.sm))

        Text(
            text = stringResource(R.string.model_roles),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.every_role_is_selected_from_verified),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(AuraSpacing.small))

        // Chat default, plus the models that are NOT `ModelRole.configurable`.
        //
        // "Background tasks" and "Deep Mode" used to sit here as well, and were
        // the same two preference keys the Model Roles section below already
        // owned under the names Background and Deep Research — `background_model`
        // and `deep_mode_model`. Two controls, two labels, one key each: setting
        // either silently moved the other, and neither said so. Model Roles keeps
        // them, because it is the one that can tell an explicit choice from an
        // inherited fallback.
        //
        // Chat default stays here and is excluded from Model Roles instead. It is
        // the setting people come to Settings for, and Model Roles describes
        // itself as overrides *of* the default — the default is not one of its
        // own overrides.
        RoleModelRow("Chat default", state.defaultModel) { activeModelRole = "chat" }
        // Blank here does not mean "auto", the way it does for the capability
        // rows below — it means recall is running on keywords alone.
        // `ProviderKeys.embeddingModel` defaults to "", which falls through to
        // `LocalEmbedder`: a 384-dimension hash-and-project sketch its own KDoc
        // calls a pseudo-embedding. It produces vectors of the right shape that
        // carry no meaning, so "I love Kotlin" does not recall for "programming
        // languages I enjoy".
        //
        // That was invisible. The row rendered an empty value, and an empty
        // value reads as a default someone chose. Saying it plainly is the
        // whole fix — the setting has worked all along.
        RoleModelRow("Embedding", state.embeddingModel.ifBlank { EMBEDDING_UNSET_LABEL }) {
            activeModelRole = "embedding"
        }
        RoleModelRow("Vision", state.visionModel) { activeModelRole = "vision" }

        // Capability models. These lists come from the same catalogs as the
        // chat models, filtered by what each model can actually do — so a
        // provider that serves images (Agnes AI serves agnes-image-2.1-flash
        // from the same base URL as its chat models) shows up here without
        // anyone having written an adapter for it.
        //
        // Blank means "use the first discovered backend", so leaving these
        // untouched still works; the picker is an override.
        if (state.imageModels.isNotEmpty()) {
            RoleModelRow("Image", state.imageModel.ifBlank { AUTO_LABEL }) { activeModelRole = "image" }
        }
        if (state.videoModels.isNotEmpty()) {
            RoleModelRow("Video", state.videoModel.ifBlank { AUTO_LABEL }) { activeModelRole = "video" }
        }
        if (state.voiceModels.isNotEmpty()) {
            RoleModelRow("Voice", state.voiceModel.ifBlank { AUTO_LABEL }) { activeModelRole = "voice" }
        }

        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        Text(
            text = stringResource(R.string.mixture_of_agents),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.choose_at_least_two_reference_models),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.small)) {
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
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
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

        Spacer(modifier = Modifier.height(AuraSpacing.sm))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.plan_before_answering), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (state.planningEnabled) {
                        "On - extra model call per message; better tool picks, slower replies"
                    } else {
                        "Off - replies start immediately"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = state.planningEnabled, onCheckedChange = onSetPlanningEnabled)
        }

        Spacer(modifier = Modifier.height(AuraSpacing.sm))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.prompt_caching), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (state.promptCachingEnabled) {
                        "On - reuses the fixed part of the prompt across a reply's steps"
                    } else {
                        "Off - every step re-sends the whole prompt at full price"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = state.promptCachingEnabled, onCheckedChange = onSetPromptCachingEnabled)
        }

        activeModelRole?.let { role ->
            val selectableModels = when (role) {
                // Not `availableModels`: that list is `capability.isChatUsable`,
                // and the classifier tags any id with an `embed` segment as
                // Embedding — so nomic-embed-text and mxbai-embed-large, the
                // models this picker exists for, could never appear in it. The
                // `ollama:` restriction now lives in the ViewModel, next to the
                // reason for it, along with why the new list still includes the
                // ids the classifier calls Unknown.
                "embedding" -> state.embeddingModels
                "image" -> state.imageModels
                "video" -> state.videoModels
                "voice" -> state.voiceModels
                "moa-reference", "moa-aggregator" -> state.availableModels.filterNot { it.startsWith("moa:") }
                else -> state.availableModels
            }
            val current = when (role) {
                "chat" -> state.defaultModel
                "embedding" -> state.embeddingModel
                "vision" -> state.visionModel
                "image" -> state.imageModel
                "video" -> state.videoModel
                "voice" -> state.voiceModel
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
                        "image" -> onSetImageModel(model)
                        "video" -> onSetVideoModel(model)
                        "voice" -> onSetVoiceModel(model)
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

/** Shown when a capability model is unset — it still works, via discovery. */
private const val AUTO_LABEL = "Automatic"

/**
 * Shown when no embedding model is set.
 *
 * Deliberately *not* [AUTO_LABEL]. The capability rows fall back to a real
 * discovered backend and genuinely are automatic; this one falls back to
 * `LocalEmbedder`, which returns a hash sketch rather than an embedding. Recall
 * still works — FTS4 and BM25 do the lexical half — but nothing semantic
 * happens, and a row reading "Automatic" would say the opposite.
 */
private const val EMBEDDING_UNSET_LABEL = "Not set — recall is keyword-only"
