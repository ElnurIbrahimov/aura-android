package com.aura.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.capabilities.CapabilityRouter
import com.aura.creative.CouncilResult
import com.aura.creative.CouncilRole
import com.aura.creative.CouncilSessionRequest
import com.aura.creative.CreativeCouncil
import com.aura.creative.CreativeEngine
import com.aura.creative.CreativeMode
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.CreativeArtifactStore
import com.aura.creative.CreativeBranchStore
import com.aura.creative.ProseCraftTools
import com.aura.creative.VoiceCalibration
import com.aura.creative.TensionAnalyzer
import com.aura.creative.CharacterProgressionTracker
import com.aura.creative.WorldBible
import com.aura.creative.longform.OutlineParser
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

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
data class CreativeStudioUiState(
    val projects: List<CreativeProject> = emptyList(),
    val selectedProject: CreativeProject? = null,
    val loading: Boolean = true,
    val generating: Boolean = false,
    val output: String = "",
    val error: String? = null,
    val message: String? = null,
    val createdProjectId: String? = null,
    val councilResult: CouncilResult? = null,
    val thinkingBudget: Int? = null,
    val thinkingEnabled: Boolean = true,
    val voiceProfile: String = "",
    val calibrating: Boolean = false,
    val tensionReport: String = "",
    val analyzingTension: Boolean = false,
    /**
     * What the last analysis did *relative to the draft before it*.
     *
     * Null when there is nothing to compare against — no artifact selected, no
     * parent revision, or the parent was never analysed. Deliberately null
     * rather than a zeroed diff: "nothing changed" and "no basis for
     * comparison" are different answers and a row of zeroes reads as the first.
     */
    val tensionDiff: com.aura.creative.TensionDiff? = null,
    val wordCount: Int = 0,
    /** Null when no long-form run exists for the selected project. */
    val longform: LongformRunUi? = null,
    /** True while the outline call is in flight. */
    val planningOutline: Boolean = false,
    /** Null until the project has a living world. */
    val livingWorld: LivingWorldUi? = null,
    /** The scene under the author's hands; null when the editor is closed. */
    val sceneEditor: SceneEditorUi? = null,
    /** Facts `SceneLedger` has recorded for the open project's active branch. */
    val canonFactCount: Int = 0,
    /** Contradictions the ledger flagged and nobody has judged yet. */
    val openConflicts: List<com.aura.creative.ContinuityIssueEntity> = emptyList(),
)

/**
 * A living world as the Living tab needs to see it.
 *
 * [currentTick] and [worldEpochMs] are handed over raw rather than pre-reduced
 * to "behind by N", because how far behind the world is changes with the wall
 * clock rather than with any state write. The screen derives it at composition,
 * which is why it stays right in a process where no worker has ever run.
 */
data class LivingWorldUi(
    val worldId: String,
    val currentTick: Long,
    val worldEpochMs: Long,
    val sessionTicksBurned: Long,
    /** Whether somebody is living in this world, for the way in to the play surface. */
    val seated: Boolean,
    val factions: List<LivingFactionUi>,
    val events: List<LivingEventUi>,
    val eventCount: Int,
    /** Non-null only while a worker is mid-slice on this world. */
    val live: LivingLiveUi? = null,
    /** Event id currently being narrated on demand. */
    val narrating: String = "",
    /** This timeline's name, and every timeline the project has. */
    val branchName: String = "main",
    val branches: List<WorldBranchUi> = emptyList(),
    /** Fork-at-past needs genesis; pre-v29 worlds have none and are told no. */
    val hasGenesis: Boolean = false,
    /** Rendered comparison against main; empty until asked for. */
    val divergence: List<String> = emptyList(),
    /** The drama filter: the most notable moments, loaded on toggle. */
    val notableMoments: List<LivingEventUi> = emptyList(),
    val showNotable: Boolean = false,
    /** What happened past the last tick the user saw; snapshot per tab open. */
    val sinceYouLeft: List<LivingEventUi> = emptyList(),
    val daysAway: Long = 0L,
)

data class WorldBranchUi(val branchId: String, val name: String, val selected: Boolean)

data class SceneEditorUi(
    val beatIndex: Int,
    val artifactId: String,
    val title: String,
    val text: String,
)

data class LivingLiveUi(val currentTick: Long, val targetTick: Long, val phase: String) {
    val remaining: Long get() = (targetTick - currentTick).coerceAtLeast(0L)
}

data class LivingFactionUi(
    val id: String,
    val name: String,
    val territoryMilli: Long,
    val grainMilli: Long,
    val coinMilli: Long,
    val mightMilli: Long,
    /** Whoever this faction currently resents most, already resolved to a name. */
    val resents: String,
)

data class LivingEventUi(
    val id: String,
    val tick: Long,
    val kind: String,
    val summary: String,
    val narration: String,
    val notability: Double,
)

private const val EVENT_PAGE = 200
private const val DIVERGENCE_PAGE = 400
private const val NOTABLE_PAGE = 12
private const val SINCE_YOU_LEFT_PAGE = 5
private const val TAG = "CreativeStudioVM"

/**
 * The `kind` long-form scenes are written under.
 *
 * Mirrors `LongformRunner.KIND_SCENE`, which is private to that class, and the
 * literal `'scene'` already hardcoded in `CreativeRevisionDao.searchScenes`.
 * Third copy of the same string, and the reason a manuscript export must agree
 * with the drafter about what a scene is.
 */
private const val SCENE_KIND = "scene"

/**
 * Project the stored world state into the handful of numbers the tab shows.
 *
 * Only factions get rows. Locations and characters are seeded and simulated but
 * have nothing to display until there is somewhere to display it, and a list of
 * names with no state attached is furniture rather than information.
 */
