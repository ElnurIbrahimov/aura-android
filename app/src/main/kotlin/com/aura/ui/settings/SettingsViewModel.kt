package com.aura.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.IdentityStore
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolRisk
import com.aura.agent.policy.ConfirmationLevel
import com.aura.agent.policy.ToolPolicy
import com.aura.agent.policy.ToolPolicyDefaults
import com.aura.agent.policy.ToolPolicyStore
import com.aura.data.UserPreferences
import com.aura.mcp.McpClientManager
import com.aura.mcp.McpConnectionState
import com.aura.mcp.McpServerConfig
import com.aura.mcp.McpServerHealth
import com.aura.mcp.McpToolInfo
import com.aura.providers.CustomEndpointState
import com.aura.providers.ModelCatalog
import com.aura.providers.ModelCatalogRepository
import com.aura.providers.ModelRole
import com.aura.providers.ModelRoleRouter
import com.aura.providers.ProviderCredentialState
import com.aura.providers.ProviderKeys
import com.aura.providers.ProviderRegistry
import com.aura.providers.ProviderStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsCredentialSpec(
    val prefix: String,
    val label: String,
    val helperText: String,
    val testsModelCatalog: Boolean,
    /**
     * True when the user's key is actually consumed by at least one tool
     * or provider registration today. Capability providers whose keys
     * are persisted but not yet wired into any tool render disabled
     * with a "Coming soon" hint so the user knows the key is held
     * but not used.
     */
    val isConsumed: Boolean = testsModelCatalog,
)

val SETTINGS_CREDENTIAL_SPECS: List<SettingsCredentialSpec> = listOf(
    SettingsCredentialSpec("ollama", "Ollama Cloud", "Get a key at ollama.com/settings/keys", true),
    SettingsCredentialSpec("anthropic", "Anthropic", "Get a key at console.anthropic.com/settings/keys", true),
    SettingsCredentialSpec("openai", "OpenAI", "Get a key at platform.openai.com/api-keys", true),
    SettingsCredentialSpec("deepseek", "DeepSeek", "Get a key at platform.deepseek.com/api_keys", true),
    SettingsCredentialSpec("gemini", "Gemini", "Get a key at aistudio.google.com/apikey", true),
    SettingsCredentialSpec("groq", "Groq", "Get a key at console.groq.com/keys", true),
    SettingsCredentialSpec("openrouter", "OpenRouter", "Get a key at openrouter.ai/keys", true),
    SettingsCredentialSpec("mistral", "Mistral AI", "Get a key at console.mistral.ai/api-keys", true),
    SettingsCredentialSpec("xai", "xAI Grok", "Get a key at console.x.ai", true),
    SettingsCredentialSpec("together", "Together AI", "Get a key at api.together.xyz/settings/api-keys", true),
    SettingsCredentialSpec("cerebras", "Cerebras", "Get a key at cloud.cerebras.ai", true),
    SettingsCredentialSpec("nvidia", "NVIDIA NIM", "Get a key at build.nvidia.com/explore/discover", true),
    SettingsCredentialSpec("llama", "Meta Llama", "Get a key at llama.developer.meta.com", true),
    SettingsCredentialSpec("chatgpt", "ChatGPT Subscription", "Paste a token from `codex login` (OpenAI subscription auth)", true),
    SettingsCredentialSpec("agnes", "Agnes AI", "Get a key at agnes-ai.com/dashboard", true),
    // "Custom Endpoint" is now a dedicated card (CustomEndpointCard) — it
    // needs both a base URL and an API key, so it can't be a single
    // ProviderKeyField row. Don't add it back to this list.
    SettingsCredentialSpec("moa", "Mixture-of-Agents", "Configure MoA presets in code; no API key", true),
    SettingsCredentialSpec("brave", "Brave Search", "Used by Brave web search tools", false, isConsumed = true),
    SettingsCredentialSpec("tavily", "Tavily Search", "Used by Tavily research tools", false, isConsumed = true),
    SettingsCredentialSpec("firecrawl", "Firecrawl", "Used by Firecrawl page extraction", false, isConsumed = true),
    SettingsCredentialSpec("exa", "Exa Search", "Neural search — get a key at exa.ai/dashboard", false, isConsumed = true),
    SettingsCredentialSpec("jina", "Jina Reader", "URL-to-text search — get a key at jina.ai/reader", false, isConsumed = true),
    // Capability providers — consumed by ImageGenCapabilityTool, TtsSpeakTool,
    // and other capability-backed tools. Keys persist and are used at runtime.
    SettingsCredentialSpec("elevenlabs", "ElevenLabs", "TTS — get a key at elevenlabs.io/app/settings/api-keys", false, isConsumed = true),
    SettingsCredentialSpec("stability", "Stability AI", "Image generation — platform.stability.ai/account/keys", false, isConsumed = true),
    SettingsCredentialSpec("kling", "Kling AI", "Video generation — klingai.com/dev", false, isConsumed = true),
    SettingsCredentialSpec("worldlabs", "World Labs", "3D world generation — worldlabs.ai", false, isConsumed = true),
)

