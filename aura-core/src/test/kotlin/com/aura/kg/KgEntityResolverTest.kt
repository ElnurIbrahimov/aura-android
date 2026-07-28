package com.aura.kg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KgEntityResolverTest {

    private val resolver = KgEntityResolver()

    @Test
    fun `exact label match dedupes to existing node`() {
        val existing = listOf(
            KgNode(id = "n1", label = "Kotlin", type = NodeType.CONCEPT),
        )
        val new = listOf(
            KgNode(id = "n2", label = "kotlin", type = NodeType.CONCEPT),
        )
        val result = resolver.resolve(new, emptyList(), existing, emptyList())
        assertEquals(0, result.nodesToInsert.size)
        assertEquals(1, result.mergedNodeCount)
        assertEquals("n1", result.idRemap["n2"])
    }

    @Test
    fun `no match creates new node`() {
        val existing = listOf(
            KgNode(id = "n1", label = "Kotlin", type = NodeType.CONCEPT),
        )
        val new = listOf(
            KgNode(id = "n2", label = "Python", type = NodeType.CONCEPT),
        )
        val result = resolver.resolve(new, emptyList(), existing, emptyList())
        assertEquals(1, result.nodesToInsert.size)
        assertEquals(0, result.mergedNodeCount)
    }

    @Test
    fun `fuzzy match dedupes similar labels`() {
        val existing = listOf(
            KgNode(id = "n1", label = "Dusseldorf", type = NodeType.LOCATION),
        )
        val new = listOf(
            KgNode(id = "n2", label = "Düsseldorf", type = NodeType.LOCATION),
        )
        val result = resolver.resolve(new, emptyList(), existing, emptyList())
        assertEquals(0, result.nodesToInsert.size)
        assertEquals(1, result.mergedNodeCount)
    }

    @Test
    fun `edge remapped to existing node ID`() {
        val existing = listOf(
            KgNode(id = "n1", label = "Kotlin", type = NodeType.CONCEPT),
            KgNode(id = "u1", label = "User", type = NodeType.PERSON),
        )
        val existingEdges = listOf(
            KgEdge(id = "e1", type = EdgeType.USES, sourceId = "u1", targetId = "n1"),
        )
        val newNodes = listOf(
            KgNode(id = "n2", label = "kotlin", type = NodeType.CONCEPT),
        )
        val newEdges = listOf(
            KgEdge(id = "e2", type = EdgeType.USES, sourceId = "u1", targetId = "n2"),
        )
        val result = resolver.resolve(newNodes, newEdges, existing, existingEdges)
        // Edge should be remapped to point to existing node n1
        assertEquals(0, result.edgesToInsert.size) // duplicate edge
        assertEquals(1, result.mergedEdgeCount)
    }

    @Test
    fun `intra-batch duplicate edges are deduped`() {
        val newEdges = listOf(
            KgEdge(id = "e1", type = EdgeType.USES, sourceId = "u1", targetId = "n1"),
            KgEdge(id = "e2", type = EdgeType.USES, sourceId = "u1", targetId = "n1"),
        )
        val result = resolver.resolve(emptyList(), newEdges, emptyList(), emptyList())
        assertEquals(1, result.edgesToInsert.size)
    }

    @Test
    fun `dissimilar labels are not merged`() {
        val existing = listOf(
            KgNode(id = "n1", label = "JavaScript", type = NodeType.CONCEPT),
        )
        val new = listOf(
            KgNode(id = "n2", label = "Java", type = NodeType.CONCEPT),
        )
        val result = resolver.resolve(new, emptyList(), existing, emptyList())
        // "JavaScript" and "Java" are different enough (Levenshtein > 2 for 10-char)
        assertEquals(1, result.nodesToInsert.size)
    }
}
