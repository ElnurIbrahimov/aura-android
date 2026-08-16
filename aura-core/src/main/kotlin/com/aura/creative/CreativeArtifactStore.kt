package com.aura.creative

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Domain store for [CreativeArtifactEntity]. Wraps the DAO with
 * project-scoped mutex, status transitions, and soft-delete (archive).
 */
@Singleton
class CreativeArtifactStore @Inject constructor(
    private val artifactDao: CreativeArtifactDao,
    private val revisionDao: CreativeRevisionDao,
    private val branchDao: CreativeBranchDao,
) {
    private val projectMutex = Mutex()

    /**
     * Clear artifact pointers left dangling by the cascade that used to delete
     * revisions, and count what could not be recovered.
     *
     * `creative_artifacts` was written with `INSERT OR REPLACE` while
     * `creative_revisions` declares `ON DELETE CASCADE` against it, so
     * [addRevision] wrote a revision and then re-saved the artifact to point at
     * it — deleting every revision of that artifact, including the one written
     * a line earlier, and re-inserting the artifact with a `currentRevisionId`
     * that resolved to nothing. The DAO no longer cascades, but the artifacts
     * that survived that period still carry the broken pointer, and a pointer
     * into an empty table renders as an artifact whose content will not open.
     *
     * Repair rather than delete: the artifact row is the only surviving record
     * that the work existed. Where a revision survives, the newest becomes
     * current and `previewText` is re-derived from it. Where none does, the
     * pointer is cleared and the status set to "failed" so the Creative screens
     * can say so instead of failing to load — a visible orphan being the same
     * choice `LongformRunner` already makes when a run dies mid-commit.
     *
     * Idempotent: an artifact whose pointer resolves is not touched, so this is
     * safe to run on every startup and costs one query per artifact once.
     *
     * @return how many artifacts were repaired, and how many had no surviving
     * revision at all.
     */
    suspend fun repairDanglingRevisionPointers(): RepairReport = projectMutex.withLock {
        var repointed = 0
        var orphaned = 0
        for (artifact in artifactDao.allForBackup()) {
            val pointer = artifact.currentRevisionId
            if (pointer != null && revisionDao.getById(pointer) != null) continue

            val surviving = revisionDao.forArtifact(artifact.id).maxByOrNull { it.createdAt }
            if (surviving != null) {
                artifactDao.upsert(
                    artifact.copy(
                        currentRevisionId = surviving.id,
                        previewText = surviving.contentText.take(PREVIEW_CHARS),
                    ),
                )
                repointed++
            } else if (pointer != null) {
                artifactDao.upsert(artifact.copy(currentRevisionId = null, status = "failed"))
                orphaned++
            }
        }
        RepairReport(repointed = repointed, orphaned = orphaned)
    }

    /** Outcome of [repairDanglingRevisionPointers]. */
    data class RepairReport(val repointed: Int, val orphaned: Int) {
        val touched: Int get() = repointed + orphaned
    }

    suspend fun create(
        projectId: String,
        branchId: String,
        kind: String,
        title: String,
        initialContent: String = "",
        authorKind: String = "manual",
        providerPrefix: String = "",
        modelId: String = "",
        prompt: String = "",
    ): CreativeArtifactEntity = projectMutex.withLock {
        val artifactId = UUID.randomUUID().toString()
        val revisionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val contentHash = sha256(initialContent)

        // Artifact first, revision second. CreativeRevisionEntity declares a
        // foreign key on artifactId -> creative_artifacts.id, so inserting the
        // revision first violates it and SQLite rejects the whole call with
        // SQLITE_CONSTRAINT_FOREIGNKEY. That is what this did, on every call,
        // for as long as it has existed: no artifact has ever been written to a
        // real database by this method. It went unnoticed because the test
        // mocks both DAOs, and a mocked DAO enforces no constraints — so the
        // one thing that could fail was the one thing removed.
        //
        // `currentRevisionId` pointing at a row that does not exist yet is fine:
        // it carries no foreign key, deliberately, because an artifact has to be
        // able to name its first revision before that revision can name it back.
        val artifact = CreativeArtifactEntity(
            id = artifactId,
            projectId = projectId,
            branchId = branchId,
            kind = kind,
            title = title,
            currentRevisionId = revisionId,
            previewText = initialContent.take(PREVIEW_CHARS),
            status = "ready",
            createdAt = now,
            updatedAt = now,
        )
        artifactDao.upsert(artifact)

        val revision = CreativeRevisionEntity(
            id = revisionId,
            artifactId = artifactId,
            branchId = branchId,
            parentRevisionId = null,
            contentText = initialContent,
            contentHash = contentHash,
            authorKind = authorKind,
            providerPrefix = providerPrefix,
            modelId = modelId,
            prompt = prompt,
            createdAt = now,
        )
        revisionDao.upsert(revision)
        artifact
    }

    suspend fun revise(
        artifactId: String,
        content: String,
        parentRevisionId: String? = null,
        authorKind: String = "manual",
        providerPrefix: String = "",
        modelId: String = "",
        prompt: String = "",
    ): CreativeRevisionEntity = projectMutex.withLock {
        val artifact = artifactDao.getById(artifactId)
            ?: error("Artifact $artifactId not found")
        val effectiveParent = parentRevisionId ?: artifact.currentRevisionId
        val revisionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val revision = CreativeRevisionEntity(
            id = revisionId,
            artifactId = artifactId,
            branchId = artifact.branchId,
            parentRevisionId = effectiveParent,
            contentText = content,
            contentHash = sha256(content),
            authorKind = authorKind,
            providerPrefix = providerPrefix,
            modelId = modelId,
            prompt = prompt,
            createdAt = now,
        )
        revisionDao.upsert(revision)
        artifactDao.upsert(artifact.copy(
            currentRevisionId = revisionId,
            previewText = content.take(PREVIEW_CHARS),
            updatedAt = now,
        ))
        revision
    }

    suspend fun get(id: String): CreativeArtifactEntity? = artifactDao.getById(id)

    suspend fun forProject(projectId: String): List<CreativeArtifactEntity> =
        artifactDao.allForProject(projectId)

    suspend fun forProjectByKind(projectId: String, kind: String): List<CreativeArtifactEntity> =
        artifactDao.forProjectByKind(projectId, kind)

    suspend fun archive(id: String) {
        val artifact = artifactDao.getById(id) ?: return
        artifactDao.upsert(artifact.copy(status = "archived", updatedAt = System.currentTimeMillis()))
    }

    suspend fun restore(id: String) {
        val artifact = artifactDao.getById(id) ?: return
        artifactDao.upsert(artifact.copy(status = "ready", updatedAt = System.currentTimeMillis()))
    }

    suspend fun lineage(revisionId: String): List<String> =
        revisionDao.ancestryChain(revisionId)

    suspend fun revisionsForArtifact(artifactId: String): List<CreativeRevisionEntity> =
        revisionDao.forArtifact(artifactId)

    /**
     * The artifact's current text, resolved through its `currentRevisionId`.
     *
     * Use this rather than picking an entry out of [revisionsForArtifact].
     * `CreativeRevisionDao.forArtifact` is `ORDER BY createdAt DESC`, so the
     * newest revision is first and `lastOrNull()` — which is what
     * `CreativeEngine` was calling — returns the **oldest**. Harmless while
     * nothing revised anything, since every artifact had exactly one revision;
     * actively wrong the moment a manuscript starts accumulating them, where it
     * would feed the model scene one forever.
     *
     * Resolving by id is also immune to same-millisecond `createdAt` ties, which
     * a loop writing scenes in quick succession will produce and which any
     * ordering-based answer would resolve arbitrarily.
     */
    suspend fun currentContent(artifactId: String): String? {
        val artifact = artifactDao.getById(artifactId) ?: return null
        return currentRevision(artifactId)?.contentText
            ?: artifact.previewText.takeIf { it.isNotBlank() }
    }

    /**
     * The revision row [currentContent] resolves through, without its fallback.
     *
     * Null means the text genuinely cannot be recovered — the artifact is gone,
     * its pointer is null, or its pointer names a row that no longer exists.
     * All three collapse to the same answer here, deliberately: a caller cannot
     * tell them apart from the outside and, more to the point, should not have
     * to. Testing `currentRevisionId == null` looks like it distinguishes the
     * recoverable case and does not — a **non-null** pointer to a deleted row
     * takes [currentContent]'s fallback too, which is what
     * `CreativeArtifactCurrentContentTest` has asserted since before this method
     * existed.
     *
     * That distinction matters to exactly one kind of caller: one for which
     * `previewText` — the first 200 characters — would be worse than nothing.
     * The manuscript exporter is that caller. A 200-character stub silently
     * placed mid-novel reads as a finished scene; a gap the document admits to
     * can be fixed. Callers happy with a preview should keep using
     * [currentContent], whose behaviour is unchanged.
     */
    suspend fun currentRevision(artifactId: String): CreativeRevisionEntity? {
        val artifact = artifactDao.getById(artifactId) ?: return null
        val revisionId = artifact.currentRevisionId ?: return null
        return revisionDao.getById(revisionId)
    }

    fun observeForProject(projectId: String) = artifactDao.observeForProject(projectId)

    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Length of the list-card preview. Was three separate literal 200s. */
        const val PREVIEW_CHARS = 200
    }
}