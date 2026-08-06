package com.aura.evolution

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ForeignKey

/**
 * A recorded atomic observation that may feed an evolution proposal.
 *
 * Evidence is append-only and scoped to one of three evolution domains.
 * It stores redacted before/after ciphertext snapshots when those snapshots
 * contain user content (skill bodies, memory text, proactive payloads).
 */
@Entity(
    tableName = "evolution_evidence",
    indices = [
        Index(value = ["domain"]),
        Index(value = ["kind"]),
        Index(value = ["sourceEntityId"]),
        Index(value = ["createdAt"]),
        Index(value = ["domain", "kind", "createdAt"]),
    ],
)
data class EvolutionEvidenceEntity(
    @PrimaryKey val id: kotlin.String,
    /** SKILL, MEMORY, or PROACTIVE. */
    val domain: kotlin.String,
    /** Event kind: e.g. skill_invoked, skill_failed, memory_stored, memory_recalled, proactive_delivered, proactive_dismissed. */
    val kind: kotlin.String,
    /** The primary entity this evidence is about: skill id, memory id, proactive event id. */
    val sourceEntityId: kotlin.String,
    /** Optional conversation / run provenance. */
    val runId: kotlin.String? = null,
    val conversationId: kotlin.String? = null,
    val turnTimestamp: kotlin.Long? = null,
    /** Free-form human-readable summary, no secrets. */
    val summary: kotlin.String = "",
    /** Structured redacted payload. */
    val payloadJson: kotlin.String = "{}",
    /** AES-GCM ciphertext of the relevant 'before' content when mutation is proposed. */
    val beforeCiphertext: kotlin.String? = null,
    /** AES-GCM ciphertext of the proposed/result 'after' content. */
    val afterCiphertext: kotlin.String? = null,
    /** Epoch ms. */
    val createdAt: kotlin.Long = System.currentTimeMillis(),
)

/**
 * A candidate evolution item produced by deterministic heuristics.
 *
 * Candidates are lightweight and may be promoted to a full Proposal after
 * optional LLM reflection. They are intentionally separate from proposals so
 * that the system can collect many cheap signals without creating heavy
 * reflection work for each one.
 */
@Entity(
    tableName = "evolution_candidates",
    indices = [
        Index(value = ["domain"]),
        Index(value = ["status"]),
        Index(value = ["score"]),
        Index(value = ["createdAt"]),
        Index(value = ["domain", "status", "score"]),
        // D5 dedup key — no unique constraint (the detector enforces the
        // one-row-per-key invariant via findByKey; a unique index would make
        // the v3→v4 migration hazardous for installs with historic dups).
        Index(value = ["domain", "action", "targetId"]),
    ],
)
data class EvolutionCandidateEntity(
    @PrimaryKey val id: kotlin.String,
    val domain: kotlin.String,
    /** One of EvolutionAction. */
    val action: kotlin.String,
    /** Primary entity id the action targets. */
    val targetId: kotlin.String,
    /** JSON arguments specific to the action. */
    val argsJson: kotlin.String = "{}",
    /** Human-readable rationale. */
    val rationale: kotlin.String = "",
    /** 0.0-1.0 heuristic confidence. */
    val score: Float = 0.0f,
    /** Evidence ids that support this candidate. */
    val evidenceIdsJson: kotlin.String = "[]",
    /** pending, reflected, rejected, promoted. */
    val status: kotlin.String = CandidateStatus.PENDING.name,
    /** Reflection result text, if any. */
    val reflectionResult: kotlin.String = "",
    val createdAt: kotlin.Long = System.currentTimeMillis(),
    val updatedAt: kotlin.Long = createdAt,
)

/**
 * A user-reviewable evolution proposal.
 *
 * Proposals are created from high-confidence candidates. They carry a
 * serialized patch, an apply saga status, and rollback metadata.
 */
