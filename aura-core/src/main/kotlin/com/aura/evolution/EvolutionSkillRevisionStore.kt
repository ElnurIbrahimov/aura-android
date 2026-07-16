package com.aura.evolution

import com.aura.security.KeyManager
import com.aura.skills.Skill
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Revisioned storage for user-authored skills. Keeps the existing DataStore
 * envelope as the runtime source of truth, but also writes immutable
 * encrypted snapshots to [EvolutionRevisionEntity] for rollback/audit.
 */
@Singleton
class EvolutionSkillRevisionStore @Inject constructor(
    private val revisionDao: EvolutionRevisionDao,
    private val keyManager: KeyManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key by lazy { keyManager.getOrCreateKey() }

    suspend fun snapshot(skill: Skill, proposalId: kotlin.String? = null, summary: kotlin.String = "user edit"): kotlin.String {
        val plaintext = json.encodeToString(skill)
        val ciphertext = keyManager.encrypt(plaintext, key)
        val revision = EvolutionRevisionEntity(
            id = UUID.randomUUID().toString(),
            domain = EvolutionDomain.SKILL.name,
            targetId = skill.id,
            proposalId = proposalId,
            summary = summary,
            snapshotCiphertext = ciphertext,
            metadataJson = json.encodeToString(mapOf("name" to skill.name, "description" to skill.description)),
        )
        revisionDao.upsert(revision)
        return revision.id
    }

    suspend fun latest(skillId: kotlin.String): Skill? {
        val rev = revisionDao.history(EvolutionDomain.SKILL.name, skillId, limit = 1).firstOrNull()
            ?: return null
        val plaintext = keyManager.decrypt(rev.snapshotCiphertext, key) ?: return null
        return runCatching { json.decodeFromString<Skill>(plaintext) }.getOrNull()
    }
}
