package com.aura.skills

import android.util.Log
import com.aura.security.SecureDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_SKILLS = "aura_skills_v1"

/**
 * Persists user-authored skills in DataStore as a single JSON envelope.
 *
 * The store is intentionally minimal: there is no per-skill on-disk file
 * (DataStore isn't a good fit for many small files) and no Room table
 * (skills are configuration, not state). The envelope is loaded lazily
 * on first access; if the JSON is malformed, the store treats the install
 * as having zero skills and overwrites the bad blob on the next write.
 */
@Singleton
class SkillsStore @Inject constructor(
    private val secureDataStore: SecureDataStore,
    private val skillRevisionStore: com.aura.evolution.EvolutionSkillRevisionStore? = null,
    private val evolutionHooks: com.aura.evolution.EvolutionHooks? = null,
) {
    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    @Volatile private var loaded: Boolean = false
    private val lock = Any()

    suspend fun awaitLoaded() {
        if (loaded) return
        // Read the persisted blob outside the critical section (getString is suspend);
        // only mutate state under the lock so concurrent callers don't double-init.
        val raw: String? = secureDataStore.getString(KEY_SKILLS)
        synchronized(lock) {
            if (loaded) return
            _skills.value = (raw ?: "").decodeAsSkillList()
            loaded = true
        }
    }

    suspend fun add(skill: Skill) {
        awaitLoaded()
        val updated = _skills.value + skill
        _skills.value = updated
        secureDataStore.putString(KEY_SKILLS, updated.encodeToJsonString())
        runCatching { skillRevisionStore?.snapshot(skill, summary = "created") }.onFailure { Log.w("SkillsStore", "snapshot (created) failed", it) }
        runCatching { evolutionHooks?.onSkillAdded(skill.id) }.onFailure { Log.w("SkillsStore", "onSkillAdded hook failed", it) }
    }

    suspend fun update(skill: Skill) {
        awaitLoaded()
        val previous = _skills.value.firstOrNull { it.id == skill.id }
        val updated = _skills.value.map { if (it.id == skill.id) skill else it }
        _skills.value = updated
        secureDataStore.putString(KEY_SKILLS, updated.encodeToJsonString())
        runCatching { previous?.let { skillRevisionStore?.snapshot(it, proposalId = null, summary = "before edit") } }.onFailure { Log.w("SkillsStore", "snapshot (before edit) failed", it) }
        runCatching { skillRevisionStore?.snapshot(skill, summary = "user edit") }.onFailure { Log.w("SkillsStore", "snapshot (user edit) failed", it) }
        runCatching { evolutionHooks?.onSkillEdited(skill.id) }.onFailure { Log.w("SkillsStore", "onSkillEdited hook failed", it) }
    }

    /**
     * Insert any [seeds] whose name is not already present.
     *
     * Keyed on absent *names* rather than on an empty store, which is what
     * makes it safe to call on every startup: a later version that adds a craft
     * prompt seeds only that one, and an author who has rewritten a builtin
     * keeps their version forever. Modelled on `AgentStore.seedBuiltins`, which
     * does the same job for the seven builtin agents.
     *
     * Fires no evolution hook. Seeding is the app arriving with its own
     * knowledge, not the user authoring a skill, and recording it as authorship
     * would put a creation event on every install.
     */
    suspend fun seedBuiltins(seeds: List<Skill>) {
        awaitLoaded()
        val existing = _skills.value.mapTo(mutableSetOf()) { it.name.lowercase() }
        val missing = seeds.filterNot { it.name.lowercase() in existing }
        if (missing.isEmpty()) return
        val updated = _skills.value + missing
        _skills.value = updated
        secureDataStore.putString(KEY_SKILLS, updated.encodeToJsonString())
    }

    /**
     * Restore a builtin's body and description to what shipped, from the same
     * [seeds] list that created it. A no-op for a name that is not a seed.
     */
    suspend fun resetBuiltin(name: String, seeds: List<Skill>) {
        awaitLoaded()
        val current = findByName(name) ?: return
        val seed = seeds.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
        update(current.withBody(seed.body).withDescription(seed.description))
    }

    suspend fun remove(id: String) {
        awaitLoaded()
        // Builtins are editable, not deletable — see [Skill.builtin]. The refusal
        // lives here rather than in the UI so it holds for every caller,
        // including the evolution system's RETIRE_SKILL action.
        if (_skills.value.firstOrNull { it.id == id }?.builtin == true) {
            Log.w("SkillsStore", "refusing to remove builtin skill $id; reset it instead")
            return
        }
        val previous = _skills.value.firstOrNull { it.id == id }
        val updated = _skills.value.filterNot { it.id == id }
        _skills.value = updated
        secureDataStore.putString(KEY_SKILLS, updated.encodeToJsonString())
        runCatching { previous?.let { skillRevisionStore?.snapshot(it, proposalId = null, summary = "before removal") } }.onFailure { Log.w("SkillsStore", "snapshot (before removal) failed", it) }
        runCatching { evolutionHooks?.onSkillRemoved(id) }.onFailure { Log.w("SkillsStore", "onSkillRemoved hook failed", it) }
    }

    fun findById(id: String): Skill? = _skills.value.firstOrNull { it.id == id }

    fun findByName(name: String): Skill? = _skills.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