private val TOOL_CREDENTIAL_PREFIXES: Set<String> = SETTINGS_CREDENTIAL_SPECS
    .filterNot { it.testsModelCatalog }
    .mapTo(mutableSetOf()) { it.prefix }

data class SettingsUiState(
    val keyDrafts: Map<String, String> = ProviderKeys.PREFIXES.associateWith { "" },
    /** Map of ModelRole to selected model id (empty string = unset). */
    val roleModels: Map<ModelRole, String> = emptyMap(),
    val defaultModel: String = "",
    val visionModel: String = "",
    val backgroundModel: String = "",
    val deepModeModel: String = "",
    val moaReferenceModels: List<String> = emptyList(),
    val moaAggregatorModel: String = "",
    val firstRunComplete: Boolean = false,
    val configuredProviders: List<String> = emptyList(),
    val appLockEnabled: Boolean = false,
    val morningBriefEnabled: Boolean = true,
    val calendarMonitorEnabled: Boolean = true,
    /**
     * Current embedding model id, persisted via ProviderKeys. Used
     * to drive the Settings embedding-model picker and to restore
     * the choice after backup import.
     */
    val embeddingModel: String = "",
    val themeMode: String = "system",
    /** Full identity text resolved from DataStore override or bundled asset. */
    val identityText: String = "",
    /** True when the user has a non-blank DataStore identity override. */
    val identityCustomized: Boolean = false,
    val specialistOverrides: String = "{}",
    /**
     * Per-tool policies keyed by tool name. Default policies are merged in
     * the ViewModel so the UI always sees a complete map.
     */
    val toolPolicies: Map<String, ToolPolicy> = emptyMap(),
    /**
     * Configured MCP servers and any discovered tools per server.
     */
    val mcpServers: List<McpServerConfig> = emptyList(),
    val mcpDiscoveredTools: Map<String, List<McpToolInfo>> = emptyMap(),
    /**
     * Per-provider verify result: prefix → "✓ Verified — N models"
     * or "✗ Failed: ...". Null = not tested yet.
     */
    val verifyResults: Map<String, String> = emptyMap(),
    val verifying: String? = null,
    val morningBriefHour: Int = 7,
    val availableModels: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val providerTests: Map<String, ProviderTestResult> = emptyMap(),
    val credentialStates: Map<String, ProviderCredentialState> = emptyMap(),
    // Custom endpoint card state.
    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val customIsConfigured: Boolean = false,
    val customTesting: Boolean = false,
    val customResult: String? = null,
    // SMTP config card state.
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpUsername: String = "",
    val smtpPassword: kotlin.String = "",
    val smtpFrom: String = "",
    val smtpTesting: Boolean = false,
    val smtpResult: String? = null,
    val evolutionEnabled: Boolean = false,
    val evolutionIntervalHours: Int = 24,
    val evolutionShadowEnabled: Boolean = false,
    val daemonEnabled: Boolean = false,
    /** Distinct from credentialStates["custom"]: the URL/key are stored
     *  outside ProviderKeys, so this is a separate UI state. */
)

/** Editable in-memory model for an MCP server being added or edited. */
data class McpServerDraft(
    val name: String = "",
    val url: String = "",
    val authToken: String = "",
    val trustedLocal: Boolean = false,
    val allowedToolPrefixes: String = "",
    val deniedTools: String = "",
    val maxConcurrentCalls: Int = 4,
)

enum class ProviderTestPhase { Idle, Saving, Testing, Verified, Failed }

