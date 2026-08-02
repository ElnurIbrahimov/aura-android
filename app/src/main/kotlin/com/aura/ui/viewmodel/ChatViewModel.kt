package com.aura.ui.viewmodel
import android.util.Log

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

import com.aura.data.UserPreferences
import com.aura.agent.AgentEvent
import com.aura.agent.ConversationStore
import com.aura.agent.recentTopics
import com.aura.agent.MemoryAugmentedAgenticLoop
import com.aura.agent.Reaction
import com.aura.agent.ToolContext
import com.aura.agent.ToolExecutor
import com.aura.agent.ToolRegistry
import com.aura.tools.DelegateToAgentTool
import com.aura.agent.ToolResult
import com.aura.agent.toAuraError
import com.aura.core.error.AuraError
import com.aura.documents.DocumentTextExtractor
import com.aura.kg.KnowledgeGraphRepository
import com.aura.providers.ProviderKeys
import com.aura.providers.ProviderRegistry
import com.aura.providers.ProviderStatus
import com.aura.providers.ModelCatalog
import com.aura.providers.ModelCatalogRepository
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import com.aura.taste.TasteEngine
import com.aura.tools.Citation
import com.aura.voice.TextToSpeech
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.aura.agent.StrategyBandit
import javax.inject.Inject

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun formatToolResult(result: ToolResult): String = when (result) {
    is ToolResult.Ok -> result.output
    is ToolResult.Error -> "Error: ${result.message}"
    is ToolResult.NeedsPermission -> "Still needs permission: ${result.permission}"
    is ToolResult.NeedsApproval -> "Approval needed: ${result.rationale}"
}

    private fun extractCitations(toolName: String, result: String): List<Citation> {
    return when (toolName) {
        "deep_research" -> {
            runCatching {
                val obj = json.parseToJsonElement(result).jsonObject
                val arr = obj["citations"]?.jsonArray ?: return@runCatching emptyList<Citation>()
                arr.mapIndexed { idx, el ->
                    val map = el.jsonObject
                    Citation(
                        index = idx + 1,
                        title = map["title"]?.jsonPrimitive?.content ?: "Source",
                        url = map["url"]?.jsonPrimitive?.content ?: "",
                    )
                }
            }.getOrDefault(emptyList())
        }
        "brave_search", "tavily_search" -> {
            // Parse markdown lines: "- [title](url): snippet"
            val regex = """- \[([^\]]+)\]\(([^)]+)\):""".toRegex()
            regex.findAll(result).mapIndexed { idx, match ->
                Citation(index = idx + 1, title = match.groupValues[1], url = match.groupValues[2])
            }.toList()
        }
        "web_search" -> {
            // Parse numbered results: "1. Title\n   URL\n   snippet"
            val regex = """\d+\.\s+(.+?)\n\s+(https?://\S+)""".toRegex(RegexOption.DOT_MATCHES_ALL)
            regex.findAll(result).mapIndexed { idx, match ->
                Citation(index = idx + 1, title = match.groupValues[1].trim(), url = match.groupValues[2].trim())
            }.toList()
        }
        else -> emptyList()
    }
}

/**
 * Generate a short conversation title from the first user message.
 * Smarter than just first-6-words:
 * - strips conversational starters ("can you", "please", "i want to")
 * - uses up to the first question mark
 * - falls back to first 6 content words
 * - truncates to 50 chars
 * - capitalizes the first letter
 * No LLM call — deterministic and instant.
 */
internal fun generateConversationTitle(text: String): String {
    val raw = text.trim()
    if (raw.isEmpty()) return "New conversation"

    val firstSentence = raw.split(Regex("[.!?\\n]")).firstOrNull()?.trim().orEmpty()

    val starterPatterns = listOf(
        "can you", "could you", "would you", "will you",
        "please", "hey", "hi ", "hello", "yo ",
        "i want to", "i need to", "i'd like to", "i would like to",
        "help me", "i have a question", "i was wondering",
    )
    val lowered = firstSentence.lowercase()
    var cleaned = firstSentence
    for (starter in starterPatterns) {
        if (lowered.startsWith(starter)) {
            cleaned = firstSentence.substring(starter.length).trimStart(' ', ',', '.')
            break
        }
    }

    val words = cleaned
        .split(Regex("\\s+"))
        .filter { it.length > 1 || it.all(Char::isLetter) }
        .take(6)
    var title = words.joinToString(" ").trim().ifBlank { raw.take(50) }

    if (title.length > 50) {
        title = title.take(47) + "…"
    }
    if (title.isNotEmpty() && title[0].isLowerCase()) {
        title = title[0].uppercaseChar() + title.substring(1)
    }
    return title.ifBlank { "New conversation" }
}

/**
 * Record a taste signal from a user's thumbs-up/thumbs-down reaction
 * on a chat turn. Extracted as a file-level function so it can be
 * tested without constructing a full ChatViewModel.
 */