private fun com.aura.creative.livingworld.WorldState.toUi(
    world: com.aura.creative.livingworld.LivingWorldEntity,
    events: List<com.aura.creative.livingworld.LivingEventEntity>,
): LivingWorldUi {
    val names = entities.associateBy({ it.id }, { it.name })
    fun stock(entityId: String, key: String): Long =
        stocks.firstOrNull { it.entityId == entityId && it.key == key }?.amountMilli ?: 0L

    val factions = entities
        .filter { it.kind == com.aura.creative.livingworld.WorldSeeder.KIND_FACTION && it.diedAtTick == 0L }
        .map { faction ->
            val worst = relations
                .filter { it.fromId == faction.id && it.kind == com.aura.creative.livingworld.WorldSeeder.REL_GRIEVANCE }
                .maxByOrNull { it.magnitudeMilli }
            LivingFactionUi(
                id = faction.id,
                name = faction.name,
                territoryMilli = stock(faction.id, com.aura.creative.livingworld.WorldSeeder.STOCK_TERRITORY),
                grainMilli = stock(faction.id, com.aura.creative.livingworld.WorldSeeder.STOCK_GRAIN),
                coinMilli = stock(faction.id, com.aura.creative.livingworld.WorldSeeder.STOCK_COIN),
                mightMilli = stock(faction.id, com.aura.creative.livingworld.WorldEngine.STOCK_MIGHT),
                resents = worst?.let { names[it.toId] }.orEmpty(),
            )
        }
        .sortedByDescending { it.territoryMilli }

    return LivingWorldUi(
        worldId = world.id,
        currentTick = world.currentTick,
        worldEpochMs = world.worldEpochMs,
        sessionTicksBurned = world.sessionTicksBurned,
        seated = world.playerCharacterId.isNotBlank() && world.playerFactionId.isNotBlank(),
        factions = factions,
        events = events.map {
            LivingEventUi(
                id = it.id,
                tick = it.tickIndex,
                kind = it.kind,
                summary = it.summary,
                narration = it.narration,
                notability = it.notability,
            )
        },
        eventCount = events.size,
    )
}

/**
 * A long-form run as the Manuscript tab needs to see it.
 *
 * Joined from three sources: the job row (durable, and possibly written by a
 * worker in a previous process lifetime), the project's outline (which beats are
 * drafted), and the in-memory progress bus (the scene streaming right now).
 * Nothing is polled — the first two are Room-backed Flows, which is also why
 * this reflects work done while the screen was closed.
 */
data class LongformRunUi(
    val jobId: String,
    val status: String,
    val beats: List<BeatProgressUi> = emptyList(),
    val currentIndex: Int = -1,
    val liveText: String = "",
    val error: String? = null,
) {
    val totalScenes: Int get() = beats.size
    val draftedScenes: Int get() = beats.count { it.drafted }
    val wordsWritten: Int get() = beats.sumOf { it.wordCount }
    val active: Boolean get() = status in ACTIVE_STATUSES

    private companion object {
        val ACTIVE_STATUSES = setOf("queued", "running", "cancelling")
    }
}

data class BeatProgressUi(
    val title: String,
    val drafted: Boolean,
    val wordCount: Int = 0,
)

