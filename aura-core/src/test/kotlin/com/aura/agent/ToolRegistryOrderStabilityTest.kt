package com.aura.agent

import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ToolRegistry.definitions] feeds the `tools` array of every chat request, and
 * providers that cache prompt prefixes hash those bytes. The backing map is a
 * `ConcurrentHashMap`, whose iteration order is undefined and shifts as the
 * table resizes — and MCP tools register after startup, so it does resize
 * mid-process. An unstable order is a silent cache miss with no error anywhere,
 * which is the failure mode these tests exist to prevent.
 */
class ToolRegistryOrderStabilityTest {

    private fun tool(name: String, risk: ToolRisk = ToolRisk.READ_ONLY) = Tool(
        name = name,
        description = "Tool $name",
        risk = risk,
        parameters = ToolParameters(
            properties = mapOf("q" to ToolProperty(type = "string", description = "arg")),
        ),
        execute = { _, _ -> ToolResult.Ok("ok") },
        category = "test",
    )

    /** Enough entries to force at least one ConcurrentHashMap resize. */
    private val manyNames = (1..40).map { "tool_%02d".format(it) }

    @Test
    fun `definitions are identical regardless of registration order`() {
        val forward = ToolRegistry().apply { manyNames.forEach { register(tool(it)) } }
        val reverse = ToolRegistry().apply { manyNames.reversed().forEach { register(tool(it)) } }
        val shuffled = ToolRegistry().apply {
            manyNames.sortedBy { it.hashCode() }.forEach { register(tool(it)) }
        }

        val a = forward.definitions().map { it.name }
        val b = reverse.definitions().map { it.name }
        val c = shuffled.definitions().map { it.name }

        assertEquals(a, b, "registration order leaked into definitions()")
        assertEquals(a, c, "registration order leaked into definitions()")
    }

    @Test
    fun `definitions are sorted by name`() {
        val registry = ToolRegistry().apply {
            listOf("zebra", "alpha", "middle", "Beta").forEach { register(tool(it)) }
        }
        val names = registry.definitions().map { it.name }
        assertEquals(names.sorted(), names, "definitions() is not sorted by name")
    }

    @Test
    fun `repeated calls return the same cached instance`() {
        val registry = ToolRegistry().apply { manyNames.forEach { register(tool(it)) } }
        assertSame(
            registry.definitions(),
            registry.definitions(),
            "definitions() rebuilt the list with no intervening mutation",
        )
    }

    @Test
    fun `registering a tool invalidates the cache`() {
        val registry = ToolRegistry().apply { manyNames.forEach { register(tool(it)) } }
        val before = registry.definitions()

        registry.register(tool("tool_00_new"))
        val after = registry.definitions()

        assertNotEquals(before.size, after.size, "cache was not invalidated by register()")
        assertTrue("tool_00_new" in after.map { it.name })
        // The new tool sorts first — proving the rebuild re-sorted rather than appending.
        assertEquals("tool_00_new", after.first().name)
    }

    @Test
    fun `unregistering a tool invalidates the cache`() {
        val registry = ToolRegistry().apply { manyNames.forEach { register(tool(it)) } }
        registry.definitions()

        registry.unregister("tool_01")
        val after = registry.definitions().map { it.name }

        assertTrue("tool_01" !in after, "cache was not invalidated by unregister()")
        assertEquals(manyNames.size - 1, after.size)
    }

    @Test
    fun `re-registering the same name in place does not change the order`() {
        // McpToolBridge.syncTools unregisters then re-registers on every sync.
        // That must not perturb the array for the tools that did not change.
        val registry = ToolRegistry().apply { manyNames.forEach { register(tool(it)) } }
        val before = registry.definitions().map { it.name }

        registry.unregister("tool_20")
        registry.register(tool("tool_20"))
        val after = registry.definitions().map { it.name }

        assertEquals(before, after, "a no-op re-register reordered the tools array")
    }

    @Test
    fun `definitions carry through the fields the wire needs`() {
        val registry = ToolRegistry().apply { register(tool("probe", ToolRisk.PRIVACY)) }
        val def = registry.definitions().single()

        assertEquals("probe", def.name)
        assertEquals("Tool probe", def.description)
        assertEquals("test", def.category)
        assertEquals(setOf("q"), def.parameters.properties.keys)
    }
}
