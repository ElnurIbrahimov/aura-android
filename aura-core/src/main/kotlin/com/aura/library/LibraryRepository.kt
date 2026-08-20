package com.aura.library

import com.aura.creative.CreativeArtifactDao
import com.aura.documents.DocumentDao
import com.aura.media.GeneratedMediaDao
import javax.inject.Inject
import javax.inject.Singleton

/** The coarse categories the Library groups by. The producers' own `kind` survives in [LibraryItem.subtitle]. */
enum class LibraryKind { IMAGE, DOCUMENT, WRITING, OTHER }

/** Which store an item came from, so the UI knows where tapping it should go. */
enum class LibrarySource { GENERATED_MEDIA, DOCUMENT, CREATIVE_ARTIFACT }

/**
 * One row in the Library: enough to render and to open, and nothing else.
 *
 * Deliberately not the producing entity. Each of those carries fields only its own feature
 * understands — revisions, chunk counts, content hashes — and a list that took the union of
 * all of them would grow a field every time a producer did.
 */
data class LibraryItem(
    val id: String,
    val kind: LibraryKind,
    val title: String,
    val subtitle: String,
    val createdAt: Long,
    val source: LibrarySource,
    /** A `file://` or remote URI for anything that can render a thumbnail. Null otherwise. */
    val previewUri: String? = null,
)

/**
 * Everything Aura has made, in one list, newest first.
 *
 * A read-only union over the three stores that already hold produced things, rather than a
 * new `artifacts` table that every producer would also have to write to. Each of those
 * stores persists correctly and carries richer per-type fields than a shared table could;
 * a second table would mean dual writes at three call sites and two records free to
 * disagree about the same object. The Library is a view, and views cannot drift.
 *
 * Creative *analyses* are not here on purpose. A tension report is an analysis of an
 * artifact rather than a thing in its own right, it has no listable query — it is keyed by
 * revision — and it belongs beside the artifact it describes.
 *
 * Conversations are not here either. They have History. This is what Aura made, not what
 * was said.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val generatedMediaDao: GeneratedMediaDao,
    private val documentDao: DocumentDao,
    private val creativeArtifactDao: CreativeArtifactDao,
) {

    /**
     * @param kind when given, only items in that category.
     *
     * Each source is read inside its own `runCatching`. Three reads are three chances to
     * throw, and a Library that shows nothing because one table is locked is worse than one
     * that shows the other two — the whole point is that things stop disappearing.
     */
    suspend fun all(kind: LibraryKind? = null, limit: Int = 500): List<LibraryItem> {
        val items = buildList {
            addAll(read("generated media") { media() })
            addAll(read("documents") { documents() })
            addAll(read("creative artifacts") { artifacts() })
        }
        return items
            .let { all -> if (kind == null) all else all.filter { it.kind == kind } }
            // One timeline across all three. Sorting inside each source and concatenating
            // would put every image above every document regardless of when either was made.
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    private suspend fun read(label: String, block: suspend () -> List<LibraryItem>): List<LibraryItem> =
        runCatching { block() }
            .onFailure { android.util.Log.w(TAG, "library source '$label' failed: ${it.message}", it) }
            .getOrDefault(emptyList())

    private suspend fun media(): List<LibraryItem> =
        generatedMediaDao.recent(MEDIA_LIMIT).map { row ->
            LibraryItem(
                id = row.id,
                kind = LibraryKind.IMAGE,
                // The prompt, because it is the only description of a generated image the
                // user will recognise. A filename of a UUID is not one.
                title = row.prompt.ifBlank { "Untitled image" },
                subtitle = if (row.isLocal) "Image" else "Image — hosted, may expire",
                createdAt = row.createdAt,
                source = LibrarySource.GENERATED_MEDIA,
                previewUri = row.storageUri,
            )
        }

    private suspend fun documents(): List<LibraryItem> =
        documentDao.allForBackup().map { row ->
            LibraryItem(
                id = row.id,
                kind = LibraryKind.DOCUMENT,
                title = row.name,
                subtitle = "Document — ${row.chunkCount} chunk(s)",
                createdAt = row.importedAt,
                source = LibrarySource.DOCUMENT,
            )
        }

    private suspend fun artifacts(): List<LibraryItem> =
        creativeArtifactDao.allForBackup().map { row ->
            LibraryItem(
                id = row.id,
                kind = writingKind(row.kind),
                title = row.title.ifBlank { "Untitled" },
                // The producer's own kind, kept verbatim. `kind` is caller-supplied, so the
                // set is open — categorising into OTHER must not lose what it actually was.
                subtitle = row.kind.ifBlank { "Artifact" },
                createdAt = row.createdAt,
                source = LibrarySource.CREATIVE_ARTIFACT,
            )
        }

    private fun writingKind(raw: String): LibraryKind =
        if (raw.lowercase() in WRITING_KINDS) LibraryKind.WRITING else LibraryKind.OTHER

    private companion object {
        const val TAG = "LibraryRepository"

        /**
         * The artifact kinds that are prose. Anything else lands in OTHER *and keeps its
         * own name in the subtitle*, so an unrecognised kind is still listed — a Library
         * that silently drops what it does not recognise recreates the problem it exists
         * to solve.
         */
        val WRITING_KINDS = setOf("scene", "manuscript", "chapter", "outline", "draft")

        /** Generous: the Library is browsed rarely and a truncated one is a lying one. */
        const val MEDIA_LIMIT = 500
    }
}
