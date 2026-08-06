package com.aura.evolution

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Separate Room database for evolution-specific state. Keeping this out of
 * AgentRunDatabase avoids bloating execution history with long-lived
 * reflection/candidate/proposal data, and lets evolution tables evolve on
 * their own release cycle.
 */
@Database(
    entities = [
        EvolutionEvidenceEntity::class,
        EvolutionCandidateEntity::class,
        EvolutionProposalEntity::class,
        EvolutionRevisionEntity::class,
        EvolutionSettingsEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(EvolutionTypeConverters::class)
abstract class EvolutionDatabase : RoomDatabase() {
    abstract fun evidenceDao(): EvolutionEvidenceDao
    abstract fun candidateDao(): EvolutionCandidateDao
    abstract fun proposalDao(): EvolutionProposalDao
    abstract fun revisionDao(): EvolutionRevisionDao
    abstract fun settingsDao(): EvolutionSettingsDao
}