@Entity(
    tableName = "evolution_proposals",
    indices = [
        Index(value = ["domain"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["requiresApproval"]),
    ],
)
data class EvolutionProposalEntity(
    @PrimaryKey val id: kotlin.String,
    val domain: kotlin.String,
    val action: kotlin.String,
    val targetId: kotlin.String,
    val title: kotlin.String = "",
    val description: kotlin.String = "",
    /** Human-readable summary of the change, safe to show in UI. */
    val summary: kotlin.String = "",
    /** JSON patch describing the exact change. */
    val patchJson: kotlin.String = "{}",
    /** pending_review, approved, rejected, applied, apply_failed, rolled_back, superseded. */
    val status: kotlin.String = ProposalStatus.PENDING_REVIEW.name,
    val requiresApproval: kotlin.Boolean = true,
    /** If the user pre-approves this action domain, proposals can auto-apply. */
    val autoApply: kotlin.Boolean = false,
    /** Confidence as reported by the reflection/creator step. */
    val confidence: Float = 0.0f,
    /** Evidence ids. */
    val evidenceIdsJson: kotlin.String = "[]",
    /** Candidate ids that fed into this proposal. */
    val candidateIdsJson: kotlin.String = "[]",
    /** Apply saga status JSON: step, error, retry count. */
    val applySagaJson: kotlin.String = "{}",
    /** Rollback snapshot id or encrypted state. */
    val rollbackSnapshotJson: kotlin.String = "{}",
    /** User-facing note after apply/rollback. */
    val outcomeNote: kotlin.String = "",
    val createdAt: kotlin.Long = System.currentTimeMillis(),
    val updatedAt: kotlin.Long = createdAt,
    val resolvedAt: kotlin.Long? = null,
)

/**
 * Revision history for evolved artifacts. Each row is an immutable snapshot
 * of a skill, memory policy, or proactive rule after a successful apply.
 */
@Entity(
    tableName = "evolution_revisions",
    indices = [
        Index(value = ["domain"]),
        Index(value = ["targetId"]),
        Index(value = ["createdAt"]),
        Index(value = ["domain", "targetId", "createdAt"]),
        Index(value = ["proposalId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = EvolutionProposalEntity::class,
            parentColumns = ["id"],
            childColumns = ["proposalId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class EvolutionRevisionEntity(
    @PrimaryKey val id: kotlin.String,
    val domain: kotlin.String,
    val targetId: kotlin.String,
    val proposalId: kotlin.String? = null,
    /** Redacted summary of what changed. */
    val summary: kotlin.String = "",
    /** Encrypted ciphertext of the full artifact snapshot at this revision. */
    val snapshotCiphertext: kotlin.String = "",
    /** Public metadata extracted from the snapshot for search/display. */
    val metadataJson: kotlin.String = "{}",
    val createdAt: kotlin.Long = System.currentTimeMillis(),
)

/**
 * Domain-level user preference and operational limits.
 *
 * One row per [EvolutionDomain]. This replaces a proliferation of DataStore
 * booleans and makes backup/restore straightforward.
 */
@Entity(
    tableName = "evolution_settings",
    primaryKeys = ["domain"],
)
data class EvolutionSettingsEntity(
    val domain: kotlin.String,
    /** Master enable switch for this domain. */
    val enabled: kotlin.Boolean = true,
    /** User has pre-approved auto-apply for proposals in this domain. */
    val autoApplyApproved: kotlin.Boolean = false,
    /** User has opted into optional LLM reflection for this domain. */
    val reflectionEnabled: kotlin.Boolean = false,
    /** When enabled, approved evolutions run in shadow mode first and compare metrics. */
    val shadowEnabled: kotlin.Boolean = false,
    /** Max cloud calls per 24h window for this domain. */
    val dailyCloudCallBudget: Int = 24,
    /** Max tokens per reflection call. */
    val reflectionMaxTokens: Int = 2048,
    /** Retention days for evidence rows. */
    val evidenceRetentionDays: Int = 30,
    /** Retention days for resolved proposals. */
    val proposalRetentionDays: Int = 90,
    /** Max number of proposals kept before automatic stale cleanup. */
    val retentionCount: Int = 50,
    /** Aggregate counters (not the source of truth for detailed metrics). */
    val totalRuns: Int = 0,
    val totalCandidates: Int = 0,
    val createdAt: kotlin.Long = System.currentTimeMillis(),
    val updatedAt: kotlin.Long = createdAt,
)
