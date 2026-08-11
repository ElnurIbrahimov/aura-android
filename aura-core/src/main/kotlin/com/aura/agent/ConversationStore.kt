package com.aura.agent

import com.aura.memory.escapeLikeWildcards
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

private val convJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val EMBEDDING_BACKFILL_BATCH_SIZE = 24

@Singleton
class ConversationStore @Inject constructor(
    private val dao: ConversationDao,
    private val embedder: com.aura.memory.Embedder,
) {
    /**
     * Serialises the read-modify-write in [save].
     *
     * `save` is fired from eleven call sites with a bare `scope.launch` and no
     * ordering between them — the user message is saved as soon as it is typed,
     * the stream saves again on completion, `cancel()` saves, the media flows
     * save and then immediately trigger a send that saves again. Each one reads
     * the row, builds a whole-blob `turnsJson`, and writes it back, with a
     * network embedding call in the middle. Interleaved, that is a lost update:
     * the write that *started* first can land last and put back a turn list
     * missing the assistant's answer. The UI still showed it, because the UI
     * holds its own state — you found out when you reopened the chat from
     * History. The same interleaving let a deleted conversation come back, by
     * carrying a `deletedAt` read before the delete.
     */
    private val saveMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Persist [conversation].
     *
     * @param allowTruncation write even when the stored row has more turns than
     * this snapshot. False is the safe default and makes a lost update
     * impossible; the one caller that legitimately shrinks a conversation in
     * place is "clear conversation", which keeps the id and empties the turns,
     * and says so explicitly.
     */
    suspend fun save(conversation: Conversation, allowTruncation: Boolean = false) {
        // The embedding is computed BEFORE the lock. It is a network call, and
        // holding a mutex across it would serialise every save in the app
        // behind the slowest embedder response — the media flows save three
        // times in a row. Racing here costs a duplicate embed, never a
        // duplicate write.
        val previous = runCatching { dao.getById(conversation.id) }
            .onFailure { android.util.Log.w("ConversationStore", "save: getById failed for ${conversation.id}: ${it.message}", it) }
            .getOrNull()
        val searchText = conversationSearchText(conversation)
        val previousSearchText = previous?.let(::entitySearchText)
        val embedding = when {
            searchText.isBlank() -> null
            previous?.embedding != null && previousSearchText == searchText -> previous.embedding
            else -> runCatching {
                com.aura.memory.Embedder.toBytes(embedder.embed(searchText))
            }.onFailure { android.util.Log.w("ConversationStore", "save: embed failed for ${conversation.id}: ${it.message}", it) }
                .getOrNull()
        }
        saveMutex.withLock {
            // Re-read inside the lock. The row above was fetched before a
            // network call that may have taken seconds, and `agentId` /
            // `deletedAt` are carried from it — a stale read is how a deleted
            // conversation used to resurrect itself.
            val current = runCatching { dao.getById(conversation.id) }
                .onFailure {
                    android.util.Log.w(
                        "ConversationStore",
                        "save: re-read failed for ${conversation.id}: ${it.message}",
                        it,
                    )
                }
                .getOrNull() ?: previous

            val storedTurnCount = current?.let {
                runCatching { convJson.decodeFromString<List<Turn>>(it.turnsJson).size }
                    .onFailure { e ->
                        android.util.Log.w(
                            "ConversationStore",
                            "save: could not count stored turns for ${conversation.id}: ${e.message}",
                            e,
                        )
                    }
                    .getOrNull()
            }
            if (!allowTruncation && storedTurnCount != null && storedTurnCount > conversation.turns.size) {
                // A newer snapshot already landed. Writing this one would drop
                // the turns it does not know about — the assistant's reply, in
                // the case that prompted this guard.
                android.util.Log.i(
                    "ConversationStore",
                    "save: skipping stale snapshot for ${conversation.id} " +
                        "(${conversation.turns.size} turns vs $storedTurnCount stored)",
                )
                return@withLock
            }

            val entity = ConversationEntity(
                id = conversation.id,
                title = conversation.title,
                createdAt = conversation.createdAt,
                updatedAt = System.currentTimeMillis(),
                systemPrompt = conversation.systemPrompt,
                model = conversation.model,
                metadataJson = convJson.encodeToString(conversation.metadata),
                turnsJson = convJson.encodeToString(conversation.turns),
                embedding = embedding,
                contextSummary = conversation.contextSummary,
                summaryThroughTurn = conversation.summaryThroughTurn.coerceIn(0, conversation.turns.size),
                // Carry forward agentId from the stored row. The [Conversation]
                // domain object does not expose agentId (it's a storage-layer
                // concern), so without this lookup the agent association
                // would be silently lost on every save.
                agentId = conversation.agentId ?: current?.agentId,
                // Same reasoning for the soft-delete tombstone: a save() must
                // never resurrect a deleted conversation. If the stored
                // row was soft-deleted, preserve that state — only an
                // explicit restore() can clear it.
                deletedAt = current?.deletedAt,
            )
            dao.insert(entity)
        }
    }

    suspend fun load(id: String): Conversation? {
        val entity = dao.getById(id) ?: return null
        return entityToConversation(entity)
    }

    suspend fun mostRecent(): Conversation? {
        val entity = dao.mostRecent() ?: return null
        return entityToConversation(entity)
    }

    suspend fun recent(limit: Int = 50): List<Conversation> =
        // Show only non-deleted rows. The full recent() is kept available
        // for callers that explicitly want tombstones (e.g. the purge
        // sweep). Prefer [recentVisible] for user-facing lists.
        dao.recentVisible(limit).map { entityToConversation(it) }

    /**
     * Search across conversation title + serialized turn content. Returns
     * the most-recently-updated matches first. Like [com.aura.memory.MemoryStore.query],
     * the input is escaped (via the shared `escapeLikeWildcards` helper)
     * so SQL LIKE wildcards in the user query are treated as literal
     * characters.
     */
    suspend fun search(query: String, limit: Int = 50): List<Conversation> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return dao.searchVisible(escapeLikeWildcards(trimmed), limit).map { entityToConversation(it) }
    }

    suspend fun delete(id: String) {
        // Soft-delete: stamp the tombstone so the row is hidden from
        // the History list but still recoverable via [restore]. A
        // background sweep hard-deletes tombstones older than the
        // retention window.
        dao.softDelete(id, System.currentTimeMillis())
    }

    /** Clear a soft-delete tombstone. Returns the restored conversation, or null. */
    suspend fun restore(id: String): Conversation? {
        dao.restore(id)
        return dao.getById(id)?.let { entityToConversation(it) }
    }

    /**
     * Hard-delete tombstones older than the retention window. Returns
     * the number of rows purged. The background sweep calls this
     * periodically so the table doesn't grow forever with dead rows.
     */
    suspend fun purgeDeletedOlderThan(retentionMs: Long = ConversationEntity.SOFT_DELETE_RETENTION_MS): Int {
        val cutoff = System.currentTimeMillis() - retentionMs
        return dao.purgeDeletedBefore(cutoff)
    }

    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Rename a conversation. The new title must be non-blank and
     * not exceed 120 chars (the entity column caps it). Returns
     * true on success, false if the conversation doesn't exist
     * or the title is invalid.
     */
    suspend fun setTitle(id: String, newTitle: String): Boolean {
        val trimmed = newTitle.trim().take(120)
        if (trimmed.isBlank()) return false
        val entity = dao.getById(id) ?: return false
        val updated = entity.copy(title = trimmed, updatedAt = System.currentTimeMillis())
        dao.insert(updated)  // INSERT OR REPLACE — see ConversationDao
        return true
    }

    /**
     * Pin or unpin a conversation. Pinned conversations sort to
     * the top of the History list so the user can find them
     * without scrolling. Pin state lives in the metadata JSON
     * column under the key "pinned" — adding a typed column
     * would have required a Room migration.
     */
    suspend fun setPinned(id: String, pinned: Boolean): Boolean {
        val entity = dao.getById(id) ?: return false
        val metadata = runCatching {
            convJson.decodeFromString<Map<String, String>>(entity.metadataJson)
        }.onFailure { android.util.Log.w("ConversationStore", "setPinned: corrupt metadataJson for ${entity.id}: ${it.message}", it) }
            .getOrElse { emptyMap() }
        val updated = metadata.toMutableMap().apply {
            if (pinned) put("pinned", "true") else remove("pinned")
        }
        dao.insert(entity.copy(
            metadataJson = convJson.encodeToString(updated),
            updatedAt = System.currentTimeMillis(),
        ))
        return true
    }

    /**
     * Read the pinned flag from a conversation's metadata. Returns
     * false for conversations without the flag (default = unpinned).
     */
    fun isPinned(conv: Conversation): Boolean =
        conv.metadata["pinned"] == "true"

    /**
     * Set or clear the project tag on a conversation. Projects are stored
     * in conversation metadata — no Room migration needed.
     */
    suspend fun setProject(id: String, project: String?): Boolean {
        val entity = dao.getById(id) ?: return false
        val metadata = runCatching {
            convJson.decodeFromString<Map<String, String>>(entity.metadataJson)
        }.onFailure { Log.w("ConvStore", "op failed: ${it.message}", it) }.getOrElse { emptyMap() }.toMutableMap()
        if (project.isNullOrBlank()) metadata.remove("project")
        else metadata["project"] = project
        dao.insert(entity.copy(
            metadataJson = convJson.encodeToString(metadata),
            updatedAt = System.currentTimeMillis(),
        ))
        return true
    }

    /**
     * Extract the project tag from a conversation. Null if untagged.
     */
    fun projectOf(conv: Conversation): String? =
        conv.metadata["project"]?.takeIf { it.isNotBlank() }

    /**
     * Return all unique project names from the conversation list.
     */
    suspend fun allProjects(): List<String> {
        return recent(limit = 200).mapNotNull { projectOf(it) }.distinct()
    }

    /**
     * Recent conversations with pinned ones sorted to the top.
     * The list is otherwise ordered by updatedAt desc, same as
     * [recent]. Pinned items also have a stable insertion order
     * (most recently pinned first) because their updatedAt is
     * bumped when pinned.
     */
    suspend fun recentPinnedFirst(limit: Int = 50): List<Conversation> =
        recent(limit).sortedByDescending { isPinned(it) }

    /**
     * Semantic search across conversations. Embeds the query and
     * compares against conversation embeddings (lazy-populated from
     * the last user message). Falls back to SQL LIKE search if no
     * conversations have embeddings yet.
     *
     * The embedding scan is O(n) over conversations with embeddings.
     * For personal use (hundreds of conversations) this is fast.
     */
    suspend fun semanticSearch(query: String, limit: Int = 20): List<Conversation> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        backfillMissingEmbeddings()
        val existing = runCatching { dao.allWithEmbeddings() }
            .onFailure { android.util.Log.w("ConversationStore", "semanticSearch: allWithEmbeddings failed: ${it.message}", it) }
            .getOrDefault(emptyList())
        if (existing.isEmpty()) {
            // No embeddings yet — fall back to LIKE search.
            return search(trimmed, limit)
        }

        val queryEmbedding = runCatching { embedder.embed(trimmed) }
            .onFailure { android.util.Log.w("ConversationStore", "semanticSearch: embed failed: ${it.message}", it) }
            .getOrNull()
            ?: return search(trimmed, limit)

        // Rank conversations by cosine similarity to the query.
        //
        // NOT guarded against a stale embedding model, unlike MemoryStore and
        // DreamConsolidator. `ConversationEntity` has no `embeddingModel`
        // column, so there is nothing to compare against — adding one is a Room
        // migration and belongs in its own change.
        //
        // The exposure is real but bounded. After an embedding-model switch,
        // conversation search ranks by a meaningless cosine until these rows
        // are rewritten, which happens on the next update since the embedding
        // is recomputed whenever `searchText` changes. Nothing is persisted
        // from the ranking, so the damage is a bad ordering rather than a
        // corrupted corpus — the opposite of the dream-clustering path. A
        // dimension change degrades gracefully, because `cosineSimilarity`
        // returns 0 on a size mismatch; a same-dimension swap does not, and
        // every credible small model is 384.
        data class Scored(val conv: ConversationEntity, val score: Float)
        val scored = existing.mapNotNull { entity ->
            val emb = entity.embedding ?: return@mapNotNull null
            val score = cosineSimilarity(queryEmbedding, com.aura.memory.Embedder.fromBytes(emb))
            if (score > 0.05f) Scored(entity, score) else null
        }.sortedByDescending { it.score }.take(limit)

        return scored.map { entityToConversation(it.conv) }
    }

    /**
     * Fork a conversation from a specific turn index. Creates a new
     * conversation with a new ID, copying turns 0..fromTurnIndex
     * (inclusive). The original is untouched. The fork's title is
     * "{original title} (fork)" and it inherits the system prompt +
     * model. Used by the "Fork from here" action in ChatScreen.
     */
    /**
     * Toggle the pinned state of a specific turn within a conversation.
     * Pinned turns are highlighted in the UI for quick reference.
     */
    suspend fun toggleTurnPin(id: kotlin.String, turnIndex: Int): Boolean {
        val entity = dao.getById(id) ?: return false
        val turns = runCatching {
            convJson.decodeFromString<List<Turn>>(entity.turnsJson)
        }.onFailure { Log.w("ConversationStore", "runCatching failed: ${it.message}", it) }.getOrElse { return false }
        if (turnIndex !in turns.indices) return false
        val updated = turns.toMutableList()
        updated[turnIndex] = updated[turnIndex].copy(pinned = !updated[turnIndex].pinned)
        dao.updateTurns(id, convJson.encodeToString(updated), System.currentTimeMillis())
        return true
    }

    suspend fun fork(id: String, fromTurnIndex: Int): kotlin.String? {
        val original = dao.getById(id) ?: return null
        val metadata = runCatching {
            convJson.decodeFromString<Map<String, String>>(original.metadataJson)
        }.onFailure { android.util.Log.w("ConversationStore", "fork: corrupt metadataJson for ${original.id}: ${it.message}", it) }
            .getOrElse { emptyMap() }
        val allTurns = runCatching {
            convJson.decodeFromString<List<Turn>>(original.turnsJson)
        }.onFailure { android.util.Log.w("ConversationStore", "fork: corrupt turnsJson for ${original.id}: ${it.message}", it) }
            .getOrElse { emptyList() }
        if (fromTurnIndex !in allTurns.indices) return null
        val forkedTurns = allTurns.take(fromTurnIndex + 1)
        val forkId = java.util.UUID.randomUUID().toString()
        val forkTitle = "${original.title} (fork)"
        val forkTurnCount = fromTurnIndex + 1
        val forkEmbedding = searchText(forkedTurns, forkTitle).takeIf { it.isNotBlank() }?.let { text ->
            runCatching {
                com.aura.memory.Embedder.toBytes(embedder.embed(text))
            }.onFailure { android.util.Log.w("ConversationStore", "fork: embed failed for $forkId: ${it.message}", it) }
                .getOrNull()
        }
        val canReuseSummary = original.contextSummary.isNotBlank() &&
            original.summaryThroughTurn in 1..forkTurnCount
        dao.insert(ConversationEntity(
            id = forkId,
            title = forkTitle,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            systemPrompt = original.systemPrompt,
            model = original.model,
            metadataJson = convJson.encodeToString(metadata),
            turnsJson = convJson.encodeToString(forkedTurns),
            embedding = forkEmbedding,
            contextSummary = if (canReuseSummary) original.contextSummary else "",
            summaryThroughTurn = if (canReuseSummary) original.summaryThroughTurn else 0,
        ))
        return forkId
    }

    private suspend fun backfillMissingEmbeddings(limit: Int = EMBEDDING_BACKFILL_BATCH_SIZE): Int {
        val pending = runCatching { dao.missingEmbeddings(limit) }
            .onFailure { android.util.Log.w("ConversationStore", "backfill: missingEmbeddings query failed: ${it.message}", it) }
            .getOrDefault(emptyList())
        var rebuilt = 0
        for (entity in pending) {
            val text = entitySearchText(entity)
            if (text.isBlank()) continue
            val bytes = runCatching {
                com.aura.memory.Embedder.toBytes(embedder.embed(text))
            }.onFailure { android.util.Log.w("ConversationStore", "backfill: embed failed for ${entity.id}: ${it.message}", it) }
                .getOrNull() ?: continue
            if (runCatching { dao.updateEmbedding(entity.id, bytes) }
                .onFailure { android.util.Log.w("ConversationStore", "backfill: updateEmbedding failed for ${entity.id}: ${it.message}", it) }
                .isSuccess) {
                rebuilt += 1
            }
        }
        return rebuilt
    }

    private fun conversationSearchText(conversation: Conversation): String =
        searchText(conversation.turns, conversation.title)

    private fun entitySearchText(entity: ConversationEntity): String {
        val turns = runCatching {
            convJson.decodeFromString<List<Turn>>(entity.turnsJson)
        }.onFailure { android.util.Log.w("ConversationStore", "entitySearchText: corrupt turnsJson for ${entity.id}: ${it.message}", it) }
            .getOrDefault(emptyList())
        return searchText(turns, entity.title)
    }

    private fun searchText(turns: List<Turn>, title: String): String =
        turns.asReversed().firstNotNullOfOrNull { turn ->
            turn.user?.trim()?.takeIf { it.isNotEmpty() }
        } ?: title.trim()

    private fun entityToConversation(e: ConversationEntity): Conversation {
        // runCatching here is a guard against a corrupted row. The
        // entity columns are typed, but metadataJson and turnsJson are
        // free-form text that may have been written by an older app
        // version with a different schema, or corrupted by a partial
        // disk write. We log and fall through to the empty default
        // rather than throwing — the user should still be able to see
        // their conversation title even if a turn is unrecoverable.
        val metadata: Map<String, String> = runCatching {
            convJson.decodeFromString<Map<String, String>>(e.metadataJson)
        }.getOrElse {
            android.util.Log.w("ConversationStore", "corrupt metadataJson for conv ${e.id}: ${it.message}", it)
            emptyMap()
        }

        val turns: List<Turn> = runCatching {
            convJson.decodeFromString<List<Turn>>(e.turnsJson)
        }.getOrElse {
            android.util.Log.w("ConversationStore", "corrupt turnsJson for conv ${e.id}: ${it.message}", it)
            emptyList()
        }

        return Conversation(
            id = e.id,
            title = e.title,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
            systemPrompt = e.systemPrompt,
            model = e.model,
            contextSummary = e.contextSummary,
            summaryThroughTurn = e.summaryThroughTurn.coerceIn(0, turns.size),
            metadata = metadata,
            turns = turns,
        )
    }
}


/** Fast cosine similarity between two same-dimension float arrays. */
private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) return 0f
    var dot = 0f
    var aNorm = 0f
    var bNorm = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        aNorm += a[i] * a[i]
        bNorm += b[i] * b[i]
    }
    val aN = kotlin.math.sqrt(aNorm)
    val bN = kotlin.math.sqrt(bNorm)
    if (aN == 0f || bN == 0f) return 0f
    return dot / (aN * bN)
}

/**
 * Extract active topics from recent conversation titles and summaries.
 * Returns a comma-separated list of keywords for injection into the
 * system prompt on new conversations. Simple word-frequency heuristic
 * — no LLM call needed.
 */
suspend fun ConversationStore.recentTopics(limit: Int = 5, excludeId: kotlin.String? = null): String {
    val recent = recent(limit)
    if (recent.isEmpty()) return ""
    val filtered = if (excludeId != null) recent.filter { it.id != excludeId } else recent
    if (filtered.isEmpty()) return ""
    val words = filtered.flatMap { conv ->
        val text = "${conv.title} ${conv.contextSummary}"
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 3 }
            .filter { it !in com.aura.core.util.StopWords.ENGLISH }
    }
    return words.groupingBy { it }.eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(8)
        .joinToString(", ") { it.key }
}
