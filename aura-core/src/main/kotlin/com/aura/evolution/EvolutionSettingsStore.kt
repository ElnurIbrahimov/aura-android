package com.aura.evolution

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Operations on [EvolutionSettingsEntity]. Guarantees a default row exists
 * for each [EvolutionDomain] so callers never have to handle null settings.
 */
@Singleton
class EvolutionSettingsStore @Inject constructor(
    private val dao: EvolutionSettingsDao,
) {
    suspend fun get(domain: EvolutionDomain): EvolutionSettingsEntity =
        dao.get(domain.name) ?: defaultFor(domain).also { dao.upsert(it) }

    suspend fun all(): List<EvolutionSettingsEntity> {
        val existing = dao.all().associateBy { it.domain }
        return EvolutionDomain.entries.map { domain ->
            existing[domain.name] ?: defaultFor(domain).also { dao.upsert(it) }
        }
    }

    suspend fun setAutoApplyApproved(domain: EvolutionDomain, approved: kotlin.Boolean) {
        dao.upsert(get(domain).copy(autoApplyApproved = approved, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setReflectionEnabled(domain: EvolutionDomain, enabled: kotlin.Boolean) {
        dao.upsert(get(domain).copy(reflectionEnabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    private fun defaultFor(domain: EvolutionDomain): EvolutionSettingsEntity =
        EvolutionSettingsEntity(domain = domain.name)
}
