package com.aura.ui.viewmodel
import androidx.compose.runtime.Immutable
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
import com.aura.agent.ToolExecutor
import com.aura.agent.ToolRegistry
import com.aura.tools.DelegateToAgentTool
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
import com.aura.agent.Citation
import com.aura.ui.util.toSummary
import com.aura.voice.TextToSpeech
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.aura.agent.StrategyBandit
import javax.inject.Inject

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
            }.onFailure { Log.w("ChatViewModel", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyList())
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
    // Scope and project are different columns on `preference_signals`, and this
    // passed the scope into the parameter that means project. The signal above
    // is written with the default `projectId = ""`, so `forProject("agent:x")`
    // matched nothing, `recomputeProfile` returned at its `signals.isEmpty()`
    // guard, and every thumbs-up or thumbs-down given inside an agent
    // conversation was a silent no-op — only reactions in the general chat ever
    // reached a profile.
    //
    // Recomputing the global profile is correct for both cases: these signals
    // are the user's taste, not a project's, and `global()` selects exactly the
    // `projectId = ''` rows this records. `agentScope` is still written on the
    // signal, so a future per-agent aggregation has the data it needs; there is
    // no scoped *writer* yet, which is why passing one here did nothing.
    tasteEngine.recomputeProfile("")
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

/** A question Aura is waiting to ask, reduced to what the card needs. */
data class OpenQuestionPrompt(val id: String, val question: String)

/**
 * Marked [Immutable] so Compose skips on `equals` instead of identity.
 *
 * Every one of these is republished as a fresh object on each change, so under strong
 * skipping an unstable state class meant a screen taking it recomposed on every publish
 * whether or not anything it read had changed. The promise holds: all properties are
 * `val`, and the collections are replaced through `copy()` — there is no `MutableList`
 * property anywhere in main sources and nothing mutates a state collection in place.
 *
 * It is a promise the compiler cannot check. A field that starts being mutated in place
 * will stop recomposing rather than fail to build.
 */
@Immutable
data class ChatUiState(
    val conversation: com.aura.agent.Conversation = com.aura.agent.Conversation(),
    val conversationLoading: Boolean = false,
    val streaming: Boolean = false,
    val streamingThinking: String = "",
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
    /** Name of the project this conversation is attributed to, or null. */
    val activeProject: String? = null,
    /** Active project names, most recently worked first, for the picker. */
    val availableProjects: List<String> = emptyList(),
    /** Agent ID for per-agent memory scoping. Derived from [activeAgent]. */
    val activeAgentId: String? = null,
    /**
     * The gate the agentic loop paused on (runtime permission, policy
     * confirmation, or remote-cost approval). The UI shows the dialog
     * matching [PendingGateUi.kind]; on Allow the send controller calls
     * the loop's resumeAfterGate, on Deny denyPendingGate.
     */
    val pendingGate: PendingGateUi? = null,
    /** URL to open in the in-app browser. Set when a tool returns [BROWSER:url]. */
    val pendingBrowserUrl: String? = null,
    /** Canvas content detected from the model response. Opens CanvasSheet. */
    val pendingCanvas: com.aura.ui.screens.canvas.CanvasContent? = null,
    /** Proactive in-chat message from AgentPresence. Shown as a special bubble. */
    val proactiveMessage: String? = null,
    /**
     * Idle-time prepared answer (ProAct pattern). Populated when the
     * daemon pre-researched a predicted question. ChatRoute renders a
     * suggestion chip; tapping it sends the predicted question so the
     * pre-researched answer is injected as fast-path context.
     */
    val preparedQuestion: String? = null,
    /**
     * Per-conversation set of REMOTE_COST tools the user has
     * approved. Passed into ToolContext so ToolExecutor lets
     * them through without re-prompting.
     */
    val approvedRemoteCostTools: Set<String> = emptySet(),
    /**
     * Per-conversation set of tools whose policy confirmation the user
     * granted. Mirror of [approvedRemoteCostTools] for the
     * ConfirmationLevel gate — passed into the loop so later sends in
     * this conversation carry the grant too.
     */
    val confirmedTools: Set<String> = emptySet(),
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
    /**
     * Something Aura wants to know, shown after it has finished answering.
     *
     * Null whenever there is nothing to ask, which is most of the time — the
     * scan runs nightly and there is never more than one open question.
     */
    val openQuestion: OpenQuestionPrompt? = null,
) {
    /**
     * The model this chat will actually use, resolved once for the header,
     * the send path and the "choose a model" banner.
     *
     * An open conversation carries the model it was created with, and that
     * wins over the global default — reopening a chat should not silently
     * move it to another model. [activeModel] is the fallback for a fresh
     * conversation that has not picked one yet.
     *
     * These three call sites used to disagree. The header already resolved
     * `conversationModel ?: activeModel`, while the banner and the send
     * path read `activeModel` alone. [sessionModelOverride] lives only in
     * memory, so after process death `activeModel` came back blank while
     * the conversation's own model was still in Room — and the header
     * displayed "Deepseek V4 Flash" directly above a banner insisting
     * "Choose a model before sending."
     */
    val effectiveModel: String
        get() = conversation.model?.takeIf(String::isNotBlank) ?: activeModel
}

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

/**
 * UI mirror of [AgentEvent.GateRequested] — the gate the agentic loop
 * paused on. [kind] drives which dialog ChatRoute shows; [permission]
 * is set for PERMISSION gates, [level] for CONFIRMATION gates.
 */
data class PendingGateUi(
    val toolName: String,
    val toolCallId: String,
    val args: String,
    val kind: MemoryAugmentedAgenticLoop.GateKind,
    val permission: String,
    val level: String,
    val rationale: String,
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
    private val idleTimePreparationEngine: com.aura.proactive.IdleTimePreparationEngine? = null,
    private val proactiveEventDao: com.aura.proactive.ProactiveEventDao? = null,
    private val curiosityStore: com.aura.curiosity.CuriosityStore? = null,
    private val situationReader: com.aura.situation.SituationReader? = null,
    /** Turn-level relevance signals for the harvested retrieval labels. */
    private val retrievalLabels: com.aura.memory.RetrievalLabelStore? = null,
    /** Projects this conversation can be attributed to. */
    private val projectStore: com.aura.projects.ProjectStore? = null,
    /**
     * The only state here that outlives the process.
     *
     * SavedStateHandle appeared nowhere in this app and rememberSaveable twice against 233
     * `remember`, so nothing survived Android killing a backgrounded app — which it does
     * for entirely routine reasons. Almost all of that is fine: a collapsed card reopening
     * collapsed is not worth a line of code.
     *
     * The draft is the exception. It is the one piece of state the *user* authored, and it
     * is not in Room precisely because it has not been sent. Look something up mid-message,
     * come back, and it was gone. Nullable so the many tests that construct this directly
     * do not all have to supply one.
     */
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle? = null,
    /**
     * Lets a message become a task instead of a turn.
     *
     * Optional and last, matching every other late arrival here: Hilt supplies it in
     * production and the many tests that construct this positionally do not need it.
     */
    private val agentRunStore: com.aura.agentrun.AgentRunStore? = null,
) : AndroidViewModel(application) {

    /**
     * Attribute this conversation to a project, or clear it.
     *
     * Writes three places, all of which have to agree or the ledger reads the
     * wrong conversation: the conversation's own tag (what the sweep and the
     * History filter read), the sticky preference (what the *next* conversation
     * inherits), and the UI state.
     *
     * The project is created if the name is new — `ProjectStore.create` is
     * idempotent on name, so picking an existing one from the list and typing
     * its name are the same operation.
     *
     * Never called from anywhere but the picker: attribution changing without a
     * tap is the failure this whole design avoids, since a wrong project writes
     * into the wrong ledger and nothing would look broken.
     */
    fun setActiveProject(name: String?) {
        viewModelScope.launch {
            val store = projectStore
            val resolved = if (name.isNullOrBlank()) {
                null
            } else {
                runCatching { store?.create(name) }
                    .onFailure { android.util.Log.w("ChatViewModel", "could not resolve project: ${it.message}", it) }
                    .getOrNull()
            }
            runCatching {
                conversationStore.setProject(_state.value.conversation.id, resolved?.name)
                userPreferences.setStickyProjectId(resolved?.name)
            }.onFailure { android.util.Log.w("ChatViewModel", "could not attribute conversation: ${it.message}", it) }
            _state.update { it.copy(activeProject = resolved?.name) }
            refreshProjects()
        }
    }

    /** Refresh the picker's list. Active projects only, most recent first. */
    internal fun refreshProjects() {
        viewModelScope.launch {
            val names = runCatching { projectStore?.active()?.map { it.name } }
                .onFailure { android.util.Log.w("ChatViewModel", "could not list projects: ${it.message}", it) }
                .getOrNull()
                .orEmpty()
            _state.update { it.copy(availableProjects = names) }
        }
    }

    /** The turn a signal is about, in the form the label rows are keyed by. */
    private fun turnProvenance(turnTimestamp: Long?) =
        com.aura.provenance.ConversationProvenance(
            _state.value.conversation.id,
            turnTimestamp ?: 0L,
        )

    private val _state = MutableStateFlow(ChatUiState())

    /** Questions the user said "not now" to, for this session only. */
    private val snoozedQuestionIds = mutableSetOf<String>()

    private val conversationController = ChatConversationController(
        _state = _state,
        conversationStore = conversationStore,
        knowledgeGraphRepository = knowledgeGraphRepository,
        scope = viewModelScope,
        cancelSend = ::cancel,
    )

    /**
     * Model catalog controller — owns model list refresh and
     * model selection state resolution. Extracted from ChatViewModel
     * to isolate catalog logic from the send pipeline.
     */
    private val modelController: ChatModelController? = modelCatalogRepository?.let {
        ChatModelController(_state, it)
    }

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

    /**
     * UI-interaction controller — owns permission/approval/dismissal
     * state mutations and canvas/proactive-message I/O.
     * Extracted from ChatViewModel to reduce the method count.
     */
    private val interactionController: ChatInteractionController by lazy {
        ChatInteractionController(
            state = _state,
            memoryStore = memoryStore,
            proactiveMessageStore = proactiveMessageStore,
            scope = viewModelScope,
            resumeGate = { sendController.resumeGate(viewModelScope) },
            denyGate = { sendController.denyGate() },
        )
    }


    private fun saveConversation() = conversationController.saveConversation()


    init {
        // Before anything that might overwrite it. A restored draft is only ever what the
        // user last typed; if the process was never killed the handle is empty and this
        // does nothing.
        savedStateHandle?.get<String>(DRAFT_KEY)
            ?.takeIf { it.isNotEmpty() }
            ?.let { restored -> _state.update { it.copy(draft = restored) } }
        textToSpeech.initialize()
        // Sticky project attribution.
        //
        // The conversation's own tag wins over the sticky preference: reopening
        // an old chat must show the project it was actually filed under, not the
        // one currently in use. The preference only supplies the answer for a
        // conversation that has none — which is what "sticky" means, and is the
        // only case where inheriting is not a guess about the past.
        viewModelScope.launch {
            runCatching {
                val tagged = conversationStore.projectOf(_state.value.conversation)
                val sticky = userPreferences.stickyProjectId.first()
                _state.update { it.copy(activeProject = tagged ?: sticky) }
            }.onFailure { Log.w(TAG, "sticky project resolve failed", it) }
        }
        refreshProjects()
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
        // Idle-time preparation (ProAct): if the daemon pre-researched a
        // predicted question, surface it as a suggestion chip. consumePrepared()
        // clears it once the user taps the chip (or it expires on next prepare).
        // Ask after answering, not before. The moment Aura has finished doing
        // what was asked of it is the only moment where a question of its own
        // is not an interruption — so this watches the streaming edge rather
        // than loading once, and every path out of a turn (send, retry, error)
        // goes through it.
        viewModelScope.launch {
            _state
                .map { it.streaming }
                .distinctUntilChanged()
                .collect { streaming -> if (!streaming) refreshOpenQuestion() }
        }
        viewModelScope.launch {
            runCatching {
                idleTimePreparationEngine?.prepared?.collect { prepared ->
                    if (prepared != null) {
                        _state.update {
                            it.copy(preparedQuestion = prepared.predictedQuestion)
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "Idle prep collect failed", it) }
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
        // The conversation loads asynchronously, after the catalog and the
        // default-model preference have already emitted. Its model is part
        // of ChatUiState.effectiveModel, so without this the banner kept
        // whatever verdict it reached before the conversation arrived —
        // "Choose a model before sending" sitting under a header that
        // named the model, on a chat that sent perfectly well.
        viewModelScope.launch {
            _state.map { it.conversation.model }
                .distinctUntilChanged()
                .collect { applyModelCatalog(modelCatalogRepository?.catalog?.value) }
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
        val controller = modelController
        if (controller == null) {
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
        controller.refreshModels()
    }

    private fun applyModelCatalog(catalog: ModelCatalog?) {
        modelController?.applyModelCatalog(catalog) ?: run {
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
            }
        }
    }

    fun setDraft(text: String) {
        _state.update { it.copy(draft = text) }
        // Mirrored rather than read back on every keystroke: the flow stays the source of
        // truth and this is only the copy the OS keeps. Written on clear too, or a sent
        // message reappears in the field after the app is killed and reopened.
        savedStateHandle?.set(DRAFT_KEY, text)
    }

    /**
     * Pick a model for this chat only; global default belongs to Settings.
     *
     * This has to write the conversation's own model, not just [activeModel].
     * Everything that matters — the header, the send path, the "choose a
     * model" banner — reads [ChatUiState.effectiveModel], which is
     * `conversation.model ?: activeModel`. A conversation acquires a model as
     * soon as it is first sent to, so from the second message onward that
     * fallback never fires: updating `activeModel` alone changed nothing
     * visible and nothing real. The picker would mark the new model
     * "current" while the header kept naming the old one and every message
     * still went to it — picking gpt-5.6-sol in a chat begun on DeepSeek
     * quietly kept using DeepSeek.
     *
     * The precedence itself is right: reopening an old chat should not drag
     * it onto whatever the global default has since become. But an explicit
     * pick is not the silent drift that rule exists to prevent.
     */
    fun setModel(model: String) {
        _state.update {
            it.copy(
                activeModel = model,
                sessionModelOverride = model,
                conversation = it.conversation.copy(model = model),
                modelSelection = ModelSelectionState.Ready(model, it.availableModels),
            )
        }
        // Persist now instead of waiting for the next send, so leaving the
        // chat straight after switching doesn't discard the choice.
        viewModelScope.launch {
            runCatching { conversationStore.save(_state.value.conversation) }
                .onFailure { android.util.Log.w("ChatViewModel", "could not persist model choice: ${it.message}", it) }
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
        // Marked, never graded down. An edit says the *question* was wrong, not
        // the memories; grading it as a miss would teach the eval that
        // correctly-retrieved memories for a badly-phrased question are
        // irrelevant.
        val editedTurn = conv.turns[turnIndex].timestamp
        viewModelScope.launch {
            runCatching { retrievalLabels?.markSupersededByEdit(turnProvenance(editedTurn)) }
                .onFailure { Log.w("ChatViewModel", "edit marker write failed: ${it.message}", it) }
        }
        // subList is exclusive, so this drops the turn being edited along with everything
        // after it — and then addUser puts the edited text back. Without that second half
        // the message went nowhere: runSend's contract for retryUserText is that the
        // caller has already rewound and left the user row in place, so it does not add
        // one. Neither side did, and editing the first message emptied the conversation.
        val truncated = conv.copy(turns = conv.turns.subList(0, turnIndex)).addUser(newText)
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

    /**
     * Resume a run paused on a gate (permission, confirmation, or
     * remote-cost approval). The loop records the grant per gate kind.
     */
    fun resumeGate() = sendController.resumeGate(viewModelScope)

    /** Deny the pending gate — drops the held tool without resuming. */
    fun denyGate() = sendController.denyGate()

    /**
     * User approved a REMOTE_COST tool. Resumes the paused run — the
     * loop adds the tool to the per-conversation approved set.
     */

    fun approveRemoteCost() = interactionController.approveRemoteCost()

    /**
     * User confirmed a confirmation-gated tool. Resumes the paused run —
     * the loop adds the tool to the per-conversation confirmed set.
     */

    fun confirmTool(toolName: String) = interactionController.confirmTool(toolName)

    /** User dismissed the approval/confirmation dialog. */

    fun dismissApproval() = interactionController.dismissApproval()

    /** Clear the pending browser URL (user closed the in-app browser). */
    fun dismissBrowser() = interactionController.dismissBrowser()

    /** Clear the pending canvas (user closed the canvas sheet). */
    fun dismissCanvas() = interactionController.dismissCanvas()

    /** Clear the proactive message from UI state. The store was already consumed in loadProactiveMessage(). */
    fun dismissProactiveMessage() = interactionController.dismissProactiveMessage()

    /** Load proactive message if one is waiting. Called on chat open. */
    fun loadProactiveMessage() = interactionController.loadProactiveMessage()

    /**
     * Consume the idle-time prepared answer (ProAct fast path). Sets the
     * draft to the predicted question and injects the pre-researched
     * answer as system-prompt context so the model can answer instantly
     * instead of re-researching. Clears the chip state.
     */
    fun sendPrepared() {
        val prepared = idleTimePreparationEngine?.consume() ?: return
        _state.update { st ->
            val withContext = st.conversation.copy(
                systemPrompt = st.conversation.systemPrompt +
                    "\n\n[Pre-researched context for the next question]:\n" + prepared.answer,
            )
            st.copy(
                conversation = withContext,
                draft = prepared.predictedQuestion,
                preparedQuestion = null,
            )
        }
        send()
    }

    /**
     * Load the question Aura is waiting to ask, if there is one.
     *
     * "Not now" holds for the rest of the session, in memory: a snooze that
     * survived a restart would need a column, and a question that reappears
     * tomorrow is the intended behaviour anyway. Only a permanent refusal is
     * written down.
     */
    private fun refreshOpenQuestion() {
        val store = curiosityStore ?: return
        if (snoozedQuestionIds.isNotEmpty() && _state.value.openQuestion != null) return
        viewModelScope.launch {
            // Aura's own question is the one interruption that serves Aura
            // rather than the user, so it is the first thing a bad moment
            // should cost. An unreadable situation still asks — the card is
            // in-app and dismissible, not a notification.
            val badMoment = runCatching { situationReader?.get()?.interruptible == false }
                .getOrDefault(false)
            val question = runCatching { store.current() }
                .onFailure { Log.w(TAG, "open-question read failed", it) }
                .getOrNull()
                ?.takeIf { it.id !in snoozedQuestionIds && !badMoment }
            _state.update {
                it.copy(openQuestion = question?.let { q -> OpenQuestionPrompt(q.id, q.question) })
            }
            question?.let { q -> runCatching { store.markAsked(q.id) } }
        }
    }

    /** The user answered Aura's question. The answer becomes a memory. */
    fun answerOpenQuestion(text: String) {
        val question = _state.value.openQuestion ?: return
        val store = curiosityStore ?: return
        _state.update { it.copy(openQuestion = null) }
        viewModelScope.launch {
            runCatching {
                store.answer(
                    question.id,
                    text,
                    provenance = com.aura.provenance.ConversationProvenance(
                        _state.value.conversation.id,
                        _state.value.conversation.turns.lastOrNull()?.timestamp ?: 0L,
                    ),
                )
            }.onFailure { Log.w(TAG, "answering failed", it) }
        }
    }

    /** Not now — hidden for this session, asked again later. */
    fun snoozeOpenQuestion() {
        _state.value.openQuestion?.let { snoozedQuestionIds += it.id }
        _state.update { it.copy(openQuestion = null) }
    }

    /** Never ask about this again. Permanent, and written down. */
    fun neverAskOpenQuestion() {
        val question = _state.value.openQuestion ?: return
        val store = curiosityStore ?: return
        _state.update { it.copy(openQuestion = null) }
        viewModelScope.launch {
            runCatching { store.dismiss(question.id) }
                .onFailure { Log.w(TAG, "dismissing failed", it) }
        }
    }

    /** Dismiss the idle-prep chip without sending. */
    fun dismissPrepared() {
        idleTimePreparationEngine?.consume()
        _state.update { it.copy(preparedQuestion = null) }
    }

    /** Save canvas content as a memory. */
    fun saveCanvasToMemory(content: String) = interactionController.saveCanvasToMemory(content)

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
        // Recorded as a turn-level signal, which deliberately does not set a
        // grade: a verdict on the answer spread across every memory recalled for
        // that turn grades them all alike, and nDCG cannot separate those.
        val signal = when (newReaction) {
            Reaction.Up -> com.aura.memory.RetrievalLabelStore.TurnSignal.THUMBS_UP
            Reaction.Down -> com.aura.memory.RetrievalLabelStore.TurnSignal.THUMBS_DOWN
            null -> null
        }
        if (signal != null) {
            viewModelScope.launch {
                runCatching { retrievalLabels?.recordTurnSignal(turnProvenance(turnTimestamp), signal) }
                    .onFailure { Log.w("ChatViewModel", "turn signal write failed: ${it.message}", it) }
                // The same verdict, to the arm that produced the answer. Until this existed
                // the bandit had already counted this turn a success at the moment the run
                // completed, whichever way the user then judged it.
                runCatching {
                    strategyBandit.resolvePending(
                        turnProvenance(turnTimestamp),
                        success = signal == com.aura.memory.RetrievalLabelStore.TurnSignal.THUMBS_UP,
                    )
                }.onFailure { Log.w("ChatViewModel", "bandit verdict failed: ${it.message}", it) }
            }
        }
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
        // Captured before prepareConversationForRetry, whose first act is to
        // null `recall` — after that call there is nothing left identifying the
        // turn whose retrieval the user is implicitly rejecting.
        val retriedTurn = _state.value.conversation.turns.lastOrNull()?.timestamp
        val retry = prepareConversationForRetry(_state.value.conversation) ?: return
        viewModelScope.launch {
            runCatching {
                retrievalLabels?.recordTurnSignal(
                    turnProvenance(retriedTurn),
                    com.aura.memory.RetrievalLabelStore.TurnSignal.REGENERATED,
                )
            }.onFailure { Log.w("ChatViewModel", "regenerate signal write failed: ${it.message}", it) }
            // Regenerating is a rejection of the answer, so it is a failure for the arm that
            // chose how to reason about it — not for the retrieval, which is graded
            // separately and deliberately not graded down by an edit.
            runCatching {
                strategyBandit.resolvePending(turnProvenance(retriedTurn), success = false)
            }.onFailure { Log.w("ChatViewModel", "bandit verdict failed: ${it.message}", it) }
        }
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

    fun cancel() {
        sendController.cancel()
    }

    fun onUserMessage(text: String) {
        _state.update { it.copy(draft = text) }
        send()
    }

    // ---- Consume-once nav-argument handling ----
    //
    // ChatRoute's LaunchedEffects re-fire whenever the back stack entry
    // recomposes with its retained nav arguments (e.g. navigating away
    // and back). These guards live in the ViewModel — which survives
    // exactly as long as the back stack entry — so a brief is sent at
    // most once per event id (no duplicate LLM call) and the initial
    // draft can never clobber text the user typed after it was applied.

    /** Brief event ids already auto-sent by this ViewModel instance. */
    private val consumedBriefEventIds = mutableSetOf<Long>()

    /** Whether the `draft` nav argument has already been applied. */
    private var initialDraftConsumed = false

    /**
     * Load a morning brief's body from the proactive-event store by id
     * and auto-send it as a user message — once. The id (not the full
     * text) is what travels through notification extras and nav-route
     * arguments; the body lives in Room.
     */
    fun sendMorningBrief(briefEventId: Long) {
        if (briefEventId <= 0L) return
        if (!consumedBriefEventIds.add(briefEventId)) return
        val dao = proactiveEventDao ?: return
        viewModelScope.launch {
            val body = runCatching { loadBriefBody(dao, briefEventId) }
                .onFailure { Log.w(TAG, "brief load failed: ${it.message}", it) }
                .getOrNull()
            if (!body.isNullOrBlank()) onUserMessage(body)
        }
    }

    private suspend fun loadBriefBody(
        dao: com.aura.proactive.ProactiveEventDao,
        briefEventId: Long,
    ): String? {
        val entity = dao.byId(briefEventId) ?: return null
        return when (entity.eventType) {
            "MorningBriefReady" -> entity.body.takeIf { it.isNotBlank() }
            "MorningBriefStructured" -> runCatching {
                json.decodeFromString(com.aura.proactive.BriefContext.serializer(), entity.body)
                    .toSummary()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /**
     * Apply the `draft` nav argument to the composer — once. Later
     * re-fires of the nav effect are no-ops so the user's typed text
     * survives back-navigation.
     */
    fun applyInitialDraft(draft: String) {
        if (initialDraftConsumed) return
        initialDraftConsumed = true
        if (draft.isNotBlank()) setDraft(draft)
    }

    fun send() {
        sendController.runSend(viewModelScope)
    }

    /**
     * Run the draft as a background task rather than as a turn in this conversation.
     *
     * The difference that matters is what survives closing the app. A turn lives in the
     * conversation and dies with the collector; a task is a row with a status, a goal and
     * its own steps, executed by a WorkManager job that finishes whether or not the app is
     * open, and reports when it does.
     *
     * Everything this needs already existed — createRun has always taken a goal, and
     * AgentRunsScreen has always been able to list, resume and cancel. What it never had
     * was a way for the user to make one.
     */
    fun runInBackground() {
        val store = agentRunStore ?: return
        val goal = _state.value.draft.trim().takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            val run = runCatching {
                store.createRun(
                    trigger = "user",
                    goalDescription = goal,
                    conversationId = _state.value.conversation.id,
                    modelId = _state.value.effectiveModel,
                )
            }.onFailure {
                Log.w("ChatViewModel", "could not create a background task: ${it.message}", it)
                setErrorWithAutoDismiss("Could not start that as a background task.")
            }.getOrNull() ?: return@launch

            // createRun writes the row as RUNNING, so a failure to enqueue would leave a
            // task that nothing will ever execute and that reads to the user as still
            // working — the same orphan state AgentTaskWorker guards against on a crash.
            // Finish it here rather than leaving it, and keep the draft so nothing the user
            // typed is lost to a WorkManager problem they cannot see.
            runCatching { com.aura.agentrun.AgentTaskService.enqueue(getApplication(), run.id) }
                .onFailure {
                    Log.w("ChatViewModel", "could not enqueue task ${run.id}: ${it.message}", it)
                    runCatching { store.finish(run.id, "FAILED", "the task could not be started") }
                    setErrorWithAutoDismiss("Could not start that as a background task.")
                    return@launch
                }

            // Only once it is genuinely running. Clearing earlier would lose what the user
            // typed whenever the launch failed.
            setDraft("")
        }
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

    private companion object {
        /** Where the unsent draft is mirrored so it can outlive the process. */
        const val DRAFT_KEY = "chat_draft"
    }
}
