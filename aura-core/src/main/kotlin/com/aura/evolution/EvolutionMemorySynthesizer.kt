package com.aura.evolution

import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import com.aura.world.EvidenceDao
import com.aura.world.EvidenceEntity
import com.aura.memory.MemoryDao
import com.aura.memory.MemoryFeedbackDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Memory synthesis engine v1: turns a scoped cluster of memories and feedback
 * into a durable Belief. Uses lexical clustering and simple confidence math;
 * LLM reflection can be layered later via [EvolutionReflectionExecutor].
 */
@Singleton
class EvolutionMemorySynthesizer @Inject constructor(
    private val memoryDao: MemoryDao,
    private val memoryFeedbackDao: MemoryFeedbackDao,
    private val beliefDao: BeliefDao,
    private val evidenceDao: EvidenceDao,
) {
    suspend fun synthesizeScope(scope: String, now: Long = System.currentTimeMillis()): BeliefEntity? {
        val memories = if (scope == "general") memoryDao.byScope("general", 200) else memoryDao.withinScope("$scope%", 200)
        if (memories.size < 2) return null

        // Cluster by token overlap; pick largest cluster.
        val clusters = clusterLexically(memories.map { it.content })
        val topCluster = clusters.maxByOrNull { it.size } ?: return null
        if (topCluster.size < 2) return null
        val clusterMemories = memories.filter { it.content in topCluster }
        val subject = guessSubject(clusterMemories.map { it.content })
        val predicate = "preference_or_fact"
        val value = summarize(clusterMemories.map { it.content })

        // Confidence: base 0.5 + 0.05 per supporting memory, capped at 0.95.
        val supportBonus = (clusterMemories.size - 1) * 0.05f
        val feedback = clusterMemories.flatMap { memoryFeedbackDao.byMemoryId(it.id, 50) }
        val upvotes = feedback.count { it.kind == "upvote" }
        val downvotes = feedback.count { it.kind == "downvote" }
        val feedbackDelta = (upvotes - downvotes) * 0.03f
        val confidence = (0.5f + supportBonus + feedbackDelta).coerceIn(0.0f, 0.95f)

        val beliefId = "belief_${scope}_${now}"
        val belief = BeliefEntity(
            id = beliefId,
            subject = subject,
            predicate = predicate,
            valueJson = """{"summary":"$value"}""",
            confidence = confidence,
            createdAt = now,
            updatedAt = now,
        )
        beliefDao.upsert(belief)
        for (mem in clusterMemories) {
            evidenceDao.upsert(
                EvidenceEntity(
                    id = "ev_${beliefId}_${mem.id}",
                    beliefId = beliefId,
                    source = mem.source,
                    summary = mem.content.take(200),
                    timestamp = mem.createdAt,
                )
            )
        }
        return belief
    }

    private fun clusterLexically(phrases: List<String>): List<List<String>> {
        val normalized = phrases.map { it.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").split(Regex("\\s+")).filter { w -> w.length > 2 }.toSet() }
        val visited = mutableSetOf<Int>()
        val clusters = mutableListOf<List<String>>()
        for (i in normalized.indices) {
            if (i in visited) continue
            val cluster = mutableListOf(phrases[i])
            visited.add(i)
            for (j in normalized.indices) {
                if (j in visited) continue
                val overlap = normalized[i].intersect(normalized[j]).size
                val union = normalized[i].union(normalized[j]).size.coerceAtLeast(1)
                if (overlap.toFloat() / union > 0.2f) {
                    cluster.add(phrases[j])
                    visited.add(j)
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    private fun guessSubject(phrases: List<String>): String {
        // Crude: most common non-stop word across phrases.
        val stop = setOf("the", "and", "that", "have", "for", "not", "with", "you", "this", "but", "his", "from", "they", "she", "her", "him", "its", "is", "are", "was", "were", "be", "been", "being", "i", "me", "my", "we", "us", "our")
        val tokens = phrases.flatMap { it.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").split(Regex("\\s+")).filter { it.length > 2 && it !in stop } }
        return tokens.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "general"
    }

    private fun summarize(phrases: List<String>): String = phrases.maxByOrNull { it.length } ?: ""
}
