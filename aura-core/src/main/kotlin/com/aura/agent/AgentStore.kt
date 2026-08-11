package com.aura.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CRUD wrapper over [AgentDao]. Seeds 7 builtin agents from
 * [Specialist.ALL] on first run.
 */
@Singleton
class AgentStore @Inject constructor(
    private val dao: AgentDao,
    private val stateStore: com.aura.agent.state.AgentStateStore? = null,
) {
    private val seedMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): Flow<List<AgentEntity>> = dao.all()

    suspend fun allOnce(): List<AgentEntity> = dao.allOnce()

    suspend fun byId(id: String): AgentEntity? = dao.getById(id)

    suspend fun byName(name: String): AgentEntity? = dao.byName(name)

    suspend fun builtins(): List<AgentEntity> = dao.builtins()

    suspend fun customs(): List<AgentEntity> = dao.customs()

    suspend fun count(): Int = dao.count()

    /**
     * Insert 7 builtin agents mapped from [Specialist.ALL] if
     * the database is empty. Safe to call on every startup —
     * only inserts when count == 0.
     */
    suspend fun seedBuiltins() {
        seedMutex.withLock {
            if (dao.count() > 0) return@withLock
        val now = System.currentTimeMillis()
        val agents = Specialist.ALL.mapIndexed { idx, s ->
            val personality = when (s.name) {
                "general" -> PersonalityProfile.General
                "coder" -> PersonalityProfile.Coder
                "researcher" -> PersonalityProfile.Researcher
                "writer" -> PersonalityProfile.Writer
                "creative" -> PersonalityProfile.Creative
                "executive" -> PersonalityProfile.Executive
                "phone_native" -> PersonalityProfile.PhoneNative
                else -> PersonalityProfile()
            }
            AgentEntity(
                id = "agent_${s.name}",
                name = s.name,
                icon = s.icon,
                description = s.blurb,
                identity = s.systemPrompt,
                toolsAllowed = s.toolsAllowed.joinToString(","),
                preferredModel = s.suggestedModel,
                memoryScope = if (s.name == "general") "shared" else "agent:agent_${s.name}",
                personalityJson = json.encodeToString(PersonalityProfile.serializer(), personality),
                isBuiltin = true,
                isDefault = s.name == "general",
                createdAt = now,
                updatedAt = now,
                color = idx,
            )
        }
        dao.insertAll(agents)
        } // end seedMutex.withLock
    }

    /**
     * Give every agent an `agent_state` row, creating any that are missing.
     *
     * This used to live inside [seedBuiltins], after `dao.insertAll`, and was
     * therefore unreachable once `dao.count() > 0` — which is every launch
     * after the first. That was survivable only for as long as nothing deleted
     * the rows. Something did: `agents` was written with `INSERT OR REPLACE`
     * while five tables cascade off it, so re-saving an agent — which
     * `refreshBuiltinDescriptions` did on every launch, and the agent editor
     * does on every save — deleted its state, relationships, observations,
     * forum posts and votes.
     *
     * Losing them was silent in both directions. `AgentStateDao`'s writers are
     * all `UPDATE … WHERE agentId = :agentId`, so they matched zero rows and
     * reported success forever after; and the council UI substitutes a
     * transient default when `getState` returns null, so the screens showed
     * plausible neutral moods over an empty table.
     *
     * The REPLACE is fixed, so nothing deletes these rows any more. This runs
     * on every startup anyway, because the rows already lost cannot come back
     * on their own, and because [create] never called `ensureState` at all —
     * user-created agents have never had a state row.
     */
    suspend fun ensureAllAgentStates() {
        val store = stateStore ?: return
        val agents = runCatching { dao.allOnce() }
            .onFailure { Log.w("AgentStore", "ensureAllAgentStates: listing agents failed: ${it.message}", it) }
            .getOrDefault(emptyList())
        var repaired = 0
        for (agent in agents) {
            runCatching {
                if (store.getState(agent.id) == null) {
                    store.ensureState(agent.id)
                    repaired++
                }
            }.onFailure { Log.w("AgentStore", "ensureState ${agent.id}: ${it.message}", it) }
        }
        if (repaired > 0) Log.i("AgentStore", "recreated $repaired missing agent_state row(s)")
    }

    /**
     * Give already-seeded builtins the description they should have had.
     *
     * [seedBuiltins] only runs when the table is empty, so every install made
     * before descriptions were written by hand still stores
     * `systemPrompt.take(80)` — "You are Aura's coding specialist. You excel at"
     * and so on. Reseeding is not an option (it would discard the user's own
     * agents' neighbours and the council state keyed off these rows), so this
     * rewrites the one field in place.
     *
     * Only rows still carrying the generated text are touched. A description
     * the user edited does not start with "You are", so their wording survives
     * — the check is what makes this safe to run on every launch.
     */
    suspend fun refreshBuiltinDescriptions() {
        val stale = dao.builtins().filter { it.description.startsWith(GENERATED_DESCRIPTION_PREFIX) }
        if (stale.isEmpty()) return
        val now = System.currentTimeMillis()
        stale.forEach { agent ->
            val blurb = Specialist.byName(agent.name)?.blurb ?: return@forEach
            dao.insert(agent.copy(description = blurb, updatedAt = now))
        }
        Log.i("AgentStore", "rewrote ${stale.size} builtin description(s)")
    }

    /**
     * Create a custom agent. Returns the created entity.
     */
    suspend fun create(
        name: String,
        icon: String,
        description: String,
        identity: String,
        tools: Set<String>,
        preferredModel: String?,
        memoryScope: String,
        personality: PersonalityProfile,
        color: Int = 0,
    ): AgentEntity {
        val now = System.currentTimeMillis()
        val id = "agent_custom_${now}_${name.lowercase().replace(Regex("[^a-z0-9]"), "_")}"
        val agent = AgentEntity(
            id = id,
            name = name,
            icon = icon,
            description = description,
            identity = identity,
            toolsAllowed = tools.joinToString(","),
            preferredModel = preferredModel,
            memoryScope = memoryScope,
            personalityJson = json.encodeToString(PersonalityProfile.serializer(), personality),
            isBuiltin = false,
            isDefault = false,
            createdAt = now,
            updatedAt = now,
            color = color,
        )
        dao.insert(agent)
        return agent
    }

    suspend fun update(agent: AgentEntity) {
        dao.insert(agent.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Delete a custom agent. Builtin agents cannot be deleted. */
    suspend fun delete(id: String) {
        dao.deleteCustom(id)
    }

    /** Delete all custom agents. Builtin agents remain. */
    suspend fun deleteAllCustom() {
        dao.deleteAllCustom()
    }

    private companion object {
        /**
         * Every builtin system prompt opens "You are Aura's ...", so this is
         * what a description sliced off one begins with — and nothing a person
         * would write about an agent they were describing to themselves.
         */
        const val GENERATED_DESCRIPTION_PREFIX = "You are Aura's"
    }
}
