package com.aura.evolution

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EvolutionEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(evidence: EvolutionEvidenceEntity)

    @Query("SELECT * FROM evolution_evidence WHERE domain = :domain AND kind = :kind ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byKind(domain: kotlin.String, kind: kotlin.String, limit: Int = 200): List<EvolutionEvidenceEntity>

    @Query("SELECT * FROM evolution_evidence WHERE sourceEntityId = :id ORDER BY createdAt DESC LIMIT :limit")
    suspend fun forSource(id: kotlin.String, limit: Int = 100): List<EvolutionEvidenceEntity>

    @Query("SELECT * FROM evolution_evidence WHERE createdAt <= :cutoff")
    suspend fun olderThan(cutoff: kotlin.Long): List<EvolutionEvidenceEntity>

    @Query("DELETE FROM evolution_evidence WHERE createdAt <= :cutoff")
    suspend fun deleteOlderThan(cutoff: kotlin.Long): Int

    @Query("SELECT COUNT(*) FROM evolution_evidence WHERE domain = :domain AND createdAt >= :since")
    suspend fun countSince(domain: kotlin.String, since: kotlin.Long): Int

    @Query("SELECT * FROM evolution_evidence ORDER BY createdAt ASC")
    suspend fun allForBackup(): List<EvolutionEvidenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(evidence: List<EvolutionEvidenceEntity>)

    @Query("DELETE FROM evolution_evidence")
    suspend fun deleteAll()
}

@Dao
interface EvolutionCandidateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(candidate: EvolutionCandidateEntity)

    @Query("SELECT * FROM evolution_candidates WHERE domain = :domain AND status = :status ORDER BY score DESC LIMIT :limit")
    suspend fun byStatus(domain: kotlin.String, status: kotlin.String, limit: Int = 100): List<EvolutionCandidateEntity>

    @Query("SELECT * FROM evolution_candidates WHERE id = :id LIMIT 1")
    suspend fun getById(id: kotlin.String): EvolutionCandidateEntity?

    /**
     * Dedup lookup (D5): one candidate row per (domain, action, targetId).
     * Backed by index_evolution_candidates_domain_action_targetId; the v3→v4
     * migration collapsed historic duplicates so at most one row matches.
     */
    @Query("SELECT * FROM evolution_candidates WHERE domain = :domain AND action = :action AND targetId = :targetId ORDER BY createdAt DESC LIMIT 1")
    suspend fun findByKey(domain: kotlin.String, action: kotlin.String, targetId: kotlin.String): EvolutionCandidateEntity?

    @Query("UPDATE evolution_candidates SET status = :status, updatedAt = :timestamp, reflectionResult = :reflection WHERE id = :id")
    suspend fun setStatus(id: kotlin.String, status: kotlin.String, reflection: kotlin.String = "", timestamp: kotlin.Long = System.currentTimeMillis())

    @Query("SELECT * FROM evolution_candidates ORDER BY createdAt ASC")
    suspend fun allForBackup(): List<EvolutionCandidateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(candidates: List<EvolutionCandidateEntity>)

    @Query("DELETE FROM evolution_candidates")
    suspend fun deleteAll()
}

