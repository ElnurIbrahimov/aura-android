package com.aura.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.AgentEntity
import com.aura.agent.AgentStore
import com.aura.agent.PersonalityProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
data class AgentEditorUiState(
    val id: String? = null,
    val name: String = "",
    val icon: String = "\uD83E\uDD16",
    val description: String = "",
    val identity: String = "",
    val toolsAllowed: Set<String> = emptySet(),
    val preferredModel: String = "",
    val memoryScope: String = "agent",
    val personality: PersonalityProfile = PersonalityProfile(),
    val color: Int = 0,
    val isBuiltin: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val showTemplatePicker: Boolean = false,
)

@HiltViewModel
class AgentEditorViewModel @Inject constructor(
    private val agentStore: AgentStore,
    private val toolRegistry: com.aura.agent.ToolRegistry,
    private val agentTemplates: com.aura.agent.AgentTemplates = com.aura.agent.AgentTemplates(),
) : ViewModel() {

    /** All available tool names from the registry, sorted alphabetically. */
    val availableTools: List<String> get() = toolRegistry.definitions().map { it.name }.sorted()

    /** All agent templates for the template picker. */
    val templates: List<com.aura.agent.AgentTemplates.Template> get() = agentTemplates.all

    fun showTemplatePicker() {
        _state.value = _state.value.copy(showTemplatePicker = true)
    }

    fun dismissTemplatePicker() {
        _state.value = _state.value.copy(showTemplatePicker = false)
    }

    fun applyTemplate(template: com.aura.agent.AgentTemplates.Template) {
        _state.value = _state.value.copy(
            name = template.name,
            description = template.description,
            identity = template.systemPromptHint,
            toolsAllowed = template.toolsAllowed ?: emptySet(),
            personality = template.personality,
            showTemplatePicker = false,
        )
    }

    private val _state = MutableStateFlow(AgentEditorUiState())
    val state: StateFlow<AgentEditorUiState> = _state.asStateFlow()

    fun loadAgent(id: String) {
        viewModelScope.launch {
            val agent = agentStore.byId(id) ?: return@launch
            _state.value = AgentEditorUiState(
                id = agent.id,
                name = agent.name,
                icon = agent.icon,
                description = agent.description,
                identity = agent.identity,
                toolsAllowed = agent.toolSet(),
                preferredModel = agent.preferredModel.orEmpty(),
                memoryScope = if (agent.memoryScope == "shared") "shared" else "agent",
                personality = agent.personality(),
                color = agent.color,
                isBuiltin = agent.isBuiltin,
            )
        }
    }

    fun updateName(v: String) { _state.value = _state.value.copy(name = v) }
    fun updateIcon(v: String) { _state.value = _state.value.copy(icon = v) }
    fun updateDescription(v: String) { _state.value = _state.value.copy(description = v) }
    fun updateIdentity(v: String) { _state.value = _state.value.copy(identity = v) }
    fun updatePreferredModel(v: String) { _state.value = _state.value.copy(preferredModel = v) }
    fun updateMemoryScope(v: String) { _state.value = _state.value.copy(memoryScope = v) }
    fun updateColor(v: Int) { _state.value = _state.value.copy(color = v) }

    fun toggleTool(toolName: String) {
        val current = _state.value.toolsAllowed
        _state.value = _state.value.copy(
            toolsAllowed = if (toolName in current) current - toolName else current + toolName,
        )
    }

    fun updatePersonality(dim: String, value: Float) {
        val p = _state.value.personality
        _state.value = _state.value.copy(
            personality = when (dim) {
                "warmth" -> p.copy(warmth = value)
                "formality" -> p.copy(formality = value)
                "verbosity" -> p.copy(verbosity = value)
                "humor" -> p.copy(humor = value)
                "proactivity" -> p.copy(proactivity = value)
                "riskTolerance" -> p.copy(riskTolerance = value)
                else -> p
            },
        )
    }

    fun save() {
        val s = _state.value
        if (s.name.isBlank()) {
            _state.value = s.copy(error = "Name is required")
            return
        }
        if (s.identity.isBlank()) {
            _state.value = s.copy(error = "Identity/system prompt is required")
            return
        }
        viewModelScope.launch {
            val scope = if (s.memoryScope == "shared") "shared" else "agent:agent_custom_${System.currentTimeMillis()}_${s.name.lowercase().replace(Regex("[^a-z0-9]"), "_")}"
            if (s.id != null) {
                val existing = agentStore.byId(s.id)
                if (existing != null) {
                    agentStore.update(existing.copy(
                        name = s.name,
                        icon = s.icon,
                        description = s.description,
                        identity = s.identity,
                        toolsAllowed = s.toolsAllowed.joinToString(","),
                        preferredModel = s.preferredModel.ifBlank { null },
                        memoryScope = if (existing.isBuiltin) existing.memoryScope else scope,
                        personalityJson = kotlinx.serialization.json.Json.encodeToString(PersonalityProfile.serializer(), s.personality),
                        color = s.color,
                        updatedAt = System.currentTimeMillis(),
                    ))
                }
            } else {
                agentStore.create(
                    name = s.name,
                    icon = s.icon,
                    description = s.description,
                    identity = s.identity,
                    tools = s.toolsAllowed,
                    preferredModel = s.preferredModel.ifBlank { null },
                    memoryScope = scope,
                    personality = s.personality,
                    color = s.color,
                )
            }
            _state.value = _state.value.copy(saved = true)
        }
    }

    fun delete() {
        val id = _state.value.id ?: return
        viewModelScope.launch {
            agentStore.delete(id)
            _state.value = _state.value.copy(saved = true)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}