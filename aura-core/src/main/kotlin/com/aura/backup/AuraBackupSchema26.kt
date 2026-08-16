package com.aura.backup

import kotlinx.serialization.Serializable

/**
 * Backup type added in schema v26 — harvested retrieval labels.
 *
 * Included for a reason beyond the coverage rule that
 * [com.aura.backup.BackupCoverageAuditTest] enforces on every persisted entity.
 * `scripts/build_eval_corpus.py` reads a backup file and assigns each memory a
 * **positional** id (`m0001`, `m0002`, …) as it writes `corpus.jsonl`; the real
 * UUIDs deliberately never leave the device. Judgments reference memories by
 * UUID, so they have to be mapped through that same positional assignment — and
 * the only way to do that safely is in one pass over one file, while the map is
 * still in memory.
 *
 * Exporting labels through a second channel would mean joining two files
 * produced at different instants, which is precisely how a labelled memory ends
 * up absent from the corpus it is judged against. Committing the map instead
 * would leak the UUIDs the positional ids exist to keep local, and so would any
 * stable hash of them.
 *
 * [RetrievalLabelBackup.queryText] is the user's own words. It is scrubbed
 * through `Redactor` before it is ever written to the table, so what is exported
 * here is already redacted rather than redacted on the way out.
 */

// ── Retrieval labels (which memory answered which question, and how well) ──

@Serializable
data class RetrievalLabelBackup(
    val id: String,
    val conversationId: String,
    val turnTimestamp: Long,
    val queryText: String,
    val memoryId: String,
    val rank: Int,
    /** 0..3, matching `RetrievalMetrics`. Null means observed but unjudged. */
    val grade: Int? = null,
    val gradeSource: String = "",
    val heuristicGrade: Int? = null,
    val signalsJson: String = "[]",
    val sampled: Boolean = false,
    val judgedAt: Long? = null,
    val queryClass: String? = null,
    val supersededByEdit: Boolean = false,
    val createdAt: Long = 0L,
)

internal fun com.aura.memory.RetrievalLabelEntity.toBackup() = RetrievalLabelBackup(
    id = id,
    conversationId = conversationId,
    turnTimestamp = turnTimestamp,
    queryText = queryText,
    memoryId = memoryId,
    rank = rank,
    grade = grade,
    gradeSource = gradeSource,
    heuristicGrade = heuristicGrade,
    signalsJson = signalsJson,
    sampled = sampled,
    judgedAt = judgedAt,
    queryClass = queryClass,
    supersededByEdit = supersededByEdit,
    createdAt = createdAt,
)

internal fun RetrievalLabelBackup.toEntity() = com.aura.memory.RetrievalLabelEntity(
    id = id,
    conversationId = conversationId,
    turnTimestamp = turnTimestamp,
    queryText = queryText,
    memoryId = memoryId,
    rank = rank,
    grade = grade,
    gradeSource = gradeSource,
    heuristicGrade = heuristicGrade,
    signalsJson = signalsJson,
    sampled = sampled,
    judgedAt = judgedAt,
    queryClass = queryClass,
    supersededByEdit = supersededByEdit,
    createdAt = createdAt,
)
