package com.aura.proactive

import android.util.Log
import com.aura.kg.KnowledgeGraphRepository
import com.aura.memory.MemoryDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Curiosity Scanner — scans the Knowledge Graph for knowledge gaps
 * and generates natural-language curiosity questions.
 *
 * Ported from Python Aura's `proactive/curiosity_scanner.py`.
 */
@Singleton
class CuriosityScanner @Inject constructor(
    private val kgRepository: KnowledgeGraphRepository,
    private val memoryDao: MemoryDao,
) {
    data class CuriosityTarget(
        val entityName: kotlin.String,
        val entityType: kotlin.String,
        val gapType: GapType,
        val urgency: Float,
        val question: kotlin.String,
        val context: kotlin.String,
    )

    enum class GapType { ISOLATED, CONTEXTLESS, STALE, SHALLOW }

    suspend fun scan(): List<CuriosityTarget> {
        val targets = mutableListOf<CuriosityTarget>()
        runCatching { targets.addAll(findIsolatedNodes()) }
            .onFailure { Log.w("Curiosity", "isolated scan failed: ${it.message}", it) }
        runCatching { targets.addAll(findContextlessMentions()) }
            .onFailure { Log.w("Curiosity", "contextless scan failed: ${it.message}", it) }
        runCatching { targets.addAll(findStaleTopics()) }
            .onFailure { Log.w("Curiosity", "stale scan failed: ${it.message}", it) }
        runCatching { targets.addAll(findShallowKnowledge()) }
            .onFailure { Log.w("Curiosity", "shallow scan failed: ${it.message}", it) }
        return targets.distinctBy { it.entityName to it.gapType }
            .sortedByDescending { it.urgency }
            .take(5)
    }

    /** 1. Isolated nodes: < 3 connections */
    private suspend fun findIsolatedNodes(): List<CuriosityTarget> {
        val nodes = kgRepository.recent(100)
        val allEdges = kgRepository.allEdges()
        val results = mutableListOf<CuriosityTarget>()
        for (node in nodes) {
            val edgeCount = allEdges.count { it.sourceId == node.id || it.targetId == node.id }
            if (edgeCount < 3) {
                results.add(CuriosityTarget(
                    entityName = node.label,
                    entityType = node.type.name,
                    gapType = GapType.ISOLATED,
                    urgency = 0.3f,
                    question = "I noticed '$node.label' doesn't connect to much yet. What's its relationship to the rest?",
                    context = "Isolated node with only $edgeCount connections",
                ))
            }
        }
        return results.take(3)
    }

    /** 2. Contextless: mentioned in memory but no properties in KG */
    private suspend fun findContextlessMentions(): List<CuriosityTarget> {
        val memories = memoryDao.recent(50)
        val nodes = kgRepository.recent(200).filter { it.properties.isEmpty() }
        val nodeLabels = nodes.map { it.label.lowercase() }.toSet()
        val results = mutableListOf<CuriosityTarget>()
        for (memory in memories) {
            val words = memory.content.split(Regex("\\s+")).filter { it.length > 4 }
            val matched = words.firstOrNull { it.lowercase() in nodeLabels }
            if (matched != null) {
                val node = nodes.first { it.label.lowercase() == matched.lowercase() }
                results.add(CuriosityTarget(
                    entityName = node.label,
                    entityType = node.type.name,
                    gapType = GapType.CONTEXTLESS,
                    urgency = 0.4f,
                    question = "You've mentioned '$node.label' but I don't have context. Can you tell me more about it?",
                    context = "Mentioned in memory but no KG properties",
                ))
                break
            }
        }
        return results
    }

    /** 3. Stale topics: not updated in 14+ days */
    private suspend fun findStaleTopics(): List<CuriosityTarget> {
        val now = System.currentTimeMillis()
        val cutoff = now - 14L * 24 * 60 * 60 * 1000
        val nodes = kgRepository.recent(200)
        val results = mutableListOf<CuriosityTarget>()
        for (node in nodes) {
            if (node.updatedAt < cutoff) {
                results.add(CuriosityTarget(
                    entityName = node.label,
                    entityType = node.type.name,
                    gapType = GapType.STALE,
                    urgency = 0.2f,
                    question = "It's been a while since we discussed '$node.label'. Still relevant?",
                    context = "Not updated in 14+ days",
                ))
            }
        }
        return results.take(3)
    }

    /** 4. Shallow: high connectivity but low access count */
    private suspend fun findShallowKnowledge(): List<CuriosityTarget> {
        val nodes = kgRepository.recent(100)
        val allEdges = kgRepository.allEdges()
        val results = mutableListOf<CuriosityTarget>()
        for (node in nodes) {
            if (node.accessCount < 2) {
                val edgeCount = allEdges.count { it.sourceId == node.id || it.targetId == node.id }
                if (edgeCount >= 5) {
                    results.add(CuriosityTarget(
                        entityName = node.label,
                        entityType = node.type.name,
                        gapType = GapType.SHALLOW,
                        urgency = 0.3f,
                        question = "'$node.label' keeps coming up but I barely know it. Tell me more?",
                        context = "High connectivity ($edgeCount edges), low access count (${node.accessCount})",
                    ))
                }
            }
        }
        return results.take(2)
    }
}