@Dao
interface EvolutionProposalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(proposal: EvolutionProposalEntity)

    @Query("SELECT * FROM evolution_proposals WHERE status IN ('PENDING_REVIEW', 'APPROVED', 'APPLY_FAILED') ORDER BY createdAt DESC")
    fun observeOpen(): Flow<List<EvolutionProposalEntity>>

    @Query("SELECT * FROM evolution_proposals WHERE status IN ('PENDING_REVIEW', 'APPROVED', 'APPLY_FAILED') ORDER BY createdAt DESC")
    suspend fun open(): List<EvolutionProposalEntity>

    /**
     * Proposals that have been applied and could still be undone.
     *
     * The inbox listed only [open] proposals, whose WHERE clause cannot match
     * an APPLIED row — so the rollback button, which renders only for APPLIED,
     * could never appear, and the rollback screen looked up its proposal in
     * that same list and always rendered "not found". Rollback was implemented,
     * tested, and unreachable.
     */
    @Query(
        "SELECT * FROM evolution_proposals WHERE status = 'APPLIED' AND resolvedAt >= :since " +
            "ORDER BY resolvedAt DESC LIMIT :limit",
    )
    suspend fun appliedSince(since: kotlin.Long, limit: Int = 20): List<EvolutionProposalEntity>

    /**
     * Reactive count of proposals awaiting user action (PENDING_REVIEW
     * only — APPROVED/APPLY_FAILED are already in flight). Used by the
     * bottom-nav badge so the user can see "3 new proposals" without
     * opening the inbox.
     */
    @Query("SELECT COUNT(*) FROM evolution_proposals WHERE status = 'PENDING_REVIEW'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM evolution_proposals WHERE domain = :domain ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byDomain(domain: kotlin.String, limit: Int = 200): List<EvolutionProposalEntity>

    @Query("SELECT * FROM evolution_proposals WHERE id = :id LIMIT 1")
    suspend fun getById(id: kotlin.String): EvolutionProposalEntity?

    @Query("UPDATE evolution_proposals SET status = :status, updatedAt = :timestamp, outcomeNote = :note WHERE id = :id")
    suspend fun setStatus(id: kotlin.String, status: kotlin.String, note: kotlin.String = "", timestamp: kotlin.Long = System.currentTimeMillis())

    @Query("UPDATE evolution_proposals SET status = :status, resolvedAt = :timestamp, outcomeNote = :note WHERE id = :id")
    suspend fun resolve(id: kotlin.String, status: kotlin.String, note: kotlin.String, timestamp: kotlin.Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(proposals: List<EvolutionProposalEntity>)

    @Query("SELECT * FROM evolution_proposals ORDER BY createdAt DESC")
    suspend fun allForBackup(): List<EvolutionProposalEntity>

    @Query("DELETE FROM evolution_proposals")
    suspend fun deleteAll()
}

@Dao
interface EvolutionRevisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(revision: EvolutionRevisionEntity)

    @Query("SELECT * FROM evolution_revisions WHERE domain = :domain AND targetId = :targetId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun history(domain: kotlin.String, targetId: kotlin.String, limit: Int = 50): List<EvolutionRevisionEntity>

    @Query("SELECT * FROM evolution_revisions WHERE id = :id LIMIT 1")
    suspend fun getById(id: kotlin.String): EvolutionRevisionEntity?

    @Query("SELECT * FROM evolution_revisions ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EvolutionRevisionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(revisions: List<EvolutionRevisionEntity>)

    @Query("SELECT * FROM evolution_revisions ORDER BY createdAt DESC")
    suspend fun allForBackup(): List<EvolutionRevisionEntity>

    @Query("DELETE FROM evolution_revisions")
    suspend fun deleteAll()
}

@Dao
interface EvolutionSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: EvolutionSettingsEntity)

    @Query("SELECT * FROM evolution_settings WHERE domain = :domain LIMIT 1")
    suspend fun get(domain: kotlin.String): EvolutionSettingsEntity?

    @Query("SELECT * FROM evolution_settings")
    suspend fun all(): List<EvolutionSettingsEntity>

    @Query("UPDATE evolution_settings SET autoApplyApproved = :approved, updatedAt = :timestamp WHERE domain = :domain")
    suspend fun setAutoApplyApproved(domain: kotlin.String, approved: kotlin.Boolean, timestamp: kotlin.Long = System.currentTimeMillis())

    @Query("UPDATE evolution_settings SET reflectionEnabled = :enabled, updatedAt = :timestamp WHERE domain = :domain")
    suspend fun setReflectionEnabled(domain: kotlin.String, enabled: kotlin.Boolean, timestamp: kotlin.Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(settings: List<EvolutionSettingsEntity>)

    @Query("DELETE FROM evolution_settings")
    suspend fun deleteAll()
}
