package com.aura.kg

import com.aura.providers.ProviderRegistry
import com.aura.tools.KnowledgeGraphTool
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class KgIdUserNodeTest {

    @Test
    fun `USER_NODE_ID matches a PERSON node labelled user`() {
        // The extractor is instructed to label the speaker "user"; promotion
        // identifies beliefs about the user by comparing an edge's sourceId
        // against this constant, so the two must agree exactly.
        assertEquals(KgId.node(NodeType.PERSON, "user"), KgId.USER_NODE_ID)
    }

    @Test
    fun `USER_NODE_ID is case and whitespace insensitive at the label`() {
        assertEquals(KgId.USER_NODE_ID, KgId.node(NodeType.PERSON, "  User "))
    }

    // The two tests above only pin USER_NODE_ID against KgId.node directly —
    // that's tautological, since USER_NODE_ID is *defined* as that call. The
    // tests below route through KnowledgeGraphTool.parseResponse instead, so
    // they actually exercise the extractor-output -> USER_NODE_ID contract:
    // if the prompt rule in KnowledgeGraphTool.callLlm and this constant ever
    // drift apart, these are the tests that catch it.

    private val tool = KnowledgeGraphTool(ProviderRegistry(emptyMap(), mockk(relaxed = true), mockk(relaxed = true)))

    @Test
    fun `parseResponse assigns USER_NODE_ID to a node labelled user`() {
        val response = """
            {"nodes":[{"label":"user","type":"person"},{"label":"Kotlin","type":"concept"}],
             "edges":[{"type":"uses","source_label":"user","target_label":"Kotlin"}]}
        """.trimIndent()

        val (nodes, _) = tool.parseResponse(response)!!

        val userNode = nodes.first { it.label.equals("user", ignoreCase = true) }
        assertEquals(KgId.USER_NODE_ID, userNode.id)
    }

    @Test
    fun `parseResponse does not assign USER_NODE_ID to a node labelled with a real name`() {
        val response = """
            {"nodes":[{"label":"Elnur","type":"person"}],"edges":[]}
        """.trimIndent()

        val (nodes, _) = tool.parseResponse(response)!!

        val personNode = nodes.first { it.label.equals("Elnur", ignoreCase = true) }
        assertNotEquals(KgId.USER_NODE_ID, personNode.id)
    }
}
