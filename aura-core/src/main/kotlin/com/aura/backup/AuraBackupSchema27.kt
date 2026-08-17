package com.aura.backup

import com.aura.projects.ProjectEntity
import com.aura.projects.ProjectNoteEntity
import kotlinx.serialization.Serializable

/**
 * Backup types added in schema v27 — projects and their ledger.
 *
 * This is the one table in the export whose loss would be silent rather than
 * noisy. A dropped memory is one fact among thousands; a dropped `project_notes`
 * row is the *only* record that a decision was ever taken, because the ledger
 * exists precisely because nothing else in Aura writes down "decided" or
 * "blocked". Restoring a device without it would leave every project reading as
 * brand new while the conversations that built it are all still there.
 *
 * [ProjectNoteBackup] carries superseded and resolved rows as well as active
 * ones. That is not completeness for its own sake: the value of the ledger is
 * that a decision displaced another one on a date, and exporting only the
 * current row would restore a project that has never changed its mind — which
 * is a different and less useful history than the one being backed up.
 *
 * Row ids **are** carried here, unlike `place_visits`, because
 * [ProjectNoteEntity.supersededBy] points at another row by id. A restore that
 * reassigned ids would have to rewrite those pointers, and any it missed would
 * turn into a superseded row pointing at nothing — the audit trail silently
 * losing its links while looking intact.
 */

// ── Projects ──────────────────────────────────────────────────────────────

@Serializable
data class ProjectBackup(
    val id: String,
    val name: String,
    val description: String = "",
    val status: String = ProjectEntity.STATUS_ACTIVE,
    val lastTurnAt: Long = 0L,
    val turnCount: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class ProjectNoteBackup(
    val id: String,
    val projectId: String,
    val kind: String,
    val subject: String,
    val body: String,
    val sourceConversationId: String = "",
    val sourceTurnAt: Long = 0L,
    val state: String = ProjectNoteEntity.STATE_ACTIVE,
    /** Points at another row in this same list. See the file KDoc on ids. */
    val supersededBy: String? = null,
    val resolvedAt: Long? = null,
    val createdAt: Long = 0L,
)

internal fun ProjectEntity.toBackup() = ProjectBackup(
    id = id,
    name = name,
    description = description,
    status = status,
    lastTurnAt = lastTurnAt,
    turnCount = turnCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ProjectBackup.toEntity() = ProjectEntity(
    id = id,
    name = name,
    description = description,
    status = status,
    lastTurnAt = lastTurnAt,
    turnCount = turnCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ProjectNoteEntity.toBackup() = ProjectNoteBackup(
    id = id,
    projectId = projectId,
    kind = kind,
    subject = subject,
    body = body,
    sourceConversationId = sourceConversationId,
    sourceTurnAt = sourceTurnAt,
    state = state,
    supersededBy = supersededBy,
    resolvedAt = resolvedAt,
    createdAt = createdAt,
)

internal fun ProjectNoteBackup.toEntity() = ProjectNoteEntity(
    id = id,
    projectId = projectId,
    kind = kind,
    subject = subject,
    body = body,
    sourceConversationId = sourceConversationId,
    sourceTurnAt = sourceTurnAt,
    state = state,
    supersededBy = supersededBy,
    resolvedAt = resolvedAt,
    createdAt = createdAt,
)