data class ProviderTestResult(
    val phase: ProviderTestPhase = ProviderTestPhase.Idle,
    val message: String? = null,
    val modelCount: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val providerKeys: ProviderKeys,
    private val userPreferences: UserPreferences,
    private val identityStore: IdentityStore,
    private val modelCatalogRepository: ModelCatalogRepository,
    private val customEndpointState: CustomEndpointState,
    private val toolRegistry: ToolRegistry,
    private val toolPolicyStore: ToolPolicyStore,
    private val modelRoleRouter: ModelRoleRouter,
    private val mcpClientManager: McpClientManager,
    private val mcpToolBridge: com.aura.mcp.McpToolBridge,
    private val secureDataStore: com.aura.security.SecureDataStore,
) : ViewModel() {

    private fun configuredProviderLabels(): List<String> =
        providerRegistry.configured()
            .sortedBy { it.prefix }
            .map { "${it.prefix} (${it.displayName})" }

    private fun defaultPolicies(): Map<String, ToolPolicy> =
        toolRegistry.all().associate { tool ->
            tool.name to ToolPolicyDefaults.forTool(tool.name, tool.risk)
        }

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            providerKeys.credentialStates.collectLatest { states ->
                _state.update { it.copy(credentialStates = states) }
            }
        }
        viewModelScope.launch {
            modelCatalogRepository.catalog.collectLatest(::applyCatalog)
        }
        viewModelScope.launch {
            // Reactive read so the card flips from "Unsaved" → "Configured"
            // the moment the user taps Save & Test.
            customEndpointState.state.collectLatest { (url, key, _) ->
                _state.update {
                    it.copy(
                        customBaseUrl = url,
                        customIsConfigured = url.isNotBlank() && key.isNotBlank(),
                    )
                }
            }
        }
        viewModelScope.launch {
            toolPolicyStore.allPolicies.collectLatest { stored ->
                val merged = defaultPolicies().toMutableMap()
                stored.forEach { (name, policy) -> merged[name] = policy }
                _state.update { it.copy(toolPolicies = merged) }
            }
        }
    }

    fun reload() {
        viewModelScope.launch {
            // Wait for the initial DataStore load to finish so the
            // configured providers list doesn't show "0 configured"
            // on first launch while the keys are still being read.
            providerKeys.loaded.first { it }
            val configured = configuredProviderLabels()
            val defaultModel = userPreferences.defaultModel.first()
            val visionModel = userPreferences.visionModel.first()
            val backgroundModel = userPreferences.backgroundModel.first()
            val deepModeModel = userPreferences.deepModeModel.first()
            val moaReferenceModels = userPreferences.moaReferenceModels.first()
            val moaAggregatorModel = userPreferences.moaAggregatorModel.first()
            val firstRunComplete = userPreferences.firstRunComplete.first()
            val appLockEnabled = userPreferences.appLockEnabled.first()
            val morningBriefEnabled = userPreferences.morningBriefEnabled.first()
            val calendarMonitorEnabled = userPreferences.calendarMonitorEnabled.first()
            val embeddingModel = providerKeys.embeddingModel
            val themeMode = userPreferences.themeMode.first()
            val identityText = identityStore.readCurrent()
            val identityCustomized = identityStore.hasOverride()
            val specialistOverrides = userPreferences.specialistOverrides.first()
            val morningBriefHour = userPreferences.morningBriefHour.first()
            val smtpHost = userPreferences.smtpHost.first()
            val smtpPort = userPreferences.smtpPort.first()
            val smtpUsername = userPreferences.smtpUsername.first()
            val smtpPassword = userPreferences.smtpPassword.first()
            val smtpFrom = userPreferences.smtpFrom.first()
            val evolutionEnabled = userPreferences.evolutionEnabled.first()
            val evolutionIntervalHours = userPreferences.evolutionIntervalHours.first()
            val evolutionShadowEnabled = userPreferences.evolutionShadowEnabled.first()
            val daemonEnabled = userPreferences.daemonEnabled.first()
            val mcpServersJson = userPreferences.mcpServersJson.first()
            val roleModels = ModelRole.configurable.associateWith { role ->
                modelRoleRouter.resolve(role).orEmpty()
            }
            val mergedPolicies = defaultPolicies().toMutableMap().apply {
                toolPolicyStore.allPolicies.first().forEach { (name, policy) -> this[name] = policy }
            }
            _state.value = SettingsUiState(
                keyDrafts = ProviderKeys.PREFIXES.associateWith { prefix ->
                    providerKeys.keyFor(prefix).orEmpty()
                },
                roleModels = roleModels,
                defaultModel = defaultModel.orEmpty(),
                visionModel = visionModel.orEmpty(),
                backgroundModel = backgroundModel.orEmpty(),
                deepModeModel = deepModeModel.orEmpty(),
                moaReferenceModels = moaReferenceModels,
                moaAggregatorModel = moaAggregatorModel.orEmpty(),
                firstRunComplete = firstRunComplete,
                configuredProviders = configured,
                appLockEnabled = appLockEnabled,
                morningBriefEnabled = morningBriefEnabled,
                calendarMonitorEnabled = calendarMonitorEnabled,
                embeddingModel = embeddingModel,
                themeMode = themeMode,
                identityText = identityText,
                identityCustomized = identityCustomized,
                specialistOverrides = specialistOverrides,
                toolPolicies = mergedPolicies,
                mcpServers = parseMcpServers(mcpServersJson),
                mcpDiscoveredTools = emptyMap(),
                morningBriefHour = morningBriefHour,
                smtpHost = smtpHost,
                smtpPort = smtpPort,
                smtpUsername = smtpUsername,
                smtpPassword = smtpPassword,
                smtpFrom = smtpFrom,
                evolutionEnabled = evolutionEnabled,
                evolutionIntervalHours = evolutionIntervalHours,
                evolutionShadowEnabled = evolutionShadowEnabled,
                daemonEnabled = daemonEnabled,
            )
        }
    }

    fun setDefaultModel(model: String) {
        viewModelScope.launch {
            userPreferences.setDefaultModel(model)
            _state.update { it.copy(defaultModel = model) }
        }
    }

    fun setVisionModel(model: String) {
        viewModelScope.launch {
            userPreferences.setVisionModel(model)
            _state.update { it.copy(visionModel = model) }
        }
    }

    fun setBackgroundModel(model: String) {
        viewModelScope.launch {
            userPreferences.setBackgroundModel(model)
            _state.update { it.copy(backgroundModel = model) }
        }
    }

    fun setDeepModeModel(model: String) {
        viewModelScope.launch {
            userPreferences.setDeepModeModel(model)
            _state.update { it.copy(deepModeModel = model) }
        }
    }

    fun setMoaReferenceModels(models: List<String>) {
        viewModelScope.launch {
            val selected = models.distinct().take(4)
            userPreferences.setMoaReferenceModels(selected)
            _state.update { it.copy(moaReferenceModels = selected) }
            modelCatalogRepository.refreshProvider("moa", force = true)
        }
    }

    fun setMoaAggregatorModel(model: String) {
        viewModelScope.launch {
            userPreferences.setMoaAggregatorModel(model)
            _state.update { it.copy(moaAggregatorModel = model) }
            modelCatalogRepository.refreshProvider("moa", force = true)
        }
    }

    fun markFirstRunComplete() {
        viewModelScope.launch {
            userPreferences.setFirstRunComplete(true)
            _state.update { it.copy(firstRunComplete = true) }
        }
    }

    /**
     * Toggle the biometric app lock. The actual gate that enforces
     * the lock lives in [com.aura.MainActivity]; this just persists
     * the choice. We don't run a biometric challenge on toggle —
     * the user is already in the app and authenticated to the OS
     * session. The challenge fires the next time the app is
     * launched (or resumed from background).
     */
    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setAppLockEnabled(enabled)
            _state.update { it.copy(appLockEnabled = enabled) }
        }
    }

    /**
     * Toggle the morning-brief schedule. The actual cancel /
     * reschedule happens in [com.aura.proactive.ProactiveBootstrap]
     * on the next app launch — toggling in Settings persists the
     * choice, and the worker state converges when the app next
     * starts. This is intentional: the Settings VM has no business
     * touching WorkManager directly (it would couple the UI layer
     * to the proactive subsystem).
     */
    fun setMorningBriefEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setMorningBriefEnabled(enabled)
            _state.update { it.copy(morningBriefEnabled = enabled) }
        }
    }

    fun setMorningBriefHour(hour: Int) {
        viewModelScope.launch {
            userPreferences.setMorningBriefHour(hour)
            _state.update { it.copy(morningBriefHour = hour) }
        }
    }

    /**
     * Toggle the calendar-monitor foreground service. Same pattern
     * as [setMorningBriefEnabled]: persists the choice, bootstrap
     * converges the actual FGS state on next app launch.
     */
    fun setCalendarMonitorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setCalendarMonitorEnabled(enabled)
            _state.update { it.copy(calendarMonitorEnabled = enabled) }
        }
    }

    fun setEmbeddingModel(model: String) {
        viewModelScope.launch {
            providerKeys.setEmbeddingModel(model)
            _state.update { it.copy(embeddingModel = model) }
        }
    }

    fun setEvolutionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setEvolutionEnabled(enabled)
            _state.update { it.copy(evolutionEnabled = enabled) }
        }
    }

    fun setEvolutionIntervalHours(hours: Int) {
        viewModelScope.launch {
            userPreferences.setEvolutionIntervalHours(hours)
            _state.update { it.copy(evolutionIntervalHours = hours) }
        }
    }

    fun setEvolutionShadowEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setEvolutionShadowEnabled(enabled)
            _state.update { it.copy(evolutionShadowEnabled = enabled) }
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        // Persist directly to DataStore; the ChatViewModel observes
        // ttsEnabled from its own DataStore Flow. No need to mirror
        // into SettingsUiState — the toggle isn't displayed in
        // Settings UI yet, and ChatContent reads from ChatViewModel.
        viewModelScope.launch { userPreferences.setTtsEnabled(enabled) }
    }

    fun setIncognitoDefault(enabled: Boolean) {
        // Same shape as setTtsEnabled: ChatViewModel observes
        // incognitoDefault from its own DataStore Flow. No state mirror.
        viewModelScope.launch { userPreferences.setIncognitoDefault(enabled) }
    }

    fun setImageModel(model: String) {
        // ImageGenTool reads from UserPreferences.imageModel directly;
        // no UI state needed in Settings.
        viewModelScope.launch { userPreferences.setImageModel(model) }
    }

    fun setDaemonEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setDaemonEnabled(enabled)
            _state.update { it.copy(daemonEnabled = enabled) }
        }
    }

    /** Emotion snapshot for the Emotion & Daemon settings section. */
    val emotionSnapshot: kotlinx.coroutines.flow.StateFlow<com.aura.emotion.EmotionEngine.EmotionSnapshot?> =
        MutableStateFlow<com.aura.emotion.EmotionEngine.EmotionSnapshot?>(null)

    /** Count of daemon-produced proactive events for the Emotion & Daemon settings section. */
    val daemonThoughtsCount: kotlinx.coroutines.flow.StateFlow<Int> =
        MutableStateFlow(0)

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            userPreferences.setThemeMode(mode)
            _state.update { it.copy(themeMode = mode) }
        }
    }

    /** Persist the DataStore-backed identity used by the next chat send. */
    fun saveIdentity(text: String) {
        viewModelScope.launch {
            if (text.isBlank()) {
                identityStore.resetToDefault()
            } else {
                identityStore.save(text)
            }
            _state.update {
                it.copy(
                    identityText = identityStore.readCurrent(),
                    identityCustomized = identityStore.hasOverride(),
                )
            }
        }
    }

    /** Clear the custom identity and fall back to the bundled asset. */
    fun resetIdentity() {
        viewModelScope.launch {
            identityStore.resetToDefault()
            _state.update {
                it.copy(
                    identityText = identityStore.readCurrent(),
                    identityCustomized = identityStore.hasOverride(),
                )
            }
        }
    }

    fun setSpecialistOverrides(json: String) {
        viewModelScope.launch {
            userPreferences.setSpecialistOverrides(json)
            _state.update { it.copy(specialistOverrides = json) }
        }
    }

    /**
     * Persist the selected model for a [ModelRole]. Empty string clears it.
     */
    fun setRoleModel(role: ModelRole, model: String) {
        viewModelScope.launch {
            val clean = model.trim()
            userPreferences.setRoleModel(role, clean.takeIf { it.isNotBlank() })
            _state.update { current ->
                current.copy(roleModels = current.roleModels + (role to clean))
            }
        }
    }

    /**
     * Toggle a tool's enabled state. Persists in [ToolPolicyStore].
     */
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = toolPolicyStore.getPolicy(toolName)
                ?: ToolPolicyDefaults.forTool(toolName, toolRegistry.get(toolName)?.risk ?: ToolRisk.READ_ONLY)
            toolPolicyStore.setPolicy(toolName, current.copy(enabled = enabled))
            _state.update { state ->
                state.copy(
                    toolPolicies = state.toolPolicies + (toolName to current.copy(enabled = enabled)),
                )
            }
        }
    }

    /**
     * Update the confirmation level for a tool.
     */
    fun setToolConfirmation(toolName: String, level: ConfirmationLevel) {
        viewModelScope.launch {
            val current = toolPolicyStore.getPolicy(toolName)
                ?: ToolPolicyDefaults.forTool(toolName, toolRegistry.get(toolName)?.risk ?: ToolRisk.READ_ONLY)
            toolPolicyStore.setPolicy(toolName, current.copy(confirmation = level))
            _state.update { state ->
                state.copy(
                    toolPolicies = state.toolPolicies + (toolName to current.copy(confirmation = level)),
                )
            }
        }
    }

    /** Connect to an MCP server and, on success, discover its tools. */
    fun testMcpConnection(draft: McpServerDraft) {
        if (draft.name.isBlank() || draft.url.isBlank()) return
        viewModelScope.launch {
            val prefixes = draft.allowedToolPrefixes.split(',').map { it.trim() }.filter { it.isNotBlank() }
            val denied = draft.deniedTools.split(',').map { it.trim() }.filter { it.isNotBlank() }
            val config = McpServerConfig(
                id = draft.name.lowercase().replace(Regex("[^a-z0-9]"), "_"),
                name = draft.name,
                url = draft.url,
                trustedLocal = draft.trustedLocal,
                allowedToolPrefixes = prefixes,
                deniedTools = denied,
                authToken = draft.authToken.ifBlank { null },
            )
            val health = mcpClientManager.connect(config, config.authToken)
            val tools = if (health.state == com.aura.mcp.McpConnectionState.CONNECTED) {
                mcpClientManager.listTools(config.id)
            } else emptyList()
            val updatedServers = _state.value.mcpServers + config
            _state.update { state ->
                state.copy(
                    mcpServers = updatedServers,
                    mcpDiscoveredTools = state.mcpDiscoveredTools + (config.id to tools),
                )
            }
            // Persist the server list and sync MCP tools into ToolRegistry
            persistMcpServers(updatedServers)
            mcpToolBridge.syncTools(updatedServers)
        }
    }

    /** Disconnect an MCP server by id. */
    fun disconnectMcpServer(serverId: kotlin.String) {
        viewModelScope.launch {
            mcpClientManager.disconnect(serverId)
            val updatedServers = _state.value.mcpServers.filter { it.id != serverId }
            _state.update { state ->
                state.copy(
                    mcpServers = updatedServers,
                    mcpDiscoveredTools = state.mcpDiscoveredTools - serverId,
                )
            }
            // Persist the updated server list and sync tools
            persistMcpServers(updatedServers)
            mcpToolBridge.syncTools(updatedServers)
        }
    }

    /** Serialize and persist the MCP server list to DataStore.
     * Auth tokens are stripped from the JSON and stored separately in
     * SecureDataStore (encrypted) to avoid plaintext secrets in preferences. */
    private suspend fun persistMcpServers(servers: List<McpServerConfig>) {
        // Store each auth token in SecureDataStore
        for (server in servers) {
            val token = server.authToken
            if (!token.isNullOrBlank()) {
                secureDataStore.putString("mcp_auth_${server.id}", token)
            } else {
                secureDataStore.removeString("mcp_auth_${server.id}")
            }
        }
        // Strip auth tokens from the JSON before writing to plain DataStore
        val jsonStr = if (servers.isEmpty()) "" else {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            kotlinx.serialization.json.JsonArray(
                servers.map { server ->
                    val sanitized = server.copy(authToken = null)
                    json.encodeToJsonElement(McpServerConfig.serializer(), sanitized)
                }
            ).toString()
        }
        userPreferences.setMcpServersJson(jsonStr)
    }

    /** Parse persisted MCP server JSON back to config objects.
     * Auth tokens are re-injected from SecureDataStore. */
    private suspend fun parseMcpServers(jsonStr: kotlin.String): List<McpServerConfig> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonArray
                ?: return emptyList()
            arr.mapNotNull { item ->
                val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val config = json.decodeFromJsonElement(McpServerConfig.serializer(), obj)
                // Re-inject auth token from SecureDataStore
                val token = runCatching { secureDataStore.getString("mcp_auth_${config.id}") }.getOrNull()
                if (token.isNullOrBlank()) config else config.copy(authToken = token)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun updateCredentialDraft(prefix: String, value: String) {
        require(prefix in ProviderKeys.PREFIXES) { "Unknown credential prefix: $prefix" }
        _state.update { current ->
            current.copy(
                keyDrafts = current.keyDrafts + (prefix to value),
                providerTests = current.providerTests - prefix,
                verifyResults = current.verifyResults - prefix,
            )
        }
    }

    private fun keyDraft(prefix: String): String = _state.value.keyDrafts[prefix].orEmpty()

    fun saveAndTestProvider(prefix: String) {
        if (_state.value.verifying != null) return
        val value = keyDraft(prefix).trim()
        updateProviderTest(prefix, ProviderTestPhase.Saving, "Saving securely…")
        viewModelScope.launch {
            try {
                providerKeys.set(prefix, value)
                if (providerKeys.credentialStates.value[prefix] == ProviderCredentialState.StorageError) {
                    updateProviderTest(prefix, ProviderTestPhase.Failed, "Secure storage failed")
                    return@launch
                }
                if (prefix in TOOL_CREDENTIAL_PREFIXES) {
                    updateProviderTest(
                        prefix,
                        ProviderTestPhase.Idle,
                        if (value.isBlank()) "Credential removed" else "Saved securely",
                    )
                    return@launch
                }
                if (value.isBlank()) {
                    modelCatalogRepository.refreshProvider(prefix, force = true)
                    updateProviderTest(prefix, ProviderTestPhase.Idle, "Credential removed")
                    return@launch
                }

                updateProviderTest(prefix, ProviderTestPhase.Testing, "Testing provider…")
                modelCatalogRepository.refreshProvider(prefix, force = true)
                val providerState = modelCatalogRepository.catalog.value.providers[prefix]
                val valid = providerState?.status == ProviderStatus.Ready &&
                    providerState.errorMessage == null &&
                    providerState.models.isNotEmpty()
                providerKeys.markValidation(prefix, valid)
                if (valid) {
                    updateProviderTest(
                        prefix,
                        ProviderTestPhase.Verified,
                        "Verified — ${providerState!!.models.size} models",
                        providerState.models.size,
                    )
                } else {
                    val message = providerState?.errorMessage
                        ?: providerState?.status?.name
                        ?: "Provider unavailable"
                    updateProviderTest(prefix, ProviderTestPhase.Failed, message)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                providerKeys.markValidation(prefix, false)
                updateProviderTest(
                    prefix,
                    ProviderTestPhase.Failed,
                    error.message?.take(100) ?: "Provider test failed",
                )
            } finally {
                _state.update {
                    it.copy(
                        verifying = null,
                        configuredProviders = configuredProviderLabels(),
                    )
                }
            }
        }
    }

    private fun updateProviderTest(
        prefix: String,
        phase: ProviderTestPhase,
        message: String,
        modelCount: Int = 0,
    ) {
        val legacy = when (phase) {
            ProviderTestPhase.Verified -> "✓ $message"
            ProviderTestPhase.Failed -> "✗ $message"
            else -> message
        }
        _state.update {
            it.copy(
                verifying = if (phase == ProviderTestPhase.Saving || phase == ProviderTestPhase.Testing) {
                    prefix
                } else null,
                providerTests = it.providerTests + (
                    prefix to ProviderTestResult(phase, message, modelCount)
                ),
                verifyResults = it.verifyResults + (prefix to legacy),
            )
        }
    }

    fun refreshModels() {
        if (_state.value.modelsLoading) return
        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            modelCatalogRepository.refresh(force = true)
        }
    }

    fun verifyKey(prefix: String) = saveAndTestProvider(prefix)

    /**
     * Persist the user's drafts for the custom endpoint and verify the
     * connection by hitting the live `/models` endpoint. The state is
     * written to [CustomEndpointState] (which is what the provider reads
     * from at chat time) and persisted to DataStore by the singleton.
     */
    fun saveAndTestCustomEndpoint() {
        if (_state.value.customTesting) return
        val url = _state.value.customBaseUrl.trim().trimEnd('/')
        val key = _state.value.customApiKey.trim()
        if (url.isBlank() || key.isBlank()) {
            _state.update { it.copy(customResult = "✗ Base URL and API key are required") }
            return
        }
        _state.update { it.copy(customTesting = true, customResult = "Testing…") }
        viewModelScope.launch {
            customEndpointState.setEndpoint(url, key)
            try {
                modelCatalogRepository.refreshProvider("custom", force = true)
                val providerState = modelCatalogRepository.catalog.value.providers["custom"]
                val valid = providerState?.status == ProviderStatus.Ready &&
                    providerState.errorMessage == null &&
                    providerState.models.isNotEmpty()
                if (valid) {
                    _state.update {
                        it.copy(
                            customTesting = false,
                            customResult = "✓ Verified — ${providerState!!.models.size} models",
                            customIsConfigured = true,
                        )
                    }
                } else {
                    val message = providerState?.errorMessage
                        ?: providerState?.status?.name
                        ?: "Provider unavailable"
                    _state.update {
                        it.copy(
                            customTesting = false,
                            customResult = "✗ $message",
                        )
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(customTesting = false) }
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        customTesting = false,
                        customResult = "✗ ${error.message?.take(80) ?: "Connection failed"}",
                    )
                }
            }
        }
    }

    fun updateCustomBaseUrl(value: String) {
        _state.update {
            it.copy(
                customBaseUrl = value,
                customResult = null,
                customIsConfigured = it.customIsConfigured && value.isNotBlank(),
            )
        }
    }

    fun updateCustomApiKey(value: String) {
        _state.update {
            it.copy(
                customApiKey = value,
                customResult = null,
                customIsConfigured = it.customIsConfigured && value.isNotBlank(),
            )
        }
    }

    fun clearCustomEndpoint() {
        viewModelScope.launch {
            customEndpointState.setEndpoint("", "", emptyList())
            _state.update {
                it.copy(
                    customBaseUrl = "",
                    customApiKey = "",
                    customIsConfigured = false,
                    customResult = null,
                )
            }
        }
    }

    fun updateSmtpHost(value: String) {
        _state.update { it.copy(smtpHost = value, smtpResult = null) }
    }

    fun updateSmtpPort(value: String) {
        _state.update { it.copy(smtpPort = value.toIntOrNull() ?: 587, smtpResult = null) }
    }

    fun updateSmtpUsername(value: String) {
        _state.update { it.copy(smtpUsername = value, smtpResult = null) }
    }

    fun updateSmtpPassword(value: kotlin.String) {
        _state.update { it.copy(smtpPassword = value, smtpResult = null) }
    }

    fun updateSmtpFrom(value: String) {
        _state.update { it.copy(smtpFrom = value, smtpResult = null) }
    }

    fun saveSmtpConfig() {
        if (_state.value.smtpTesting) return
        _state.update { it.copy(smtpTesting = true, smtpResult = "Saving…") }
        viewModelScope.launch {
            try {
                userPreferences.setSmtpConfig(
                    _state.value.smtpHost,
                    _state.value.smtpPort,
                    _state.value.smtpUsername,
                    _state.value.smtpPassword,
                    _state.value.smtpFrom,
                )
                _state.update {
                    it.copy(
                        smtpTesting = false,
                        smtpResult = "✓ SMTP saved",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(smtpTesting = false, smtpResult = "✗ ${e.message}") }
            }
        }
    }

    private fun applyCatalog(catalog: ModelCatalog) {
        val failures = catalog.providers.values
            .filter { provider ->
                provider.status !in setOf(
                    ProviderStatus.NotConfigured,
                    ProviderStatus.Loading,
                    ProviderStatus.Ready,
                )
            }
            .map { provider ->
                "${provider.providerPrefix}: ${provider.errorMessage ?: provider.status.name}"
            }
        _state.update {
            it.copy(
                configuredProviders = configuredProviderLabels(),
                availableModels = catalog.allModels.map { model -> model.id }.distinct().sorted(),
                modelsLoading = catalog.providers.values.any { provider ->
                    provider.status == ProviderStatus.Loading
                },
                modelsError = failures.firstOrNull(),
            )
        }
    }
}