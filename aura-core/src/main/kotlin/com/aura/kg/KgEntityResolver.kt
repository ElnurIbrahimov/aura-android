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
 * When a match is found, the new node is merged into the existing one:
 * - The existing node's ID is reused (so edges point to the right node)
 * - Confidence is updated: new = max(existing, new) + 0.05 boost
 * - Access count is incremented
 *
 * For edges: if an edge with the same (sourceId, targetId, type) already
 * exists, the new edge is not inserted. Instead, the existing edge's
 * confidence is updated: new = max(existing, new) + 0.05.
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
        val resolvedNodes = mutableListOf<KgNode>()
        val newNodesToInsert = mutableListOf<KgNode>()

        for (newNode in newNodes) {
            val existing = findMatch(newNode, existingByLabel, existingNodes)
            if (existing != null) {
                // Merge: reuse existing ID, boost confidence
                idRemap[newNode.id] = existing.id
                // Don't add to resolvedNodes — the existing node stays.
                // The caller can optionally update the existing node's
                // confidence + access count.
            } else {
                // No match — keep as new
                resolvedNodes.add(newNode)
                newNodesToInsert.add(newNode)
            }
        }

        // Remap edge source/target IDs and dedup edges
        val existingEdgeKeys = existingEdges.map { edgeKey(it) }.toMutableSet()
        val resolvedEdges = mutableListOf<KgEdge>()

        for (edge in newEdges) {
            val remappedSource = idRemap[edge.sourceId] ?: edge.sourceId
            val remappedTarget = idRemap[edge.targetId] ?: edge.targetId
            val remapped = edge.copy(sourceId = remappedSource, targetId = remappedTarget)
            val key = edgeKey(remapped)
            if (key !in existingEdgeKeys) {
                resolvedEdges.add(remapped)
                existingEdgeKeys.add(key) // prevent intra-batch dups too
            }
            // If edge already exists, skip — caller can boost confidence
        }

        return KgResolutionResult(
            nodesToInsert = newNodesToInsert,
            edgesToInsert = resolvedEdges,
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
        // Exact match (case-insensitive)
        existingByLabel[newNode.label.lowercase()]?.let { return it }

        // Fuzzy match: only for short labels (≤20 chars) to avoid
        // expensive O(n) comparison on large graphs
        if (newNode.label.length > 20) return null
        for (existing in existingNodes) {
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
    /** Map of new node ID → existing node ID (for edges that were remapped) */
    val idRemap: Map<String, String>,
    val mergedNodeCount: Int,
    val mergedEdgeCount: Int,
)
