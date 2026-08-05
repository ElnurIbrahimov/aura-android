package com.aura.ui.viewmodel

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
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

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
) : ViewModel() {
    private val _state = MutableStateFlow(CreativeStudioUiState())
    val state: StateFlow<CreativeStudioUiState> = _state.asStateFlow()
    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            store.observeAll().collect { projects ->
                val selectedId = _state.value.selectedProject?.id
                _state.update { current ->
                    current.copy(
                        projects = projects,
                        selectedProject = selectedId?.let { id -> projects.find { it.id == id } }
                            ?: current.selectedProject,
                        loading = false,
                    )
                }
            }
        }
    }

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

    // P0 fix: Tension analysis
    fun analyzeTension(manuscript: String) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(analyzingTension = true, tensionReport = "", error = null) }
            runCatching {
                val report = StringBuilder()
                tensionAnalyzer.analyze(manuscript).collect { chunk -> report.append(chunk) }
                _state.update { it.copy(tensionReport = report.toString(), analyzingTension = false) }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Tension analysis failed.", analyzingTension = false) }
            }
        }
    }

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