package com.aura.kg

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface KnowledgeGraphDao {

    /**
     * `@Upsert`, not `@Insert(REPLACE)`. REPLACE is a DELETE followed by an
     * INSERT, and `kg_edges` declares CASCADE against `kg_nodes.id` on **both**
     * endpoints — so re-saving a node deleted every edge touching it. Since
     * [com.aura.kg.KgId.node] hashes (type, label), a node keeps its id across
     * mentions, and the extractor labels the user as `user` on essentially
     * every turn: the graph was being truncated to one turn's worth of edges,
     * continuously, in a way nothing reported.
     *
     * It also made the world model unreachable. [saveGraph] inserts nodes
     * before edges, so the cascade wiped each edge just before the loop below
     * read its original `createdAt` — leaving `createdAt == lastReinforced` on
     * every write, which is exactly the "seen in more than one turn" test
     * `BeliefPromoter.qualifies()` requires. Zero beliefs could ever be
     * promoted.
     *
     * Note `@Upsert` overwrites every column of the entity passed to it, so it
     * does not by itself preserve `createdAt`/`accessCount`/`lastAccessed` —
     * see [KnowledgeGraphRepository.saveGraph], which carries them forward the
     * same way it already did for edges.
     */
    @Upsert
    suspend fun insertNode(node: NodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: EdgeEntity)

    @Update
    suspend fun updateNode(node: NodeEntity)

    @Query("SELECT * FROM kg_nodes WHERE id = :id")
    suspend fun getNode(id: String): NodeEntity?

    @Query("SELECT * FROM kg_nodes WHERE label = :label LIMIT 1")
    suspend fun getNodeByLabel(label: String): NodeEntity?

    @Query("""
        SELECT * FROM kg_nodes
        WHERE label LIKE '%' || :queryEscaped || '%' ESCAPE '\'
           OR type LIKE '%' || :queryEscaped || '%' ESCAPE '\'
           OR properties LIKE '%' || :queryEscaped || '%' ESCAPE '\'
        ORDER BY accessCount DESC, updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchNodes(queryEscaped: String, limit: Int = 50): List<NodeEntity>

    @Query("SELECT * FROM kg_nodes ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentNodes(limit: Int = 50): List<NodeEntity>

    /**
     * KG nodes whose updatedAt is at or after [sinceMs], newest
     * first. Bounded by [limit]. Used by the morning brief to
     * surface "facts learned in the last 24h."
     */
    @Query("SELECT * FROM kg_nodes WHERE updatedAt >= :sinceMs ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentNodesSince(sinceMs: Long, limit: Int): List<NodeEntity>

    @Query("UPDATE kg_nodes SET accessCount = accessCount + 1, lastAccessed = :now WHERE id = :id")
    suspend fun incrementAccessCount(id: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM kg_edges WHERE sourceId = :id OR targetId = :id")
    suspend fun edgesForNode(id: String): List<EdgeEntity>

    /** Alias with management-domain wording. */
    suspend fun neighbors(id: String): List<EdgeEntity> = edgesForNode(id)

    @Query("SELECT * FROM kg_edges WHERE sourceId = :sourceId")
    suspend fun edgesFrom(sourceId: String): List<EdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE id = :id")
    suspend fun getEdge(id: String): EdgeEntity?

    @Query("SELECT * FROM kg_edges WHERE targetId = :targetId")
    suspend fun edgesTo(targetId: String): List<EdgeEntity>

    /**
     * The claims Aura extracted from one conversation turn.
     *
     * The one hop a correction follows: if the sentence a memory came from was
     * wrong, the graph claims made from that same sentence are wrong with it.
     * Indexed on `sourceConversationId`; the timestamp narrows it to the turn.
     */
    @Query("SELECT * FROM kg_edges WHERE sourceConversationId = :conversationId AND sourceTurnTimestamp = :turnTimestamp")
    suspend fun edgesFromTurn(conversationId: String, turnTimestamp: Long): List<EdgeEntity>

    @Query("DELETE FROM kg_edges WHERE id = :id")
    suspend fun deleteEdge(id: String)

    @Query("DELETE FROM kg_edges WHERE sourceId = :id OR targetId = :id")
    suspend fun deleteEdgesForNode(id: String)

    @Query("DELETE FROM kg_nodes WHERE id = :id")
    suspend fun deleteNode(id: String)

    @Query("SELECT COUNT(*) FROM kg_nodes")
    suspend fun nodeCount(): Int

    /**
     * Nodes with fewer than 2 incident edges — "knowledge gaps" feeding
     * the CURIOSITY drive (see [com.aura.consciousness.DriveSignals]).
     * The correlated COUNT is index-backed: kg_edges is indexed on both
     * sourceId and targetId, so each inner count is two index lookups.
     * O(nodes) overall, and only ever run behind DriveSignals' 5-min TTL.
     */
    @Query(
        """
        SELECT COUNT(*) FROM kg_nodes n WHERE
            (SELECT COUNT(*) FROM kg_edges e WHERE e.sourceId = n.id OR e.targetId = n.id) < 2
        """
    )
    suspend fun gapNodeCount(): Int

    /**
     * The gap nodes themselves, newest first.
     *
     * [gapNodeCount] has always returned how many there are, and that count is
     * the whole of what the CURIOSITY drive knows — so the drive could say "14
     * unexplored topics" and never which fourteen. A question needs the rows.
     * Same correlated COUNT, same index-backed cost, bounded by [limit].
     */
    @Query(
        """
        SELECT * FROM kg_nodes n WHERE
            (SELECT COUNT(*) FROM kg_edges e WHERE e.sourceId = n.id OR e.targetId = n.id) < 2
        ORDER BY n.createdAt DESC LIMIT :limit
        """
    )
    suspend fun gapNodes(limit: Int = 20): List<NodeEntity>

    /**
     * Nodes with plenty of connections that have never actually been used.
     *
     * The inverse of a gap, and a sharper question than one: Aura has built a
     * web of relationships around something and has never once reached for it,
     * which usually means it recorded the shape of a topic without
     * understanding it. Inherited from the `CuriosityScanner` this replaced —
     * the one signal there that the gap scan does not already cover.
     */
    @Query(
        """
        SELECT * FROM kg_nodes n WHERE n.accessCount < :maxAccess AND
            (SELECT COUNT(*) FROM kg_edges e WHERE e.sourceId = n.id OR e.targetId = n.id) >= :minEdges
        ORDER BY n.createdAt DESC LIMIT :limit
        """
    )
    suspend fun shallowNodes(maxAccess: Int = 2, minEdges: Int = 5, limit: Int = 20): List<NodeEntity>

    @Query("SELECT COUNT(*) FROM kg_edges")
    suspend fun edgeCount(): Int

    /**
     * Nodes whose label exactly matches one of [lowercaseLabels].
     *
     * The indexed half of entity resolution — `kg_nodes` is indexed on `label`,
     * so this is a handful of lookups rather than a table scan, and it is what
     * catches the overwhelming majority of merges ("Causeway" meeting
     * "causeway").
     */
    @Query("SELECT * FROM kg_nodes WHERE LOWER(label) IN (:lowercaseLabels)")
    suspend fun nodesByLabels(lowercaseLabels: List<String>): List<NodeEntity>

    /**
     * A bounded pool of same-type nodes for fuzzy matching.
     *
     * Resolution's second pass is a Levenshtein scan over same-type nodes,
     * which used to run against every node in the graph on every extraction —
     * roughly every turn. Bounding it changes fuzzy matching from exhaustive to
     * best-effort, which it already was: it only applies to labels of 20
     * characters or fewer, and a graph large enough for the bound to bite is
     * one where an unbounded per-turn scan was never affordable anyway.
     */
    @Query("SELECT * FROM kg_nodes WHERE type IN (:types) ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun nodesByTypes(types: List<String>, limit: Int): List<NodeEntity>

    /**
     * Edges touching any of [ids].
     *
     * Resolution uses existing edges only to build a set of (source, target,
     * type) keys for dedup, and a new edge's key can only collide with one
     * whose endpoints are among the nodes being resolved. Loading the rest was
     * loading the whole table to ignore it.
     */
    @Query("SELECT * FROM kg_edges WHERE sourceId IN (:ids) OR targetId IN (:ids)")
    suspend fun edgesTouching(ids: List<String>): List<EdgeEntity>

    @Query("SELECT * FROM kg_nodes")
    suspend fun allNodes(): List<NodeEntity>

    @Query("SELECT * FROM kg_edges")
    suspend fun allEdges(): List<EdgeEntity>

    /** Backup restore. `@Upsert` for the same cascade reason as [insertNode]. */
    @Upsert
    suspend fun insertAllNodes(rows: List<NodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllEdges(rows: List<EdgeEntity>)

    /**
     * Atomically publish the merged target and its rewritten relations before
     * deleting the source. Deleting the source cascades its old incident edges.
     */
    @Transaction
    suspend fun mergeNodeRecords(
        sourceId: String,
        target: NodeEntity,
        rewrittenEdges: List<EdgeEntity>,
    ) {
        updateNode(target)
        if (rewrittenEdges.isNotEmpty()) insertAllEdges(rewrittenEdges)
        deleteNode(sourceId)
    }

    @Query("DELETE FROM kg_edges")
    suspend fun deleteAllEdges()

    @Query("DELETE FROM kg_nodes")
    suspend fun deleteAllNodes()
}