internal suspend fun recordTasteSignalFromReaction(
    tasteEngine: TasteEngine,
    turn: com.aura.agent.Turn,
    reaction: Reaction?,
    modelId: String,
    specialistName: String?,
    agentId: String? = null,
) {
    tasteEngine.recordSignal(
        signalType = "chat_reaction",
        category = "general",
        artifactId = turn.timestamp.toString(),
        attributes = mapOf(
            "reaction" to (reaction?.name ?: "cleared"),
            "modelId" to modelId,
            "specialist" to (specialistName ?: "none"),
            "length" to turn.assistant.orEmpty().length.toString(),
        ),
        weight = if (reaction == Reaction.Up) 1.0f else -1.0f,
        agentScope = if (agentId != null) "agent:$agentId" else "general",
    )
    tasteEngine.recomputeProfile(if (agentId != null) "agent:$agentId" else "")
}

sealed interface ModelSelectionState {
    data object Missing : ModelSelectionState
    data class Loading(val activeModel: String?, val models: List<String>) : ModelSelectionState
    data class Ready(
        val activeModel: String,
        val models: List<String>,
        val staleProviders: Set<String> = emptySet(),
    ) : ModelSelectionState
    data class Failed(
        val activeModel: String?,
        val models: List<String>,
        val message: String,
    ) : ModelSelectionState
}

internal fun resolveModelSelection(
    activeModel: String,
    catalog: ModelCatalog,
): ModelSelectionState {
    val active = activeModel.takeIf(String::isNotBlank) ?: return ModelSelectionState.Missing
    val models = catalog.allModels.map { it.id }.distinct().sorted()
    if (catalog.providers.values.any { it.status == ProviderStatus.Loading }) {
        return ModelSelectionState.Loading(active, models)
    }
    val staleProviders = catalog.providers.values
        .filter { it.status == ProviderStatus.Ready && it.errorMessage != null }
        .mapTo(mutableSetOf()) { it.providerPrefix }
    if (active in models) return ModelSelectionState.Ready(active, models, staleProviders)

    val failure = catalog.providers.values.firstOrNull {
        it.status !in setOf(
            ProviderStatus.NotConfigured,
            ProviderStatus.Loading,
            ProviderStatus.Ready,
        )
    }
    val message = when {
        models.isNotEmpty() -> "The selected model is no longer available. Choose another model."
        failure != null -> failure.errorMessage ?: failure.status.name
        else -> "No verified models are available. Save & Test a provider in Settings."
    }
    return ModelSelectionState.Failed(active, models, message)
}

