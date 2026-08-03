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
                description = s.systemPrompt.take(80),
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
        // Seed initial state for each agent so the Council has mood/energy from day one.
        stateStore?.let { store ->
            agents.forEach { agent ->
                runCatching { store.ensureState(agent.id) }
                    .onFailure { Log.w("AgentStore", "seedState ${agent.id}: ${it.message}", it) }
            }
        }
        } // end seedMutex.withLock
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
}