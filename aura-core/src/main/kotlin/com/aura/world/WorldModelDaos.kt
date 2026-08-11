package com.aura.world

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BeliefDao {
    @Upsert
    suspend fun upsert(belief: BeliefEntity)

    @Upsert
    suspend fun insertAll(beliefs: List<BeliefEntity>)

    @Query("DELETE FROM beliefs")
    suspend fun deleteAll()

    @Query("SELECT * FROM beliefs WHERE subject = :subject AND predicate = :predicate AND status = 'active' ORDER BY confidence DESC LIMIT 1")
    suspend fun active(subject: kotlin.String, predicate: kotlin.String): BeliefEntity?

    @Query("SELECT * FROM beliefs WHERE subject = :subject AND status = 'active' ORDER BY updatedAt DESC")
    suspend fun forSubject(subject: kotlin.String): List<BeliefEntity>

    @Query("SELECT * FROM beliefs WHERE status = 'active' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun allActive(limit: Int = 200): List<BeliefEntity>

    @Query("SELECT * FROM beliefs WHERE agentScope IN (:scopes) AND status = 'active' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun allActiveInScopes(scopes: List<String>, limit: Int = 200): List<BeliefEntity>

    @Query("SELECT * FROM beliefs WHERE id = :id LIMIT 1")
    suspend fun getById(id: kotlin.String): BeliefEntity?

    @Query("UPDATE beliefs SET status = :status, supersededBy = :supersededBy, validTo = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun supersede(id: kotlin.String, status: kotlin.String, supersededBy: kotlin.String, timestamp: kotlin.Long)

    @Query("UPDATE beliefs SET lastVerifiedAt = :timestamp, confidence = :confidence WHERE id = :id")
    suspend fun verify(id: kotlin.String, confidence: Float, timestamp: kotlin.Long)

    @Query("SELECT * FROM beliefs WHERE subject = :subject AND predicate = :predicate AND status != 'retired' ORDER BY createdAt ASC")
    suspend fun history(subject: kotlin.String, predicate: kotlin.String): List<BeliefEntity>

    @Query("SELECT * FROM beliefs ORDER BY createdAt ASC")
    suspend fun allForBackup(): List<BeliefEntity>
}

@Dao
interface EvidenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(evidence: EvidenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(evidence: List<EvidenceEntity>)

    @Query("DELETE FROM evidence")
    suspend fun deleteAll()

    @Query("SELECT * FROM evidence WHERE beliefId = :beliefId ORDER BY timestamp DESC")
    suspend fun forBelief(beliefId: kotlin.String): List<EvidenceEntity>

    @Query("SELECT * FROM evidence ORDER BY timestamp ASC")
    suspend fun allForBackup(): List<EvidenceEntity>
}

@Dao
interface WorldEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: WorldEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<WorldEventEntity>)

    @Query("DELETE FROM world_events")
    suspend fun deleteAll()

    @Query("SELECT * FROM world_events WHERE consumed = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun unconsumed(limit: Int = 100): List<WorldEventEntity>

    @Query("SELECT * FROM world_events WHERE agentScope IN (:scopes) AND consumed = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun unconsumedInScopes(scopes: List<String>, limit: Int = 100): List<WorldEventEntity>

    @Query("SELECT * FROM world_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<WorldEventEntity>>

    @Query("UPDATE world_events SET consumed = 1 WHERE id = :id")
    suspend fun markConsumed(id: kotlin.String)

    @Query("SELECT * FROM world_events ORDER BY timestamp ASC")
    suspend fun allForBackup(): List<WorldEventEntity>
}

@Dao
interface OpportunityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(opportunity: OpportunityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(opportunities: List<OpportunityEntity>)

    @Query("DELETE FROM opportunities")
    suspend fun deleteAll()

    @Query("SELECT * FROM opportunities WHERE status = 'proposed' ORDER BY urgency DESC, benefit DESC LIMIT :limit")
    fun observeProposed(limit: Int = 50): Flow<List<OpportunityEntity>>

    @Query("SELECT * FROM opportunities WHERE agentScope IN (:scopes) AND status = 'proposed' ORDER BY urgency DESC, benefit DESC LIMIT :limit")
    fun observeProposedInScopes(scopes: List<String>, limit: Int = 50): Flow<List<OpportunityEntity>>

    @Query("SELECT * FROM opportunities WHERE status = 'proposed' AND (snoozeUntil = 0 OR snoozeUntil <= :now) ORDER BY urgency DESC, benefit DESC LIMIT :limit")
    suspend fun pending(now: kotlin.Long, limit: Int = 50): List<OpportunityEntity>

    @Query("SELECT * FROM opportunities WHERE id = :id LIMIT 1")
    suspend fun getById(id: kotlin.String): OpportunityEntity?

    @Query("UPDATE opportunities SET status = :status, resolvedAt = :timestamp WHERE id = :id")
    suspend fun resolve(id: kotlin.String, status: kotlin.String, timestamp: kotlin.Long)

    @Query("UPDATE opportunities SET status = 'snoozed', snoozeUntil = :until WHERE id = :id")
    suspend fun snooze(id: kotlin.String, until: kotlin.Long)

    @Query("SELECT * FROM opportunities ORDER BY createdAt ASC")
    suspend fun allForBackup(): List<OpportunityEntity>
}