data class ChatUiState(
    val conversation: com.aura.agent.Conversation = com.aura.agent.Conversation(),
    val conversationLoading: Boolean = false,
    val streaming: Boolean = false,
    val draft: String = "",
    val error: String? = null,
    val errorTyped: AuraError? = null,
    /** Model used by this chat; Settings supplies it until the user picks a session override. */
    val activeModel: String = "",
    /** Null means follow the global Settings default; non-null is chat-only. */
    val sessionModelOverride: String? = null,
    val availableModels: List<String> = emptyList(),
    /**
     * True while [refreshModels] is in flight. Drives a small
     * "Loading models…" indicator in the picker.
     */
    val modelsLoading: Boolean = false,
    /**
     * Last error from [refreshModels], if any. Picker shows
     * "Couldn't load models — tap to retry". Null on success.
     */
    val modelsError: String? = null,
    val modelSelection: ModelSelectionState = ModelSelectionState.Missing,
    val ttsEnabled: Boolean = true,
    /** Active agent selected for this chat, if any. */
    val activeAgent: com.aura.agent.AgentEntity? = null,
    /** Available agents from [AgentStore]. Populated on init. */
    val availableAgents: List<com.aura.agent.AgentEntity> = emptyList(),
    /** Agent ID for per-agent memory scoping. Derived from [activeAgent]. */
    val activeAgentId: String? = null,
    val pendingPermission: String? = null,
    val permissionRationale: String? = null,
    /** Tool name + args that the permission was requested for. Used to retry after grant. */
    val pendingToolRetry: Pair<String, String>? = null,
    /**
     * Tool name + approval kind + rationale when a tool needs user
     * approval/confirmation. The UI shows a dialog; approveRemoteCost()
     * adds REMOTE_COST tools to [approvedRemoteCostTools] and re-engages.
     */
    val pendingApproval: Triple<String, String, String>? = null,
    /** URL to open in the in-app browser. Set when a tool returns [BROWSER:url]. */
    val pendingBrowserUrl: String? = null,
    /** Canvas content detected from the model response. Opens CanvasSheet. */
    val pendingCanvas: com.aura.ui.screens.canvas.CanvasContent? = null,
    /** Proactive in-chat message from AgentPresence. Shown as a special bubble. */
    val proactiveMessage: String? = null,
    /**
     * Per-conversation set of REMOTE_COST tools the user has
     * approved. Passed into ToolContext so ToolExecutor lets
     * them through without re-prompting.
     */
    val approvedRemoteCostTools: Set<String> = emptySet(),
    val errorRetryable: Boolean = false,
    val kgNodeCount: Int = 0,
    /** True when user has toggled Deep Mode for the next turn. */
    val deepModeEnabled: Boolean = false,
    /** True when the current turn is running through MoA. */
    val deepModeActive: Boolean = false,
    /** Explicit model role used while Deep Mode is enabled. */
    val deepModeModel: String = "",
    /**
     * True when incognito mode is on. The agent still runs and
     * reads memory (so the user can ask questions about their
     * existing memories), but does not auto-store the user's
     * message or extract profile facts from the assistant's
     * response. Session-scoped — not persisted across app restarts.
     */
    val incognitoMode: Boolean = false,
    /**
     * Tool calls that have started (ToolCallStart) but whose
     * ToolResult has not yet been emitted by the agentic loop.
     * In-memory only; cleared when the next user message sends or
     * when the matching ToolResult lands. The completed entry moves
     * into `conversation.turns.last().toolTurns` once the loop
     * returns a result, so this list is just the "in-flight" view.
     */
    val inFlightToolCalls: List<InFlightToolCall> = emptyList(),
    /**
     * A bitmap that was just captured or picked but not yet sent
     * to vision. Cleared when the user picks a vision prompt chip
     * (Describe / Read text / Translate) or dismisses the row.
     * Lives in UI state — not persisted.
     */
    val pendingVisionBitmap: Bitmap? = null,
    /** Visible, dismissible notice when execution moved to another provider/model. */
    val providerWarning: String? = null,
    /**
     * Non-blocking warning shown when a conversation save fails.
     * De-duplicated: only the first failure per session is shown so
     * repeated autosave failures don't spam the UI.
     */
    val saveWarning: String? = null,
    /** False when device is offline — shows a banner above chat. */
    val isOnline: Boolean = true,
    /** Current TTS state — used to show "Stop reading" pill and highlight speaking turn. */
    val ttsState: com.aura.voice.TextToSpeech.State = com.aura.voice.TextToSpeech.State.Idle,
    /** Wall-clock duration of the most recent response. Shown in the response footer. */
    val lastResponseDurationMs: Long = 0L,
)

/**
 * A tool call that the agentic loop has started but not yet
 * finished. The companion [com.aura.agent.ToolTurn] (persisted on
 * the turn) carries the completed form once the loop emits a
 * `ToolResult` event.
 */
data class InFlightToolCall(
    val id: String,
    val name: String,
    val args: String,
    val startedAtMs: Long = System.currentTimeMillis(),
)

private const val TAG = "ChatViewModel"