@HiltViewModel
class CreativeStudioViewModel @Inject constructor(
    private val store: CreativeProjectStore,
    private val engine: CreativeEngine,
    private val council: CreativeCouncil,
    private val providerRegistry: ProviderRegistry,
    private val capabilityRouter: CapabilityRouter,
    private val modelRoleRouter: com.aura.providers.ModelRoleRouter,
    private val proseCraftTools: ProseCraftTools,
    private val voiceCalibration: VoiceCalibration,
    private val tensionAnalyzer: TensionAnalyzer,
    private val progressionTracker: CharacterProgressionTracker,
    private val artifactStore: CreativeArtifactStore,
    private val branchStore: CreativeBranchStore,
    private val brain: com.aura.agent.Brain,
    private val longformRunStore: com.aura.creative.longform.LongformRunStore,
    private val longformProgressBus: com.aura.creative.longform.LongformProgressBus,
    private val livingWorldStore: com.aura.creative.livingworld.LivingWorldStore,
    private val worldSeeder: com.aura.creative.livingworld.WorldSeeder,
    private val worldTickBus: com.aura.creative.livingworld.WorldTickBus,
    private val worldNarrator: com.aura.creative.livingworld.WorldNarrator,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    // Appended, not inserted: CreativeStudioViewModelTest and
    // LongformPlanningTest construct this positionally with every argument,
    // so a parameter added mid-list is a compile break for reasons unrelated
    // to what those tests check. Same rule ProactiveBootstrap records.
    private val creativeAnalysisStore: com.aura.creative.CreativeAnalysisStore,
    private val canonFactDao: com.aura.creative.CanonFactDao,
    private val continuityIssueDao: com.aura.creative.ContinuityIssueDao,
    // Appended, not inserted — and nullable: tests build this by name.
    private val userPreferences: com.aura.data.UserPreferences? = null,
    private val sceneEditSignals: com.aura.creative.SceneEditSignals? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(CreativeStudioUiState())
    val state: StateFlow<CreativeStudioUiState> = _state.asStateFlow()
    private var generationJob: Job? = null
    private var longformJob: Job? = null
    // Declared here, above `init`, not further down beside `livingWorldJob` —
    // see commit 719ae507 (SettingsViewModel: two StateFlows declared 570
    // lines below `init` were still null when a coroutine launched in `init`
    // assigned to them, because Kotlin runs property initialisers and init
    // blocks in declaration order). This is not just the house rule applied
    // early: observeCanon is called both from loadProject and from init's
    // own store.observeAll() fallback below, so a coroutine launched in init
    // really can assign to this field before construction finishes. Declared
    // after init, the field's own `= null` initialiser would run after that
    // assignment and silently wipe it back to null.
    private var canonJob: Job? = null

    init {
        // Repair before the first list renders. `creative_artifacts` was written
        // with INSERT OR REPLACE while `creative_revisions` cascades off it, so
        // every draft deleted that artifact's revision history — including the
        // draft being written — and left `currentRevisionId` pointing at a row
        // that no longer existed. The DAO no longer cascades, but artifacts from
        // that period still carry the broken pointer, and a broken pointer reads
        // as an artifact whose content will not open.
        //
        // Here rather than in ProactiveBootstrap on purpose: this is where the
        // damage is visible, it costs one query per artifact and only on the
        // Creative screen, and adding a constructor argument to
        // ProactiveBootstrap would break eleven positional test call sites for
        // a repair that has nothing to do with proactive work. It is idempotent
        // — an artifact whose pointer resolves is skipped — so running it on
        // every open is free after the first.
        viewModelScope.launch {
            runCatching { artifactStore.repairDanglingRevisionPointers() }
                .onSuccess { report ->
                    if (report.touched > 0) {
                        android.util.Log.i(
                            "CreativeStudio",
                            "repaired ${report.repointed} artifact pointer(s); " +
                                "${report.orphaned} had no surviving revision",
                        )
                    }
                }
                .onFailure { android.util.Log.w("CreativeStudio", "artifact repair failed: ${it.message}", it) }
        }
        viewModelScope.launch {
            store.observeAll().collect { projects ->
                val selectedId = _state.value.selectedProject?.id
                val resolved = selectedId?.let { id -> projects.find { it.id == id } }
                    ?: _state.value.selectedProject
                _state.update { current ->
                    current.copy(
                        projects = projects,
                        selectedProject = resolved,
                        loading = false,
                    )
                }
                // Re-point the long-form observer whenever the selected project
                // changes. Without this the Manuscript tab would keep showing
                // the previous project's run.
                resolved?.id?.let { id ->
                    if (observedProjectId != id) {
                        observedProjectId = id
                        observeLongform(id)
                        observeLivingWorld(id)
                        observeCanon(id)
                    }
                }
            }
        }
    }

    private var observedProjectId: String? = null

    fun createProject(
        name: String,
        description: String,
        genre: String,
        tone: String,
        templateId: String,
    ) {
        viewModelScope.launch {
            runCatching { store.create(name, description, genre, tone, templateId) }
                .onSuccess { project ->
                    _state.update { it.copy(createdProjectId = project.id, selectedProject = project, error = null) }
                }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not create project.") } }
        }
    }

    fun consumeCreatedProject() {
        _state.update { it.copy(createdProjectId = null) }
    }

    fun loadProject(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val project = store.get(id)
            _state.update {
                it.copy(
                    selectedProject = project,
                    loading = false,
                    error = if (project == null) "Creative project not found." else null,
                )
            }
            // The main entry path from navigation, and where an in-flight run
            // started in a previous session becomes visible again.
            if (project != null && observedProjectId != id) {
                observedProjectId = id
                observeLongform(id)
                observeLivingWorld(id)
                observeCanon(id)
            }
        }
    }

    fun saveMetadata(
        name: String,
        description: String,
        genre: String,
        tone: String,
        templateId: String,
    ) {
        val id = _state.value.selectedProject?.id ?: return
        viewModelScope.launch {
            runCatching { store.updateProject(id, name, description, genre, tone, templateId) }
                .onSuccess { project ->
                    _state.update { it.copy(selectedProject = project, message = "Project details saved.", error = null) }
                }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not save project.") } }
        }
    }

    fun saveWorld(world: WorldBible, message: String = "World bible saved.") {
        val id = _state.value.selectedProject?.id ?: return
        viewModelScope.launch {
            runCatching { store.updateWorld(id, world) }
                .onSuccess { project -> _state.update { it.copy(selectedProject = project, message = message, error = null) } }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not save world bible.") } }
        }
    }

    // ---------------------------------------------------------------- long-form

    /**
     * Turn a brief into a machine-readable outline.
     *
     * Separate from drafting, and by default the user approves the beats before
     * anything is written: the outline is one cheap call, drafting is a dozen
     * expensive ones, and a bad outline wastes all of them. The beats land in
     * `WorldBible.outline`, so they are editable in the World tab like any other
     * canon.
     *
     * A retry is built in because models answer in prose when asked for a
     * format. If the second attempt still parses to fewer than
     * [OutlineParser.MIN_BEATS], the raw text is surfaced rather than a run being
     * started against an empty plan — a run that writes nothing and reports
     * success is the failure worth designing against.
     */
    fun planOutline(brief: String) {
        val project = _state.value.selectedProject ?: return
        if (brief.isBlank()) return
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(planningOutline = true, error = null, message = null, output = "") }
            val beats = runCatching {
                var parsed = outlineAttempt(project.id, brief, OutlineParser.FORMAT_INSTRUCTION)
                if (parsed.size < OutlineParser.MIN_BEATS) {
                    parsed = outlineAttempt(project.id, brief, OutlineParser.RETRY_INSTRUCTION)
                }
                parsed
            }.onFailure { error ->
                _state.update { it.copy(planningOutline = false, error = error.message ?: "Could not plan the outline.") }
            }.getOrNull() ?: return@launch

            if (beats.size < OutlineParser.MIN_BEATS) {
                _state.update {
                    it.copy(
                        planningOutline = false,
                        error = "The model did not return a usable outline. Its reply is above — you can add beats by hand in the World tab.",
                    )
                }
                return@launch
            }
            val updated = runCatching { store.updateWorld(project.id, project.world.copy(outline = beats)) }
                .onFailure { error -> _state.update { it.copy(planningOutline = false, error = error.message) } }
                .getOrNull()
            _state.update {
                it.copy(
                    planningOutline = false,
                    selectedProject = updated ?: it.selectedProject,
                    message = "Outline planned — ${beats.size} beats. Review them, then start drafting.",
                )
            }
        }
    }

    private suspend fun outlineAttempt(projectId: String, brief: String, instruction: String): List<com.aura.creative.StoryBeat> {
        val raw = StringBuilder()
        engine.generate(projectId, CreativeMode.OUTLINE, "$brief\n\n$instruction", thinkingBudget = 0)
            .collect { chunk ->
                raw.append(chunk)
                _state.update { it.copy(output = raw.toString()) }
            }
        return OutlineParser.parse(raw.toString())
    }

    /**
     * Start drafting every planned beat in the background.
     *
     * Enqueues a worker rather than running in `viewModelScope`: a chapter set is
     * tens of minutes of model calls, and a ViewModel scope dies with the screen.
     */
    fun startDrafting() {
        val project = _state.value.selectedProject ?: return
        if (project.world.outline.isEmpty()) {
            _state.update { it.copy(error = "Plan an outline before drafting.") }
            return
        }
        viewModelScope.launch {
            runCatching {
                val branchId = branchStore.createMainBranch(project.id).id
                val jobId = longformRunStore.create(project.id, branchId, brief = project.description)
                com.aura.creative.longform.LongformRunService.enqueue(appContext, jobId)
                jobId
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Could not start drafting.") }
            }.onSuccess {
                _state.update { it.copy(message = "Drafting started. You can leave this screen.", error = null) }
            }
        }
    }

    /**
     * Ask the run to stop.
     *
     * The Room write happens first and the WorkManager cancel second — see
     * [com.aura.creative.longform.LongformRunService.cancel]. Cancelling only
     * through WorkManager races the worker's own re-enqueue.
     */
    fun cancelDrafting() {
        val jobId = _state.value.longform?.jobId ?: return
        viewModelScope.launch {
            runCatching {
                longformRunStore.markCancelling(jobId)
                com.aura.creative.longform.LongformRunService.cancel(appContext, jobId)
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Could not stop drafting.") }
            }
        }
    }

    /**
     * Watch the most recent run for [projectId].
     *
     * Joins the durable job row with the project's outline and the live scene
     * stream. Collecting the job Flow is what makes progress made while the
     * screen was closed — or in a previous process — appear immediately on
     * return, with no polling.
     */
    private fun observeLongform(projectId: String) {
        longformJob?.cancel()
        longformJob = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                longformRunStore.observeForProject(projectId),
                longformProgressBus.live,
            ) { jobs, live -> jobs.firstOrNull() to live }
                .collect { (job, live) ->
                    if (job == null) {
                        _state.update { it.copy(longform = null) }
                        return@collect
                    }
                    val beats = _state.value.selectedProject?.world?.outline.orEmpty()
                    val progress = beats.map { beat ->
                        BeatProgressUi(
                            title = beat.title,
                            drafted = beat.status == "drafted",
                            wordCount = 0,
                        )
                    }
                    _state.update {
                        it.copy(
                            longform = LongformRunUi(
                                jobId = job.id,
                                status = job.status,
                                beats = progress,
                                currentIndex = live?.takeIf { l -> l.jobId == job.id }?.beatIndex ?: -1,
                                liveText = live?.takeIf { l -> l.jobId == job.id }?.text.orEmpty(),
                                error = job.errorMessage.takeIf { m -> m.isNotBlank() },
                            ),
                        )
                    }
                }
        }
    }

    /**
     * Canon and its open disagreements for [projectId].
     *
     * `observeOpen` is already a Flow, so a conflict flagged by a background
     * drafting run appears without the screen polling or being reopened.
     */
    private fun observeCanon(projectId: String) {
        canonJob?.cancel()
        canonJob = viewModelScope.launch {
            // Clear first. Branch resolution below is real DB I/O and can fail, and
            // an early return would otherwise leave the previous project's counts on
            // screen under the new project's name. observeLongform and
            // observeLivingWorld both reset for the same reason.
            _state.update { it.copy(canonFactCount = 0, openConflicts = emptyList()) }
            val branchId = runCatching { branchStore.createMainBranch(projectId).id }
                .onFailure { android.util.Log.w("CreativeStudio", "branch resolve failed: ${it.message}", it) }
                .getOrNull() ?: return@launch
            val facts = runCatching { canonFactDao.activeForBranch(projectId, branchId) }
                .onFailure { android.util.Log.w("CreativeStudio", "canon read failed: ${it.message}", it) }
                .getOrDefault(emptyList())
            _state.update { it.copy(canonFactCount = facts.size) }
            continuityIssueDao.observeOpen(projectId, branchId).collect { issues ->
                _state.update { it.copy(openConflicts = issues) }
            }
        }
    }

    /**
     * Mark a flagged contradiction as deliberate.
     *
     * `intentional_exception` rather than `dismissed`: the schema distinguishes
     * them, and "the author meant this" is a different fact from "the author is
     * not interested", which matters the next time the same pair is compared.
     */
    fun dismissConflict(issueId: String) {
        viewModelScope.launch {
            runCatching {
                continuityIssueDao.resolve(issueId, "intentional_exception", System.currentTimeMillis(), "user")
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Could not dismiss the flag.") }
            }
        }
    }

    private var livingWorldJob: Job? = null

    /**
     * Watch the project's living world.
     *
     * Both halves are Room-backed, so ticks committed by a worker while this
     * screen was closed — or in a previous process — show up on return with no
     * polling and no refresh action.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeLivingWorld(projectId: String) {
        livingWorldJob?.cancel()
        livingWorldJob = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                livingWorldStore.observeAllForProject(projectId),
                selectedWorldBranch,
            ) { worlds, selected ->
                worlds to (worlds.firstOrNull { it.branchId == selected } ?: worlds.firstOrNull())
            }
                .flatMapLatest { (worlds, world) ->
                    if (world == null) {
                        kotlinx.coroutines.flow.flowOf(LivingSnapshot(null, emptyList(), null))
                    } else {
                        // Durable half and live half, joined here. Room carries
                        // what happened — including ticks committed while this
                        // screen was closed — and the bus carries what a worker
                        // is doing right now. Deep, not shallow: a fork's page
                        // starts with its inheritance.
                        kotlinx.coroutines.flow.combine(
                            livingWorldStore.observeEventsDeep(world, EVENT_PAGE),
                            worldTickBus.live(world.id),
                        ) { events, live -> LivingSnapshot(world, events, live, worlds) }
                    }
                }
                .collect { snapshot ->
                    val world = snapshot.world
                    if (world == null) {
                        _state.update { it.copy(livingWorld = null) }
                        return@collect
                    }
                    latestWorlds = snapshot.all
                    val branchNames = runCatching {
                        branchStore.forProject(world.projectId).associateBy({ it.id }, { it.name })
                    }.getOrDefault(emptyMap())
                    val previous = _state.value.livingWorld
                    val worldState = livingWorldStore.decode(world.stateJson)
                    val ui = worldState.toUi(world, snapshot.events).copy(
                        live = snapshot.live?.let {
                            LivingLiveUi(it.currentTick, it.targetTick, it.phase)
                        },
                        narrating = previous?.narrating.orEmpty(),
                        branchName = branchNames[world.branchId] ?: "main",
                        branches = snapshot.all.map { entry ->
                            WorldBranchUi(
                                branchId = entry.branchId,
                                name = branchNames[entry.branchId] ?: "main",
                                selected = entry.id == world.id,
                            )
                        },
                        hasGenesis = world.genesisJson.isNotBlank(),
                        divergence = if (previous?.worldId == world.id) previous.divergence else emptyList(),
                        notableMoments = if (previous?.worldId == world.id) previous.notableMoments else emptyList(),
                        showNotable = if (previous?.worldId == world.id) previous.showNotable else false,
                        sinceYouLeft = if (previous?.worldId == world.id) previous.sinceYouLeft else emptyList(),
                        daysAway = if (previous?.worldId == world.id) previous.daysAway else 0L,
                    )
                    _state.update { it.copy(livingWorld = ui) }
                }
        }
    }

    /**
     * Seed a living world from the project's world bible and start it ticking.
     *
     * The bible carries no quantities of any kind, so [setup] supplies the
     * starting numbers. Defaults are used when the caller does not override
     * them, but they are the author's to change — a world whose opening
     * position nobody chose is not one they can reason about.
     */
    fun startLivingWorld(setup: com.aura.creative.livingworld.WorldSetup = com.aura.creative.livingworld.WorldSetup()) {
        val project = _state.value.selectedProject ?: return
        viewModelScope.launch {
            runCatching {
                require(worldSeeder.canSeed(project.world)) {
                    "Add at least two factions in the World tab first — a world needs someone to disagree."
                }
                val branchId = branchStore.createMainBranch(project.id).id
                val seeded = worldSeeder.seed(project.world, setup)
                livingWorldStore.create(
                    projectId = project.id,
                    branchId = branchId,
                    state = seeded,
                    worldEpochMs = System.currentTimeMillis(),
                )
                com.aura.creative.livingworld.LivingWorldScheduler.schedule(appContext)
            }.onSuccess {
                _state.update { it.copy(message = "The world has begun. It moves once an hour.", error = null) }
            }.onFailure { error ->
                Log.w(TAG, "starting living world failed: ${error.message}", error)
                _state.update { it.copy(error = error.message ?: "Could not start the world.") }
            }
        }
    }

    private data class LivingSnapshot(
        val world: com.aura.creative.livingworld.LivingWorldEntity?,
        val events: List<com.aura.creative.livingworld.LivingEventEntity>,
        val live: com.aura.creative.livingworld.LiveTick?,
        val all: List<com.aura.creative.livingworld.LivingWorldEntity> = emptyList(),
    )

    /**
     * Narrate one past event on request.
     *
     * Uncapped, unlike the background pass, because one request per deliberate
     * thumb press is self-limiting — and it is the cheapest way to make a long
     * history readable without having paid to narrate all of it up front.
     */
    fun narrateEvent(eventId: String) {
        val worldId = _state.value.livingWorld?.worldId ?: return
        viewModelScope.launch {
            _state.update { s -> s.copy(livingWorld = s.livingWorld?.copy(narrating = eventId)) }
            runCatching {
                val world = livingWorldStore.byId(worldId) ?: error("World not found.")
                val event = livingWorldStore.eventById(eventId) ?: error("Event not found.")
                worldNarrator.narrateOne(world, event, System.currentTimeMillis())
            }.onFailure { error ->
                Log.w(TAG, "on-demand narration failed: ${error.message}", error)
                _state.update { it.copy(error = "Could not narrate that moment.") }
            }
            _state.update { s -> s.copy(livingWorld = s.livingWorld?.copy(narrating = "")) }
        }
    }

    /** Ask for a slice now rather than at the next periodic window. */
    /** Which timeline the Living tab is looking at; null = the root world. */
    private val selectedWorldBranch = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** The worlds behind the chips, refreshed by every living-world emission. */
    private var latestWorlds: List<com.aura.creative.livingworld.LivingWorldEntity> = emptyList()

    private suspend fun selectedWorldEntity(): com.aura.creative.livingworld.LivingWorldEntity? {
        val shown = _state.value.livingWorld?.worldId ?: return null
        return latestWorlds.firstOrNull { it.id == shown } ?: livingWorldStore.byId(shown)
    }

    /** Fork the shown timeline at its present tick, under a new branch name. */
    fun forkLivingWorldNow(name: String) {
        val project = _state.value.selectedProject ?: return
        val trimmed = name.trim().ifBlank { return }
        viewModelScope.launch {
            runCatching {
                val parent = selectedWorldEntity() ?: return@launch
                val branch = branchStore.branchFrom(project.id, null, trimmed)
                livingWorldStore.fork(parent, branch.id, trimmed)
                selectWorldBranch(branch.id)
                _state.update { it.copy(message = appContext.getString(com.aura.R.string.fork_created, trimmed)) }
            }.onFailure { Log.w(TAG, "fork failed: ${it.message}", it) }
        }
    }

    /** Fork the shown timeline at a past tick — genesis required, honestly refused without. */
    fun forkLivingWorldAt(tick: Long, name: String) {
        val project = _state.value.selectedProject ?: return
        val trimmed = name.trim().ifBlank { return }
        viewModelScope.launch {
            runCatching {
                val parent = selectedWorldEntity() ?: return@launch
                val branch = branchStore.branchFrom(project.id, null, trimmed)
                val forked = livingWorldStore.forkAt(parent, tick, branch.id, trimmed)
                if (forked == null) {
                    _state.update { it.copy(error = appContext.getString(com.aura.R.string.fork_needs_genesis)) }
                } else {
                    selectWorldBranch(branch.id)
                    _state.update { it.copy(message = appContext.getString(com.aura.R.string.fork_created, trimmed)) }
                }
            }.onFailure { Log.w(TAG, "fork-at failed: ${it.message}", it) }
        }
    }

    /** Name the first event where the shown timeline parts from the root, and the standings now. */
    fun compareWithRoot() {
        viewModelScope.launch {
            runCatching {
                val shown = selectedWorldEntity() ?: return@launch
                val root = latestWorlds.firstOrNull { it.parentWorldId.isBlank() } ?: return@launch
                if (shown.id == root.id) return@launch
                val base = shown.forkedAtTick
                val ours = livingWorldStore.ascAfter(shown.id, base, DIVERGENCE_PAGE)
                val theirs = livingWorldStore.ascAfter(root.id, base, DIVERGENCE_PAGE)
                val parting = com.aura.creative.livingworld.TimelineDiff.firstDivergence(ours, theirs)
                val standings = com.aura.creative.livingworld.TimelineDiff.standingsDiff(
                    livingWorldStore.decode(shown.stateJson),
                    livingWorldStore.decode(root.stateJson),
                ).take(3)
                val lines = buildList {
                    if (parting == null) {
                        add(appContext.getString(com.aura.R.string.timelines_identical))
                    } else {
                        val moment = parting.a ?: parting.b
                        add(
                            appContext.getString(
                                com.aura.R.string.timelines_part_at,
                                com.aura.creative.livingworld.WorldClock.label(moment?.tickIndex ?: base),
                            ),
                        )
                        parting.a?.let { add("• ${it.summary}") }
                        parting.b?.let { add("• ${it.summary}") }
                    }
                    addAll(standings)
                }
                _state.update { state ->
                    state.copy(livingWorld = state.livingWorld?.copy(divergence = lines))
                }
            }.onFailure { Log.w(TAG, "compare failed: ${it.message}", it) }
        }
    }

    /** Open a drafted scene for the author's hands. */
    fun openSceneEditor(beatIndex: Int, artifactId: String, title: String) {
        if (artifactId.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val text = artifactStore.currentContent(artifactId).orEmpty()
                _state.update {
                    it.copy(sceneEditor = SceneEditorUi(beatIndex, artifactId, title, text))
                }
            }.onFailure { Log.w(TAG, "scene open failed: ${it.message}", it) }
        }
    }

    fun dismissSceneEditor() {
        _state.update { it.copy(sceneEditor = null) }
    }

    /**
     * Save the author's edit — CreativeArtifactStore.revise's first production
     * caller. An identical save writes no revision and records mild approval;
     * a change lands as an authorKind="edit" child of the generation revision,
     * and the keep-ratio decides what the taste profile hears. The beat keeps
     * pointing at the generation revision: canon provenance stays with what
     * the extractor read, while the artifact's current revision — the tail,
     * the export, the tension diff — moves to the author's text.
     */
    fun saveSceneEdit(newText: String) {
        val editor = _state.value.sceneEditor ?: return
        val project = _state.value.selectedProject ?: return
        viewModelScope.launch {
            runCatching {
                val generation = artifactStore.currentRevision(editor.artifactId) ?: return@launch
                val before = generation.contentText.orEmpty()
                if (newText == before) {
                    sceneEditSignals?.onSceneKept(project.id, editor.artifactId)
                } else {
                    artifactStore.revise(editor.artifactId, newText, authorKind = "edit")
                    sceneEditSignals?.onSceneEdited(
                        projectId = project.id,
                        templateId = project.templateId,
                        artifactId = editor.artifactId,
                        beforeText = before,
                        afterText = newText,
                    )
                }
                _state.update {
                    it.copy(
                        sceneEditor = null,
                        message = appContext.getString(com.aura.R.string.scene_saved),
                    )
                }
            }.onFailure { Log.w(TAG, "scene save failed: ${it.message}", it) }
        }
    }

    /**
     * The tab-open ritual: snapshot what was missed, then move the marker.
     *
     * A snapshot rather than a live filter on purpose — the block shows what
     * was news at the moment of opening and stays put until the next open,
     * instead of shrinking as the marker advances underneath the reader.
     */
    fun onLivingTabOpened() {
        viewModelScope.launch {
            runCatching {
                val shown = _state.value.livingWorld ?: return@launch
                val world = selectedWorldEntity() ?: return@launch
                val prefs = userPreferences ?: return@launch
                val lastSeen = prefs.livingWorldLastSeen.first()[world.id] ?: 0L
                val missed = shown.events
                    .filter { it.tick > lastSeen }
                    .sortedByDescending { it.notability }
                    .take(SINCE_YOU_LEFT_PAGE)
                _state.update {
                    it.copy(
                        livingWorld = it.livingWorld?.copy(
                            sinceYouLeft = missed,
                            daysAway = (world.currentTick - lastSeen).coerceAtLeast(0L),
                        ),
                    )
                }
                prefs.setLivingWorldLastSeen(world.id, world.currentTick)
            }.onFailure { Log.w(TAG, "since-you-left failed: ${it.message}", it) }
        }
    }

    /** Toggle the drama filter: the most notable discoveries, lies and conquests. */
    fun toggleNotableMoments() {
        viewModelScope.launch {
            runCatching {
                val shown = _state.value.livingWorld ?: return@launch
                if (shown.showNotable) {
                    _state.update { it.copy(livingWorld = it.livingWorld?.copy(showNotable = false)) }
                    return@launch
                }
                val moments = livingWorldStore.topNotableOfKinds(
                    shown.worldId,
                    listOf(
                        com.aura.creative.livingworld.WorldEngine.KIND_BELIEF_REVEAL,
                        com.aura.creative.livingworld.WorldEngine.KIND_LIE_TOLD,
                        com.aura.creative.livingworld.WorldEngine.KIND_CLAIM_WON,
                    ),
                    NOTABLE_PAGE,
                ).map {
                    LivingEventUi(
                        id = it.id, tick = it.tickIndex, kind = it.kind,
                        summary = it.summary, narration = it.narration, notability = it.notability,
                    )
                }
                _state.update {
                    it.copy(livingWorld = it.livingWorld?.copy(notableMoments = moments, showNotable = true))
                }
            }.onFailure { Log.w(TAG, "notable filter failed: ${it.message}", it) }
        }
    }

    fun selectWorldBranch(branchId: String?) {
        selectedWorldBranch.value = branchId
    }

    fun catchUpLivingWorld() {
        runCatching { com.aura.creative.livingworld.LivingWorldScheduler.catchUpNow(appContext) }
            .onFailure { Log.w(TAG, "catch-up enqueue failed: ${it.message}", it) }
    }

    /**
     * Pin the manuscript to the world's current tick. Drafting injects the
     * world's standings only while the pin matches the present exactly — the
     * world moves on within the hour, and a stale pin goes silent rather than
     * serving yesterday as today.
     */
    fun pinStoryCursor() {
        val project = _state.value.selectedProject ?: return
        viewModelScope.launch {
            runCatching {
                val world = livingWorldStore.forProject(project.id) ?: return@runCatching
                val updated = store.updateWorld(
                    project.id,
                    project.world.copy(storyCursorTick = world.currentTick),
                )
                _state.update { it.copy(selectedProject = updated ?: it.selectedProject) }
            }.onFailure { Log.w(TAG, "story pin failed: ${it.message}", it) }
        }
    }

    fun generate(mode: CreativeMode, prompt: String, perspective: String = "") {
        val project = _state.value.selectedProject ?: return
        val thinkingBudget = if (_state.value.thinkingEnabled) _state.value.thinkingBudget else 0
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(generating = true, output = "", error = null, wordCount = 0) }
            runCatching {
                engine.generate(project.id, mode, prompt, perspective, thinkingBudget = thinkingBudget).collect { chunk ->
                    _state.update { it.copy(output = it.output + chunk, wordCount = it.output.split(Regex("\\s+")).filter { w -> w.isNotBlank() }.size) }
                }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Creative generation failed.") }
            }
            // P0 fix: save the output as an artifact so conversation continuity works
            val output = _state.value.output
            if (output.isNotBlank() && output.length > 100) {
                runCatching {
                    val branch = branchStore.createMainBranch(project.id)
                    val model = engine.resolveModel()
                    artifactStore.create(
                        projectId = project.id,
                        branchId = branch.id,
                        kind = mode.name.lowercase(),
                        title = "${mode.label} — ${prompt.take(60)}",
                        initialContent = output,
                        authorKind = "ai",
                        prompt = prompt,
                    )
                }.onFailure { android.util.Log.w("CreativeVM", "artifact save failed: ${it.message}", it) }

                // P0 fix: auto-extract character progressions after DRAFT/SIMULATE
                if (mode == CreativeMode.DRAFT || mode == CreativeMode.SIMULATE) {
                    runCatching {
                        val progressionOutput = StringBuilder()
                        progressionTracker.extractFromScene(
                            sceneText = output.take(8000),
                            knownCharacters = project.world.characters,
                            sceneLabel = "${mode.label} turn ${project.turnCount + 1}",
                        ).collect { chunk -> progressionOutput.append(chunk) }
                        if (progressionOutput.isNotBlank()) {
                            _state.update { it.copy(message = "Progression extracted: ${progressionOutput.take(200)}") }
                        }
                    }.onFailure { android.util.Log.w("CreativeVM", "progression extract failed: ${it.message}", it) }
                }
            }
            val refreshed = store.get(project.id)
            _state.update { it.copy(selectedProject = refreshed ?: it.selectedProject, generating = false) }
        }
    }

    // P0 fix: Prose craft tools — operate on selected text
    fun applyCraftTool(tool: ProseCraftTools.CraftTool, selectedText: String, context: String = "") {
        val project = _state.value.selectedProject ?: return
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(generating = true, output = "", error = null, wordCount = 0) }
            runCatching {
                proseCraftTools.apply(
                    tool = tool,
                    selectedText = selectedText,
                    context = context,
                    projectId = project.id,
                    voiceProfile = _state.value.voiceProfile,
                ).collect { chunk ->
                    _state.update { it.copy(output = it.output + chunk) }
                }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Craft tool failed.") }
            }
            _state.update { it.copy(generating = false) }
        }
    }

    // P0 fix: Voice calibration
    fun calibrateVoice(sample: String) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(calibrating = true, error = null) }
            runCatching {
                val profile = StringBuilder()
                voiceCalibration.calibrate(sample).collect { chunk -> profile.append(chunk) }
                _state.update { it.copy(voiceProfile = profile.toString(), calibrating = false, message = "Voice profile calibrated.") }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Voice calibration failed.", calibrating = false) }
            }
        }
    }

    /**
     * Score a manuscript, and keep the result when there is something to keep it
     * against.
     *
     * [artifactId] is optional because the pane also accepts pasted text that
     * belongs to no draft in this app. That case still analyses and still
     * renders — it simply cannot be compared to anything later, so storing it
     * would be storing a number with no second number to give it meaning.
     *
     * When an artifact *is* given, the report is keyed to its current revision
     * and diffed against the revision that one came from. That is the whole
     * point of the feature: not another opinion about the new draft, but what
     * the rewrite actually moved.
     */
    fun analyzeTension(manuscript: String, artifactId: String? = null) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update {
                it.copy(analyzingTension = true, tensionReport = "", tensionDiff = null, error = null)
            }
            runCatching {
                val report = tensionAnalyzer.analyze(manuscript)
                val diff = artifactId?.let { id -> storeAndDiff(id, report) }
                _state.update {
                    it.copy(
                        tensionReport = tensionAnalyzer.render(report),
                        tensionDiff = diff,
                        analyzingTension = false,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Tension analysis failed.", analyzingTension = false) }
            }
        }
    }

    /**
     * Persist against the artifact's current revision, then compare.
     *
     * Failure here must not lose the analysis the user just paid for, so it is
     * caught and the report still renders — an unstored report is worth strictly
     * more than an error where the report would have been.
     */
    private suspend fun storeAndDiff(
        artifactId: String,
        report: com.aura.creative.TensionReport,
    ): com.aura.creative.TensionDiff? = runCatching {
        val revisionId = artifactStore.get(artifactId)?.currentRevisionId ?: return null
        creativeAnalysisStore.saveTension(revisionId, artifactId, report)
        creativeAnalysisStore.diffAgainstParent(revisionId)
    }.onFailure { android.util.Log.w("CreativeVM", "storing tension failed: ${it.message}", it) }
        .getOrNull()

    fun toggleThinking() {
        _state.update { it.copy(thinkingEnabled = !it.thinkingEnabled) }
    }

    fun runCouncil(brief: String, roles: List<CouncilRole>) {
        val project = _state.value.selectedProject ?: return
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(generating = true, output = "", error = null, councilResult = null) }
            runCatching {
                val result = council.run(
                    request = CouncilSessionRequest(
                        projectId = project.id,
                        brief = brief,
                        roles = roles,
                    ),
                    executor = { task ->
                        val modelId = resolveSubagentModel(task.spec.modelRole)
                        val messages = listOf(
                            ProviderMessage(role = ProviderMessage.Role.system, content = task.spec.objective),
                            ProviderMessage(role = ProviderMessage.Role.user, content = brief),
                        )
                        val start = System.currentTimeMillis()
                        val output = StringBuilder()
                        // P1 fix: route through Brain with thinking + proper maxTokens
                        brain.stream(modelId, messages, emptyList(), ChatOptions(maxTokens = 8_192, temperature = 0.7)).collect { chunk ->
                            when (chunk) {
                                is com.aura.agent.BrainChunk.Text -> {
                                    if (chunk.text.isNotEmpty()) output.append(chunk.text)
                                }
                                is com.aura.agent.BrainChunk.Error -> throw IllegalStateException(chunk.message)
                                else -> {}
                            }
                        }
                        com.aura.agents.SubagentResult(
                            taskId = task.id,
                            success = true,
                            output = output.toString(),
                            rationale = "Executed via model role ${task.spec.modelRole}.",
                            durationMs = System.currentTimeMillis() - start,
                        )
                    },
                )
                _state.update {
                    it.copy(
                        output = result.directorOutput.ifBlank { result.proposals.joinToString("\n\n---\n\n") { p -> "${p.role.displayName}: ${p.content}" } },
                        councilResult = result,
                        generating = false,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Council failed.", generating = false) }
            }
        }
    }

    private suspend fun resolveSubagentModel(modelRole: kotlin.String): kotlin.String {
        // Resolve the council role to a real provider:model via
        // ModelRoleRouter, which reads user-configured role models
        // and falls back to the default conversation model.
        // Never returns a bare provider prefix or "default" — those
        // cause ProviderRegistry.parse() to reject the call.
        val role = runCatching {
            com.aura.providers.ModelRole.valueOf(modelRole)
        }.onFailure { Log.w("CreativeStudioViewModel", "runCatching failed: ${it.message}", it) }.getOrDefault(com.aura.providers.ModelRole.CREATIVE_DRAFT)
        return modelRoleRouter.resolve(role)
            ?: throw IllegalStateException("No model configured for $role. Set a default model in Settings.")
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        _state.update { it.copy(generating = false) }
    }

    /**
     * Compile the open project's drafted scenes into one Markdown document.
     *
     * Returns the document, or null when there is nothing to export or the read
     * failed — the caller launches the share Intent, because no ViewModel in
     * this app constructs one. `BackupViewModel.prepareExportFile` is the shape
     * this follows: produce the payload here, hand it out at the composable.
     *
     * Reads through [CreativeArtifactStore.currentRevision], never
     * `currentContent`. `currentContent` falls back to `previewText` — the first
     * 200 characters — whenever the revision does not resolve, and a stub that
     * length reads as a finished scene in the middle of a novel. Here the text
     * is either genuinely recovered or the document says it could not be.
     *
     * The total artifact count goes to the compiler so it can name prose the
     * outline has lost track of. Re-planning an outline replaces every beat with
     * a fresh one carrying a blank `artifactId`, which would otherwise produce a
     * document that reads as total data loss while the scenes sit safely in the
     * table.
     */
    suspend fun exportManuscript(): String? {
        val project = _state.value.selectedProject ?: return null
        return runCatching {
            val texts = project.world.outline
                .map { it.artifactId }
                .filter { it.isNotBlank() }
                .distinct()
                .associateWith { artifactStore.currentRevision(it)?.contentText }
            val sceneCount = artifactStore
                .forProjectByKind(project.id, SCENE_KIND)
                .size
            com.aura.creative.ManuscriptCompiler.compile(project, texts, sceneCount)
        }.onFailure { error ->
            _state.update { it.copy(error = error.message ?: "Could not compile the manuscript.") }
        }.getOrNull()
    }

    /**
     * The share sheet refused the document.
     *
     * `shareTextFile` returns false when the file could not be staged or nothing
     * on the device accepted the intent, and without this the user taps Export
     * and sees absolutely nothing — indistinguishable from a button that was
     * never wired up. The banner already renders `state.error`, so saying so
     * costs one line.
     */
    fun reportExportFailed() {
        _state.update { it.copy(error = "Nothing on this device accepted the manuscript.") }
    }

    fun canonizeSimulation(simulationId: String) {
        val id = _state.value.selectedProject?.id ?: return
        viewModelScope.launch {
            val project = store.canonizeSimulation(id, simulationId)
            _state.update { it.copy(selectedProject = project ?: it.selectedProject, message = "Simulation added to canon timeline.") }
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            store.delete(id)
            _state.update { it.copy(selectedProject = if (it.selectedProject?.id == id) null else it.selectedProject) }
        }
    }

    fun clearNotice() {
        _state.update { it.copy(error = null, message = null) }
    }
}