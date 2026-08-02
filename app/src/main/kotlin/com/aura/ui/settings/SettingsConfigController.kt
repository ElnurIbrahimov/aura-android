package com.aura.ui.settings

import com.aura.data.UserPreferences
import com.aura.integrations.IntegrationTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Extracted configuration controller for [SettingsViewModel].
 *
 * Handles SMTP email config, custom OpenAI-compatible endpoint config,
 * and Google/Microsoft integration toggles. Each cluster is pure state
 * mutation + a single coroutine launch for I/O.
 *
 * Takes only the state and dependencies it needs — not the full ViewModel.
 */
class SettingsConfigController(
    private val state: MutableStateFlow<SettingsUiState>,
    private val userPreferences: UserPreferences,
    private val integrationTokenStore: IntegrationTokenStore,
    private val scope: CoroutineScope,
) {

    // --- SMTP ---

    fun updateSmtpHost(value: String) {
        state.update { it.copy(smtpHost = value, smtpResult = null) }
    }

    fun updateSmtpPort(value: String) {
        state.update { it.copy(smtpPort = value.toIntOrNull() ?: 587, smtpResult = null) }
    }

    fun updateSmtpUsername(value: String) {
        state.update { it.copy(smtpUsername = value, smtpResult = null) }
    }

    fun updateSmtpPassword(value: kotlin.String) {
        state.update { it.copy(smtpPassword = value, smtpResult = null) }
    }

    fun updateSmtpFrom(value: String) {
        state.update { it.copy(smtpFrom = value, smtpResult = null) }
    }

    fun saveSmtpConfig() {
        if (state.value.smtpTesting) return
        state.update { it.copy(smtpTesting = true, smtpResult = "Saving…") }
        scope.launch {
            try {
                userPreferences.setSmtpConfig(
                    state.value.smtpHost,
                    state.value.smtpPort,
                    state.value.smtpUsername,
                    state.value.smtpPassword,
                    state.value.smtpFrom,
                )
                state.update {
                    it.copy(
                        smtpTesting = false,
                        smtpResult = "✓ SMTP saved",
                    )
                }
            } catch (e: Exception) {
                state.update { it.copy(smtpTesting = false, smtpResult = "✗ ${e.message}") }
            }
        }
    }

    // --- Google / Microsoft Integrations ---

    fun setGoogleClientId(id: String) {
        scope.launch { userPreferences.setGoogleClientId(id) }
    }

    fun setMicrosoftClientId(id: String) {
        scope.launch { userPreferences.setMicrosoftClientId(id) }
    }

    fun disconnectGoogle() {
        scope.launch { integrationTokenStore.disconnectGoogle() }
    }

    fun disconnectMicrosoft() {
        scope.launch { integrationTokenStore.disconnectMicrosoft() }
    }

    // --- Reasoning ---

    fun setReasoningEnabled(enabled: Boolean) {
        scope.launch { userPreferences.setReasoningEnabled(enabled) }
    }

    fun setReasoningBudget(budget: Int) {
        scope.launch { userPreferences.setReasoningBudget(budget) }
    }
}