@HiltViewModel
class ChatViewModel @Inject constructor(
    application: Application,
    private val loop: MemoryAugmentedAgenticLoop,
    private val providerKeys: ProviderKeys,
    private val providerRegistry: ProviderRegistry,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val delegateToAgentTool: com.aura.tools.DelegateToAgentTool,
    private val textToSpeech: TextToSpeech,
    private val userPreferences: UserPreferences,
    private val memoryStore: com.aura.memory.MemoryStore,
    private val conversationStore: ConversationStore,
    private val knowledgeGraphRepository: KnowledgeGraphRepository,
    private val crashLogger: com.aura.core.error.CrashLogger,
    private val documentTextExtractor: DocumentTextExtractor? = null,
    private val modelCatalogRepository: ModelCatalogRepository? = null,
    private val skillsStore: SkillsStore? = null,
    private val tasteEngine: TasteEngine,
    private val agentStore: com.aura.agent.AgentStore,
    private val strategyBandit: com.aura.agent.StrategyBandit,
    private val proactiveMessageStore: com.aura.proactive.ProactiveMessageStore? = null,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ChatUiState())

    private val conversationController = ChatConversationController(
        _state = _state,
        conversationStore = conversationStore,
        knowledgeGraphRepository = knowledgeGraphRepository,
        scope = viewModelScope,
        cancelSend = ::cancel,
    )

    /** Reactive list of installed skills, exposed for the composer attachment sheet. */
    val skills: StateFlow<List<Skill>> = skillsStore?.skills ?: MutableStateFlow(emptyList())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    @Volatile private var isolatedSessionRequested = false
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /**
     * Send pipeline controller. Owns the streaming runJob, the
     * consecutive-failures counter (for adaptive MoA escalation),
     * and the assistant-text buffer used for TTS on completion.
     * Extracted from this VM so the send logic lives in one place
     * and the VM is just a state holder + lifecycle owner.
     */
    private val sendController: ChatSendController by lazy {
        ChatSendController(
            application = application,
            state = _state,
            loop = loop,
            userPreferences = userPreferences,
            textToSpeech = textToSpeech,
            knowledgeGraphRepository = knowledgeGraphRepository,
            toolExecutor = toolExecutor,
            delegateToAgentTool = delegateToAgentTool,
            onSaveConversation = { saveConversation() },
            onKgNodeCountChanged = { refreshKgNodeCount() },
            onFirstConversationComplete = { onFirstConversationComplete() },
            extractCitations = ::extractCitations,
            setErrorWithAutoDismiss = ::setErrorWithAutoDismiss,
            generateTitle = ::generateTitle,
            strategyBandit = strategyBandit,
            onError = { msg -> _state.update { it.copy(error = com.aura.ui.components.friendlyErrorMessage(msg)) } },
            onRunComplete = { durationMs -> _state.update { it.copy(lastResponseDurationMs = durationMs) } },
            recentTopics = {
                // Only inject on new conversations — the KDoc says
                // "for new conversations" and injecting the current
                // conversation's own topics on every turn causes the
                // model to offer to "continue where the user left off"
                // mid-conversation.
                if (state.value.conversation.turns.isEmpty()) {
                    runCatching { conversationStore.recentTopics(5, state.value.conversation.id) }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                        .getOrDefault("")
                } else ""
            },
        )
    }

    /**
     * Media controller — owns vision (image), audio (transcription),
     * and document (PDF/text) handling. Extracted from ChatViewModel
     * to reduce its line count and isolate media I/O.
     */
    private val mediaController: ChatMediaController by lazy {
        ChatMediaController(
            application = application,
            state = _state,
            toolRegistry = toolRegistry,
            crashLogger = crashLogger,
            scope = viewModelScope,
            documentTextExtractor = documentTextExtractor,
            onSaveConversation = { saveConversation() },
            onError = { msg -> _state.update { it.copy(error = com.aura.ui.components.friendlyErrorMessage(msg)) } },
            onTriggerSend = { send() },
        )
    }

    private fun saveConversation() = conversationController.saveConversation()


    init {
        textToSpeech.initialize()
        // Mirror TTS state into UI state so the chat can show a
        // "Stop reading" pill and highlight the currently-speaking turn.
        // Use launchIn instead of collect { } so this coroutine doesn't
        // block — tests with mocked StateFlows would hang on .collect.
        viewModelScope.launch {
            runCatching {
                textToSpeech.state.collect { tts ->
                    _state.update { it.copy(ttsState = tts) }
                }
            }.onFailure { Log.w(TAG, "TTS state collect failed", it) }
        }
        // Observe network connectivity and update UI state.
        viewModelScope.launch {
            runCatching {
                val cm = application.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                if (cm != null) {
                    val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: android.net.Network) {
                            _state.update { it.copy(isOnline = true) }
                        }
                        override fun onLost(network: android.net.Network) {
                            _state.update { it.copy(isOnline = false) }
                        }
                    }
                    cm.registerDefaultNetworkCallback(callback)
                    networkCallback = callback
                    _state.update { it.copy(isOnline = cm.activeNetwork != null) }
                }
            }.onFailure { Log.w(TAG, "Connectivity callback registration failed", it) }
        }
        // Pre-load skills so the composer attachment sheet renders
        // the list on first launch instead of flashing empty.
        viewModelScope.launch { skillsStore?.awaitLoaded() }
        viewModelScope.launch {
            agentStore.all().collect { agents ->
                _state.update { it.copy(availableAgents = agents) }
            }
        }
        viewModelScope.launch {
            val recent = conversationStore.mostRecent()
            if (isolatedSessionRequested) return@launch
            if (recent != null) {
                _state.update { it.copy(conversation = recent) }
            } else {
                // First conversation ever — set a welcoming system prompt
                _state.update { it.copy(conversation = it.conversation.copy(
                    systemPrompt = "You are Aura, a personal AI assistant. This is your first conversation with a new user. Introduce yourself warmly — tell them what you can do: remember things, set reminders, search the web, manage tasks, and work through delegated hands. Be concise and direct. Ask what they need help with.",
                    title = "Welcome",
                )) }
            }
        }
        viewModelScope.launch {
            userPreferences.defaultModel.collect { model ->
                _state.update { current ->
                    if (current.sessionModelOverride == null) {
                        current.copy(activeModel = model.orEmpty())
                    } else current
                }
                applyModelCatalog(modelCatalogRepository?.catalog?.value)
            }
        }
        modelCatalogRepository?.let { repository ->
            viewModelScope.launch {
                repository.catalog.collect(::applyModelCatalog)
            }
        }
        viewModelScope.launch {
            userPreferences.deepModeModel.collect { model ->
                _state.update { it.copy(deepModeModel = model.orEmpty()) }
            }
        }
        // Restore persisted TTS preference so the user's mute/unmute
        // choice survives app restarts. Defaults to true (on).
        viewModelScope.launch {
            userPreferences.ttsEnabled.collect { enabled ->
                _state.update { it.copy(ttsEnabled = enabled) }
            }
        }
        // Restore persisted incognito default. The session toggle still
        // works on top of this — it's just the starting value.
        viewModelScope.launch {
            userPreferences.incognitoDefault.collect { default ->
                _state.update { it.copy(incognitoMode = default) }
            }
        }
    }

    /**
     * Persist the onboarding marker exactly once. This callback runs after
     * every successful reply, so conversation-count timing is not a valid
     * deduplication boundary; MemoryStore owns the durable exact-content gate.
     */
    private suspend fun onFirstConversationComplete() {
        if (_state.value.incognitoMode) return
        runCatching {
            memoryStore.storeIfAbsent(
                content = "This user started using Aura. They went through the onboarding.",
                source = "system",
                category = "episode",
                importance = 0.8f,
            )
        }.onFailure { Log.w(TAG, "Onboarding memory seed failed", it) }
    }

    override fun onCleared() {
        textToSpeech.shutdown()
        networkCallback?.let { cb ->
            runCatching {
                val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            }.onFailure { Log.w(TAG, "Connectivity callback unregistration failed", it) }
        }
        super.onCleared()
    }

    /**
     * Re-fetch the model list from every configured provider. Safe
     * to call repeatedly — concurrent calls are no-ops (we check
     * [ChatUiState.modelsLoading] first). Sets [ChatUiState.modelsLoading]
     * while in flight, surfaces the last error on failure so the
     * picker can show "tap to retry" instead of a silent empty list.
     *
     * This is the fix for the "I added an Ollama Cloud key and the
     * picker still shows nothing" bug. Triggered on:
     *   - init (when the chat first opens)
     *   - the picker opening (so a key added in Settings is picked up)
     *   - a manual retry tap from the picker
     */

    fun refreshModels() {
        val repository = modelCatalogRepository
        if (repository == null) {
            _state.update {
                it.copy(
                    modelSelection = ModelSelectionState.Failed(
                        activeModel = it.activeModel.takeIf(String::isNotBlank),
                        models = it.availableModels,
                        message = "Model catalog is unavailable.",
                    ),
                    modelsError = "Model catalog is unavailable.",
                )
            }
            return
        }
        val current = _state.value
        _state.update {
            it.copy(
                modelsLoading = true,
                modelsError = null,
                modelSelection = ModelSelectionState.Loading(
                    current.activeModel.takeIf(String::isNotBlank),
                    current.availableModels,
                ),
            )
        }
        repository.refresh(force = true)
    }

    private fun applyModelCatalog(catalog: ModelCatalog?) {
        val current = _state.value
        if (catalog == null) {
            val selection = if (current.activeModel.isBlank()) {
                ModelSelectionState.Missing
            } else {
                ModelSelectionState.Failed(
                    current.activeModel,
                    current.availableModels,
                    "Model catalog is unavailable.",
                )
            }
            _state.update { it.copy(modelSelection = selection) }
            return
        }

        val models = catalog.allModels.map { it.id }.distinct().sorted()
        val selection = resolveModelSelection(current.activeModel, catalog)
        _state.update {
            it.copy(
                availableModels = models,
                modelsLoading = selection is ModelSelectionState.Loading,
                modelsError = (selection as? ModelSelectionState.Failed)?.message,
                modelSelection = selection,
            )
        }
    }

    fun setDraft(text: String) {
        _state.update { it.copy(draft = text) }
    }

    /** Pick a model for this chat only; global default belongs to Settings. */

    fun setModel(model: String) {
        _state.update {
            it.copy(
                activeModel = model,
                sessionModelOverride = model,
                modelSelection = ModelSelectionState.Ready(model, it.availableModels),
            )
        }
    }

    /** Explicitly promote the current chat model to the default for future chats. */

    fun makeActiveModelDefault() {
        val model = _state.value.activeModel
        if (model.isBlank()) return
        viewModelScope.launch {
            userPreferences.setDefaultModel(model)
            _state.update { it.copy(sessionModelOverride = null) }
        }
    }

    fun setActiveAgent(agent: com.aura.agent.AgentEntity?) {
        _state.update { old ->
            val newModel = agent?.preferredModel?.takeIf { it.isNotBlank() }
                ?: old.activeModel
            val agentId = agent?.id
            val systemPrompt = if (agent != null && agent.identity.isNotBlank()) {
                "You are ${agent.name}. ${agent.identity.trim()}"
            } else (old.conversation.systemPrompt ?: "")
            old.copy(
                activeAgent = agent,
                activeAgentId = agentId,
                activeModel = newModel,
                conversation = old.conversation.copy(systemPrompt = systemPrompt),
            )
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        _state.update { it.copy(ttsEnabled = enabled) }
        if (!enabled) textToSpeech.stop()
        viewModelScope.launch { userPreferences.setTtsEnabled(enabled) }
    }

    fun toggleTts() {
        setTtsEnabled(!_state.value.ttsEnabled)
    }

    /** Stop the current TTS utterance. Called from the "Stop reading" pill. */

    fun stopTts() {
        textToSpeech.stop()
    }

    fun toggleDeepMode() {
        _state.update { it.copy(deepModeEnabled = !it.deepModeEnabled) }
    }

    /**
     * Flip incognito mode. When on, the next send does not write
     * to memory or extract profile facts. Toggling it mid-conversation
     * takes effect on the next user message — the in-flight turn
     * (if any) keeps the value it was started with.
     */

    fun toggleIncognito() {
        _state.update { it.copy(incognitoMode = !it.incognitoMode) }
    }

    /**
     * Compute the most recent assistant text from the current
     * conversation. Returns an empty string if the last turn is a
     * user message (or the conversation is empty). Used by the
     * 'Copy last response' button in the chat header.
     */

    fun lastAssistantText(): String {
        val conv = _state.value.conversation
        // Walk turns in reverse to find the most recent assistant
        // text. We don't just take the last turn because the model
        // may have returned an empty assistant text + a tool call
        // — we want the last turn that actually had assistant
        // content.
        for (i in conv.turns.indices.reversed()) {
            val assistant = conv.turns[i].assistant
            if (!assistant.isNullOrBlank()) return assistant
        }
        return ""
    }

    fun loadConversation(id: String) = conversationController.loadConversation(id)


    /**
     * Fork the current conversation from a specific turn index.
     * Creates a new conversation with turns up to [fromTurnIndex],
     * loads it, and saves. The original conversation is untouched.
     */

    fun forkConversation(fromTurnIndex: Int) = conversationController.forkConversation(fromTurnIndex)


    /**
     * Start a fresh session owned by a compact surface (widget, share sheet,
     * future quick actions) without allowing the asynchronous recent-chat load
     * to overwrite it. Execution still uses the full send controller and agent loop.
     */

    fun startIsolatedSession(
        systemPrompt: String,
        model: String? = null,
        title: String = "Quick Ask",
    ) {
        isolatedSessionRequested = true
        cancel()
        _state.update { old ->
            val selectedModel = model?.takeIf(String::isNotBlank) ?: old.activeModel
            old.copy(
                conversation = com.aura.agent.Conversation(
                    systemPrompt = systemPrompt,
                    title = title,
                ),
                streaming = false,
                draft = "",
                error = null,
                errorRetryable = false,
                errorTyped = null,
                activeModel = selectedModel,
                sessionModelOverride = model?.takeIf(String::isNotBlank),
                modelSelection = if (selectedModel.isNotBlank()) {
                    ModelSelectionState.Ready(selectedModel, old.availableModels)
                } else {
                    ModelSelectionState.Missing
                },
                ttsEnabled = false,
                activeAgent = null,
                activeAgentId = null,
                inFlightToolCalls = emptyList(),
            )
        }
    }

    /**
     * Start a fresh conversation. Clears the current conversation,
     * resets the draft, and creates a new empty one with the default
     * system prompt. The old conversation is NOT deleted — it stays
     * in History. Cancels any in-flight streaming first.
     */

    fun newConversation() {
        cancel()
        isolatedSessionRequested = false
        _state.update {
            it.copy(
                conversation = com.aura.agent.Conversation(
                    systemPrompt = "You are Aura, a personal AI assistant. Be concise and direct. Ask what they need help with.",
                    title = "New conversation",
                ),
                draft = "",
                error = null,
                errorRetryable = false,
                errorTyped = null,
                deepModeEnabled = false,
                activeAgent = null,
                activeAgentId = null,
                deepModeActive = false,
                inFlightToolCalls = emptyList(),
            )
        }
    }

    fun clearConversation() = conversationController.clearConversation()


    /**
     * Insert a council synthesis as an assistant-authored turn in the
     * current conversation. Used when the user taps "Send to chat" from
     * the agent council screen.
     */

    fun insertCouncilResult(text: String) {
        cancel()
        _state.update { old ->
            old.copy(
                conversation = old.conversation.addAssistant(text, old.activeAgentId),
            )
        }
        saveConversation()
    }

    fun exportConversation(): kotlin.String = conversationController.exportConversation()

    fun editAndResend(turnIndex: Int, newText: kotlin.String) {
        if (_state.value.streaming) return
        val conv = _state.value.conversation
        if (turnIndex < 0 || turnIndex >= conv.turns.size) return
        // Truncate to the turn being edited, replace the user message, resend
        val truncated = conv.copy(turns = conv.turns.subList(0, turnIndex))
        _state.update {
            it.copy(
                conversation = truncated,
                draft = "",
                error = null,
                errorRetryable = false,
                errorTyped = null,
            )
        }
        sendController.runSend(viewModelScope, retryUserText = newText)
    }

    fun togglePinTurn(turnIndex: Int) {
        val conv = _state.value.conversation
        if (turnIndex < 0 || turnIndex >= conv.turns.size) return
        viewModelScope.launch {
            conversationStore.toggleTurnPin(conv.id, turnIndex)
            // Update local state immediately
            val updatedTurns = conv.turns.toMutableList()
            updatedTurns[turnIndex] = updatedTurns[turnIndex].copy(pinned = !updatedTurns[turnIndex].pinned)
            _state.update { it.copy(conversation = conv.copy(turns = updatedTurns)) }
        }
    }

    /**
     * Delete the current conversation from the store and start a
     * fresh one. The deleted conversation is gone for good — no
     * undo. In incognito mode the conversation was never persisted
     * so we just reset the UI state.
     */

    fun deleteCurrentConversation() = conversationController.deleteCurrentConversation()

    fun dismissPermission() {
        _state.update { it.copy(pendingPermission = null, permissionRationale = null, pendingToolRetry = null) }
    }

    /**
     * User approved a REMOTE_COST tool. Add it to the per-conversation
     * approved set and re-engage the model so the tool re-executes
     * with the approved set in ToolContext.
     */

    fun approveRemoteCost() {
        val pending = _state.value.pendingApproval ?: return
        val toolName = pending.first
        _state.update {
            it.copy(
                pendingApproval = null,
                approvedRemoteCostTools = it.approvedRemoteCostTools + toolName,
            )
        }
        sendController.runSend(viewModelScope)
    }

    /**
     * User confirmed an IMPLICIT/BIOMETRIC tool. Dismiss the dialog and
     * re-engage so the tool executes with the user's confirmation in context.
     */

    fun confirmTool(toolName: String) {
        _state.update { it.copy(pendingApproval = null) }
        sendController.runSend(viewModelScope)
    }

    /** User dismissed the REMOTE_COST approval dialog. */

    fun dismissApproval() {
        _state.update { it.copy(pendingApproval = null) }
    }

    /** Clear the pending browser URL (user closed the in-app browser). */
    fun dismissBrowser() {
        _state.update { it.copy(pendingBrowserUrl = null) }
    }

    /** Clear the pending canvas (user closed the canvas sheet). */
    fun dismissCanvas() {
        _state.update { it.copy(pendingCanvas = null) }
    }

    /** Clear the proactive message from UI state. The store was already consumed in loadProactiveMessage(). */
    fun dismissProactiveMessage() {
        _state.update { it.copy(proactiveMessage = null) }
    }

    /** Load proactive message if one is waiting. Called on chat open. */
    fun loadProactiveMessage() {
        viewModelScope.launch {
            runCatching {
                val msg = proactiveMessageStore?.consumeMessage()
                if (!msg.isNullOrBlank()) {
                    _state.update { it.copy(proactiveMessage = msg) }
                }
            }.onFailure { android.util.Log.w("ChatViewModel", "proactive load: ${it.message}") }
        }
    }

    /** Save canvas content as a memory. */
    fun saveCanvasToMemory(content: String) {
        viewModelScope.launch {
            runCatching {
                memoryStore.store(content, source = "canvas", category = "canvas", importance = 0.7f)
            }.onFailure { android.util.Log.w("ChatViewModel", "canvas save failed: ${it.message}") }
        }
        dismissCanvas()
    }

    /**
     * Set or clear the user's thumbs-up/thumbs-down reaction on a
     * single assistant turn. Tapping the same reaction a second time
     * clears it (null → Up → null, or null → Down → null). Tapping the
     * opposite reaction switches it. No-op if [turnTimestamp] doesn't
     * match any current turn. The new conversation is persisted to
     * Room so the reaction survives reloads.
     */

    fun reactToTurn(turnTimestamp: Long, reaction: Reaction?) {
        val current = _state.value.conversation
        val index = current.turns.indexOfFirst { it.timestamp == turnTimestamp }
        if (index < 0) return
        val turn = current.turns[index]
        // Toggle: tapping the same reaction clears it
        val newReaction = if (reaction == turn.reaction) null else reaction
        val newTurns = current.turns.toMutableList().apply {
            this[index] = turn.copy(reaction = newReaction)
        }
        _state.update { it.copy(conversation = current.copy(turns = newTurns)) }
        saveConversation()
        recordTasteSignal(turnTimestamp, newReaction)
    }

    private fun recordTasteSignal(turnTimestamp: Long, reaction: Reaction?) {
        viewModelScope.launch {
            val turn = _state.value.conversation.turns.find { it.timestamp == turnTimestamp } ?: return@launch
            val modelId = _state.value.conversation.model?.ifBlank { _state.value.activeModel } ?: _state.value.activeModel
            recordTasteSignalFromReaction(
                tasteEngine = tasteEngine,
                turn = turn,
                reaction = reaction,
                modelId = modelId,
                specialistName = _state.value.activeAgent?.name,
                agentId = _state.value.activeAgentId,
            )
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null, errorRetryable = false, errorTyped = null) }
    }

    fun dismissProviderWarning() {
        _state.update { it.copy(providerWarning = null) }
    }

    fun dismissSaveWarning() {
        _state.update { it.copy(saveWarning = null) }
    }

    /**
     * Generate a short conversation title from the first user message.
     * Smarter than just first-6-words:
     * - strips conversational starters ("can you", "please", "i want to")
     * - uses up to the first question mark
     * - falls back to first 6 content words
     * - truncates to 50 chars
     * - capitalizes the first letter
     * No LLM call — deterministic and instant.
     */

    private fun generateTitle(text: String): String = generateConversationTitle(text)

    private fun refreshKgNodeCount() = conversationController.refreshKgNodeCount()

    private fun setErrorWithAutoDismiss(error: String, retryable: Boolean = false, typed: AuraError? = null) {
        val friendly = com.aura.ui.components.friendlyErrorMessage(error)
        _state.update { it.copy(error = friendly, errorRetryable = retryable, errorTyped = typed) }
        crashLogger.log(
            code = typed?.code ?: "error",
            message = error,
        )
        // Only auto-dismiss non-retryable errors. Retryable errors
        // must stay visible until the user retries or sends a new
        // message — auto-dismissing them takes the retry button away.
        if (!retryable) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(5_000L)
                // Compare against the friendly message actually stored,
                // not the raw technical string passed to this function.
                if (_state.value.error == friendly) {
                    _state.update { it.copy(error = null, errorRetryable = false, errorTyped = null) }
                }
            }
        }
    }

    /** Retry the last user turn without duplicating it in history. */

    fun retryLast() {
        if (_state.value.streaming) return
        val retry = prepareConversationForRetry(_state.value.conversation) ?: return
        _state.update {
            it.copy(
                conversation = retry.conversation,
                draft = "",
                error = null,
                errorRetryable = false,
                errorTyped = null,
            )
        }
        sendController.runSend(viewModelScope, retryUserText = retry.userText)
    }

    fun retryAfterPermission(@Suppress("UNUSED_PARAMETER") permission: String) {
        // Re-execute the failed tool directly with the original args, no model round-trip.
        val (toolName, args) = _state.value.pendingToolRetry ?: ("" to "")
        _state.update { it.copy(
            pendingPermission = null,
            permissionRationale = null,
            pendingToolRetry = null,
            streaming = true,
        ) }
        if (toolName.isBlank()) {
            _state.update { it.copy(streaming = false, error = "Lost tool call context") }
            return
        }
        // Cancel any in-flight agent stream so this retry isn't
        // racing with the loop. The original code stored both
        // the send loop and the retry in the same runJob field;
        // now they're separate but cancel() still does the right
        // thing via the controller.
        sendController.cancel()
        viewModelScope.launch {
            try {
            val ctx = ToolContext(
                conversationId = _state.value.conversation.id,
                memoryEnabled = !_state.value.incognitoMode,
                approvedRemoteCostTools = _state.value.approvedRemoteCostTools,
            )
                val result = toolExecutor.execute(toolName, args, ctx)
                val resultText = formatToolResult(result)
                // Add the result as a tool turn so the model sees it in the right format
                _state.update { old ->
                    val updated = old.conversation
                        .addToolCall("retry-$toolName", toolName, args)
                        .setToolResult("retry-$toolName", resultText)
                    old.copy(conversation = updated)
                }
                saveConversation()
                // Re-engage the model so it can process the tool result and
                // continue the conversation. Without this the user had to
                // manually type a message for the model to see the result.
                sendController.runSend(viewModelScope)
            } catch (e: kotlinx.coroutines.CancellationException) { /* cancelled */ }
            catch (e: Exception) { _state.update { it.copy(streaming = false, error = e.message ?: "unknown error") } }
        }
    }

    fun cancel() {
        sendController.cancel()
    }

    fun onUserMessage(text: String) {
        _state.update { it.copy(draft = text) }
        send()
    }

    fun send() {
        sendController.runSend(viewModelScope)
    }

    // ---- Media handling (delegated to ChatMediaController) ----

    fun onImageCaptured(bitmap: Bitmap, question: String = "Describe this image in detail") =
        mediaController.onImageCaptured(bitmap, question)

    fun runVisionPrompt(bitmap: Bitmap, question: String) =
        mediaController.runVisionPrompt(bitmap, question)

    fun dismissPendingVision() =
        mediaController.dismissPendingVision()

    fun onAudioPicked(uri: Uri) =
        mediaController.onAudioPicked(uri)

    fun onDocumentPicked(uri: Uri) =
        mediaController.onDocumentPicked(uri)
}
