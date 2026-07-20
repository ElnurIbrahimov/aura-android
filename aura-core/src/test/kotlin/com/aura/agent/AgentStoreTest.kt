package com.aura.agent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import com.aura.backup.toBackup
import com.aura.backup.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentStoreTest {

    private lateinit var dao: AgentDao
    private lateinit var store: AgentStore

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        store = AgentStore(dao)
    }

    @Test
    fun `seedBuiltins does nothing when agents already exist`() = runTest {
        coEvery { dao.count() } returns 3
        store.seedBuiltins()
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `seedBuiltins inserts 7 agents when empty`() = runTest {
        coEvery { dao.count() } returns 0
        val captured = slot<List<AgentEntity>>()
        coEvery { dao.insertAll(capture(captured)) } returns Unit

        store.seedBuiltins()

        assertEquals(7, captured.captured.size)
        val general = captured.captured.first { it.name == "general" }
        assertTrue(general.isBuiltin)
        assertTrue(general.isDefault)
        assertEquals("shared", general.memoryScope)

        val coder = captured.captured.first { it.name == "coder" }
        assertTrue(coder.isBuiltin)
        assertEquals("agent:agent_coder", coder.memoryScope)
    }

    @Test
    fun `create inserts custom agent with unique id`() = runTest {
        val agent = store.create(
            name = "Sage",
            icon = "\uD83D\uDD0E",
            description = "Research agent",
            identity = "You are a research agent.",
            tools = setOf("deep_research", "web_search"),
            preferredModel = "ollama:deepseek-v4-pro",
            memoryScope = "agent:agent_custom_sage",
            personality = PersonalityProfile.Researcher,
        )

        coVerify { dao.insert(any()) }
        assertEquals("Sage", agent.name)
        assertTrue(agent.id.contains("custom"))
        assertTrue(!agent.isBuiltin)
    }

    @Test
    fun `delete only deletes custom agents`() = runTest {
        store.delete("agent_custom_123")
        coVerify { dao.deleteCustom("agent_custom_123") }
    }

    @Test
    fun `deleteAllCustom calls dao deleteAllCustom`() = runTest {
        store.deleteAllCustom()
        coVerify { dao.deleteAllCustom() }
    }

    @Test
    fun `byName delegates to dao`() = runTest {
        val agent = AgentEntity(
            id = "agent_coder",
            name = "coder",
            icon = "\uD83D\uDCBB",
            description = "",
            identity = "",
            toolsAllowed = "",
        )
        coEvery { dao.byName("coder") } returns agent
        val result = store.byName("coder")
        assertNotNull(result)
        assertEquals("coder", result!!.name)
    }

    @Test
    fun `byName returns null when not found`() = runTest {
        coEvery { dao.byName("nonexistent") } returns null
        assertNull(store.byName("nonexistent"))
    }

    @Test
    fun `AgentEntity toolSet parses comma-separated tools`() {
        val agent = AgentEntity(
            id = "test",
            name = "test",
            icon = "\uD83E\uDD16",
            description = "",
            identity = "",
            toolsAllowed = "web_search,deep_research, recall ,",
        )
        val tools = agent.toolSet()
        assertEquals(3, tools.size)
        assertTrue(tools.contains("web_search"))
        assertTrue(tools.contains("deep_research"))
        assertTrue(tools.contains("recall"))
    }

    @Test
    fun `AgentEntity toolSet returns empty for blank toolsAllowed`() {
        val agent = AgentEntity(
            id = "test",
            name = "test",
            icon = "\uD83E\uDD16",
            description = "",
            identity = "",
            toolsAllowed = "",
        )
        assertTrue(agent.toolSet().isEmpty())
    }

    @Test
    fun `AgentEntity personality returns default for blank json`() {
        val agent = AgentEntity(
            id = "test",
            name = "test",
            icon = "\uD83E\uDD16",
            description = "",
            identity = "",
            toolsAllowed = "",
            personalityJson = "{}",
        )
        val p = agent.personality()
        assertEquals(0.5f, p.warmth, 0.01f)
    }

    @Test
    fun `AgentEntity personality parses valid json`() {
        val json = """{"warmth":0.9,"formality":0.1,"verbosity":0.5,"humor":0.8,"proactivity":0.6,"riskTolerance":0.3}"""
        val agent = AgentEntity(
            id = "test",
            name = "test",
            icon = "\uD83E\uDD16",
            description = "",
            identity = "",
            toolsAllowed = "",
            personalityJson = json,
        )
        val p = agent.personality()
        assertEquals(0.9f, p.warmth, 0.01f)
        assertEquals(0.1f, p.formality, 0.01f)
        assertEquals(0.8f, p.humor, 0.01f)
    }

    @Test
    fun `PersonalityProfile toPromptDirective returns empty for neutral values`() {
        val p = PersonalityProfile()
        assertEquals("", p.toPromptDirective())
    }

    @Test
    fun `PersonalityProfile toPromptDirective returns directives for extreme values`() {
        val p = PersonalityProfile(warmth = 0.9f, formality = 0.9f, verbosity = 0.1f, humor = 0.1f, proactivity = 0.9f, riskTolerance = 0.1f)
        val directive = p.toPromptDirective()
        assertTrue(directive.contains("warm"))
        assertTrue(directive.contains("formal"))
        assertTrue(directive.contains("concise"))
        assertTrue(directive.contains("serious"))
        assertTrue(directive.contains("proven"))
    }

    @Test
    fun `AgentBackup roundtrip preserves all fields`() {
        val original = AgentEntity(
            id = "agent_test",
            name = "TestAgent",
            icon = "\uD83E\uDD16",
            description = "Test description",
            identity = "You are a test agent.",
            toolsAllowed = "web_search,recall",
            preferredModel = "ollama:gemma4:e4b",
            memoryScope = "agent:agent_test",
            personalityJson = """{"warmth":0.8}""",
            isBuiltin = false,
            isDefault = false,
            createdAt = 1234567890L,
            updatedAt = 1234567891L,
            color = 42,
        )
        val backup = original.toBackup()
        val restored = backup.toEntity()
        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.identity, restored.identity)
        assertEquals(original.toolsAllowed, restored.toolsAllowed)
        assertEquals(original.preferredModel, restored.preferredModel)
        assertEquals(original.memoryScope, restored.memoryScope)
        assertEquals(original.personalityJson, restored.personalityJson)
        assertEquals(original.isBuiltin, restored.isBuiltin)
        assertEquals(original.color, restored.color)
    }
}