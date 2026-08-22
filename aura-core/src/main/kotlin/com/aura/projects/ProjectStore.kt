package com.aura.projects

import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only writer for `projects` and `project_notes`.
 *
 * Two invariants live here rather than in the schema, because SQLite cannot
 * express either through Room's `@Index`:
 *
 * 1. **At most one active note per `(projectId, kind, subject)`.** That needs a
 *    partial unique index; [recordNote] maintains it instead.
 * 2. **A note's `subject` is never blank.** A blank key would put every note for
 *    a project onto one supersession bucket, which is `SceneLedger`'s
 *    already-paid-for lesson — a blank id there collapsed every scene's canon
 *    onto one row and silently switched contradiction detection off. Refused
 *    here rather than defaulted, so the failure is a log line and a `false`
 *    instead of a ledger that looks populated and is wrong.
 */
@Singleton
class ProjectStore @Inject constructor(
    private val projectDao: ProjectDao,
    private val noteDao: ProjectNoteDao,
) {

    fun observeActive(): Flow<List<ProjectEntity>> = projectDao.observeActive()

    fun observeAll(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    suspend fun active(limit: Int = 50): List<ProjectEntity> = projectDao.active(limit)

    suspend fun get(id: String): ProjectEntity? = projectDao.byId(id)

    suspend fun byName(name: String): ProjectEntity? =
        name.trim().takeIf { it.isNotEmpty() }?.let { projectDao.byName(it) }

    /**
     * Create a project, or return the one that already has this name.
     *
     * Idempotent rather than throwing, because `name` carries a unique index and
     * every caller — the picker, the onboarding path, a restored backup — would
     * otherwise need its own collision handling. Returns null only when the name
     * is empty, which is the one case that is a caller bug rather than a race.
     */
    suspend fun create(name: String, description: String = ""): ProjectEntity? {
        val clean = name.trim()
        if (clean.isEmpty()) {
            Log.w(TAG, "refusing to create a project with a blank name")
            return null
        }
        projectDao.byName(clean)?.let { return it }
        val now = System.currentTimeMillis()
        val row = ProjectEntity(
            id = UUID.randomUUID().toString(),
            name = clean,
            description = description.trim(),
            createdAt = now,
            updatedAt = now,
        )
        return runCatching { projectDao.upsert(row); row }
            .onFailure { Log.w(TAG, "could not create project '$clean': ${it.message}", it) }
            // A unique-index collision means somebody else created it between the
            // read above and this write. Re-read rather than surfacing the race.
            .getOrElse { projectDao.byName(clean) }
    }

    suspend fun setStatus(id: String, status: String): Boolean {
        if (status !in ProjectEntity.STATUSES) {
            Log.w(TAG, "refusing unknown project status '$status'")
            return false
        }
        projectDao.setStatus(id, status, System.currentTimeMillis())
        return true
    }

    /** Attribute a turn. Targeted UPDATE — see [ProjectDao]'s KDoc on cascades. */
    suspend fun touch(id: String, at: Long = System.currentTimeMillis()) = projectDao.touch(id, at)

    suspend fun delete(id: String) = projectDao.delete(id)

    // ── Ledger ───────────────────────────────────────────────────────────

    suspend fun activeNotes(projectId: String, limit: Int = 100): List<ProjectNoteEntity> =
        noteDao.activeFor(projectId, limit)

    /** The vocabulary the extractor should prefer, so subjects converge. */
    suspend fun activeSubjects(projectId: String): List<String> = noteDao.activeSubjects(projectId)

    suspend fun historyFor(projectId: String, kind: String, subject: String): List<ProjectNoteEntity> =
        noteDao.historyFor(projectId, kind, subject)

    /**
     * Record one thing about a project, superseding whatever it displaces.
     *
     * **Insert first, supersede second, and deliberately not one transaction** —
     * `SceneLedger.reconcile`'s ordering, for its reason. This runs on a worker
     * with nobody watching, so being killed midway is an expected condition, not
     * an exceptional one. Superseding first would retire the old note and then
     * die before the replacement landed: the subject would read as having no
     * active row, the next pass would find nothing to supersede, and the history
     * would be gone. This order fails toward *two* active rows instead — visible
     * in the ledger, and healed on the next write because [ProjectNoteDao.activeBySubject]
     * returns all of them. Absence cannot be recovered; duplication can.
     *
     * @return the note written, or null if it was refused.
     */
    suspend fun recordNote(
        projectId: String,
        kind: String,
        subject: String,
        body: String,
        sourceConversationId: String,
        sourceTurnAt: Long,
    ): ProjectNoteEntity? {
        val cleanSubject = subject.trim().lowercase()
        if (cleanSubject.isEmpty()) {
            // The invariant this class exists to hold. See the class KDoc.
            Log.w(TAG, "refusing a $kind note with no subject; it would supersede indiscriminately")
            return null
        }
        if (kind !in ProjectNoteEntity.KINDS) {
            Log.w(TAG, "refusing note of unknown kind '$kind'")
            return null
        }
        val cleanBody = body.trim()
        if (cleanBody.isEmpty()) {
            Log.w(TAG, "refusing a $kind note with an empty body")
            return null
        }
        if (projectDao.byId(projectId) == null) {
            // The foreign key would reject this anyway; refusing here makes it a
            // log line rather than a constraint exception on a background worker.
            Log.w(TAG, "refusing a note for unknown project $projectId")
            return null
        }

        val displaced = noteDao.activeBySubject(projectId, kind, cleanSubject)
        val note = ProjectNoteEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            kind = kind,
            subject = cleanSubject,
            body = cleanBody,
            sourceConversationId = sourceConversationId,
            sourceTurnAt = sourceTurnAt,
        )
        val inserted = runCatching { noteDao.insert(note) }
            .onFailure { Log.w(TAG, "could not write $kind note for $projectId: ${it.message}", it) }
            .isSuccess
        if (!inserted) return null

        for (old in displaced) {
            if (old.id == note.id) continue
            // Identical restatement: the model saw the same thing again. Nothing
            // changed, so keep the original row and its date and retire the copy.
            if (old.body.equals(cleanBody, ignoreCase = true)) {
                noteDao.supersede(note.id, old.id)
                return old
            }
            runCatching { noteDao.supersede(old.id, note.id) }
                .onFailure { Log.w(TAG, "could not supersede note ${old.id}: ${it.message}", it) }
        }
        return note
    }

    private companion object {
        const val TAG = "ProjectStore"
    }
}
