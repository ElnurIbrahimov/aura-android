package com.aura.kg

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Entity resolver for the knowledge graph.
 *
 * Before inserting new nodes/edges from a conversation turn, the
 * resolver checks whether equivalent nodes already exist in the graph.
 * "Equivalent" means:
 * - Same label (case-insensitive exact match)
 * - High label similarity (Levenshtein distance ≤ 2 for short labels,
 *   ≤ 30% character difference for longer labels)
 *
 * When a match is found, the new node is merged into the existing one: the
 * existing node's ID is reused, so edges point at the right node, and the node
 * itself comes back in [KgResolutionResult.nodesToTouch] rather than in
 * `nodesToInsert`.
 *
 * That distinction is load-bearing, not bookkeeping. A resolver that only
 * reported what to *insert* would make `saveGraph` skip every re-mention
 * entirely, and two things depend on a re-mention being written:
 * `KnowledgeGraphDao.recentNodesSince` (the morning brief's "facts learned
 * yesterday") reads `updatedAt`, and `BeliefPromoter.qualifies()` tests
 * `lastReinforced > createdAt` as its proxy for "seen in more than one turn".
 * Dropping a duplicate edge before anything stamps `lastReinforced` would mean
 * no edge ever clears that bar and no belief is ever promoted — the exact
 * regression this split exists to prevent. Duplicates therefore come back in
 * [KgResolutionResult.edgesToReinforce], and the caller writes them through
 * the same path as new edges.
 *
 * This prevents the KG from growing with paraphrase duplicates:
 * "user → likes → Kotlin" and "user → enjoys → Kotlin" don't create
 * two separate "Kotlin" nodes.
 */
@Singleton
class KgEntityResolver @Inject constructor() {

    /**
     * Resolve a batch of new nodes+edges against existing ones.
     *
     * @param newNodes Nodes extracted from the current turn
     * @param newEdges Edges extracted from the current turn
     * @param existingNodes All nodes currently in the graph
     * @param existingEdges All edges currently in the graph
     * @return Resolved nodes (deduped, IDs remapped) + resolved edges
     *         (pointing to remapped node IDs, deduped)
     */
    fun resolve(
        newNodes: List<KgNode>,
        newEdges: List<KgEdge>,
        existingNodes: List<KgNode>,
        existingEdges: List<KgEdge>,
    ): KgResolutionResult {
        // Build a label-to-node index for fast lookup
        val existingByLabel = existingNodes.associateBy { it.label.lowercase() }

        // ID remapping: new node ID → existing node ID (if matched)
        val idRemap = mutableMapOf<String, String>()
        val newNodesToInsert = mutableListOf<KgNode>()
        val nodesToTouch = mutableListOf<KgNode>()
        val touchedIds = mutableSetOf<String>()

        // Track nodes we've already processed in this batch to
        // prevent intra-batch duplicates (same entity mentioned in
        // two edges of the same turn).
        val processedLabels = mutableMapOf<String, KgNode>()
        for (newNode in newNodes) {
            // Check intra-batch first
            val batchMatch = processedLabels[newNode.label.lowercase()]
            if (batchMatch != null) {
                idRemap[newNode.id] = batchMatch.id
                continue
            }
            val existing = findMatch(newNode, existingByLabel, existingNodes)
            if (existing != null) {
                // Merge: reuse the existing ID so edges land on the right node,
                // and hand the caller the *new* node under the *existing* id.
                // The caller needs both halves: the id to find the row, and this
                // turn's confidence to raise the stored one.
                idRemap[newNode.id] = existing.id
                if (touchedIds.add(existing.id)) {
                    nodesToTouch.add(newNode.copy(id = existing.id))
                }
            } else {
                // No match — keep as new
                newNodesToInsert.add(newNode)
                processedLabels[newNode.label.lowercase()] = newNode
            }
        }

        // Remap edge source/target IDs and split new from already-seen
        val existingEdgeKeys = existingEdges.map { edgeKey(it) }.toMutableSet()
        val resolvedEdges = mutableListOf<KgEdge>()
        val edgesToReinforce = mutableListOf<KgEdge>()
        val reinforcedKeys = mutableSetOf<String>()

        for (edge in newEdges) {
            val remappedSource = idRemap[edge.sourceId] ?: edge.sourceId
            val remappedTarget = idRemap[edge.targetId] ?: edge.targetId
            val remapped = edge.copy(sourceId = remappedSource, targetId = remappedTarget)
            val key = edgeKey(remapped)
            if (key !in existingEdgeKeys) {
                resolvedEdges.add(remapped)
                existingEdgeKeys.add(key) // prevent intra-batch dups too
            } else if (reinforcedKeys.add(key)) {
                // Seen before. NOT dropped: this is the second sighting that
                // `BeliefPromoter` is waiting for, and it only becomes visible
                // once the row's `lastReinforced` moves. Deduped against
                // `reinforcedKeys` so the same edge repeated twice inside one
                // turn still writes once.
                edgesToReinforce.add(remapped)
            }
        }

        return KgResolutionResult(
            nodesToInsert = newNodesToInsert,
            edgesToInsert = resolvedEdges,
            nodesToTouch = nodesToTouch,
            edgesToReinforce = edgesToReinforce,
            idRemap = idRemap,
            mergedNodeCount = idRemap.size,
            mergedEdgeCount = newEdges.size - resolvedEdges.size,
        )
    }

    /**
     * Find an existing node that matches [newNode].
     * Tries exact label match first, then fuzzy match.
     */
    private fun findMatch(
        newNode: KgNode,
        existingByLabel: Map<String, KgNode>,
        existingNodes: List<KgNode>,
    ): KgNode? {
        // Exact match (case-insensitive) — must be same NodeType
        existingByLabel[newNode.label.lowercase()]?.let {
            if (it.type == newNode.type) return it
        }

        // Fuzzy match: only for short labels (≤20 chars) to avoid
        // expensive O(n) comparison on large graphs. Must be same
        // NodeType to prevent "Sam" (PERSON) merging with "Pam" (CONCEPT).
        if (newNode.label.length > 20) return null
        for (existing in existingNodes) {
            if (existing.type != newNode.type) continue
            if (isSimilar(newNode.label, existing.label)) return existing
        }
        return null
    }

    /**
     * Check if two labels are similar enough to be the same entity.
     * Uses Levenshtein distance for short strings and character
     * overlap ratio for longer ones.
     */
    private fun isSimilar(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val aLower = a.lowercase()
        val bLower = b.lowercase()
        val maxLen = max(aLower.length, bLower.length)
        if (maxLen == 0) return true

        // For short labels (≤10 chars): Levenshtein ≤ 2
        if (maxLen <= 10) {
            return levenshtein(aLower, bLower) <= 2
        }

        // For longer labels: ≤ 30% character difference
        val dist = levenshtein(aLower, bLower)
        return dist.toDouble() / maxLen <= 0.3
    }

    /**
     * Levenshtein edit distance. Standard DP implementation.
     */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost,
                )
            }
        }
        return dp[m][n]
    }

    private fun edgeKey(edge: KgEdge): String =
        "${edge.sourceId}|${edge.targetId}|${edge.type}"

    companion object {
        private const val TAG = "KgEntityResolver"
    }
}

/**
 * Result of entity resolution.
 */
data class KgResolutionResult(
    val nodesToInsert: List<KgNode>,
    val edgesToInsert: List<KgEdge>,
    /**
     * Nodes that already exist and were mentioned again. Carries this turn's
     * confidence under the *stored* node's id. Defaulted, and placed before the
     * non-defaulted `idRemap`, which is safe because every construction of this
     * type uses named arguments — the resolver's own `return` and nothing else
     * in the tree.
     */
    val nodesToTouch: List<KgNode> = emptyList(),
    /**
     * Edges that already exist and were asserted again. These must still be
     * written — see this file's class KDoc for what stops working if they are
     * not.
     */
    val edgesToReinforce: List<KgEdge> = emptyList(),
    /** Map of new node ID → existing node ID (for edges that were remapped) */
    val idRemap: Map<String, String>,
    val mergedNodeCount: Int,
    val mergedEdgeCount: Int,
)
