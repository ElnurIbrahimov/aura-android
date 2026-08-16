package com.aura.mcp

import com.aura.agent.ToolRisk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Third-party tools are classified by what they can do, not by what they cost.
 *
 * Every MCP tool was registered `REMOTE_COST`, reasoned from billing — "they
 * call external network endpoints that may consume paid API credits" — and
 * applied to tools whose capabilities the bridge cannot know. `REMOTE_COST`
 * sits *below* `WRITE_LOCAL` in the ordinal order four gates compare against, so
 * a server exposing a write tool ran during incognito, whose promise to the user
 * is that the session "cannot write memory or profile facts", and produced no
 * world event — the record of what Aura did, silently missing those calls.
 *
 * The case that matters here is the unstated one. Annotations are optional in
 * the protocol, so `null` is the common answer, and treating it as `false` is
 * the whole bug in miniature: "nobody said whether this writes" is a different
 * fact from "this does not write", and only one is safe to assume about
 * somebody else's server.
 */
class McpToolRiskTest {

    @Test
    fun `an unannotated tool is assumed to write`() {
        assertEquals(
            ToolRisk.WRITE_REMOTE,
            mcpToolRisk(readOnlyHint = null, destructiveHint = null),
            "silence must not be read as read-only. It is the common case, and reading it " +
                "generously is what let a third-party write tool run during incognito.",
        )
    }

    @Test
    fun `a declared read-only tool keeps the cost classification`() {
        // Accurate now rather than lucky: a read-only tool writes nothing, so it
        // *should* survive the incognito gate, and it still costs, so it should
        // still meet the per-run cost approval.
        assertEquals(ToolRisk.REMOTE_COST, mcpToolRisk(readOnlyHint = true, destructiveHint = null))
    }

    @Test
    fun `a declared destructive tool outranks its read-only claim`() {
        // A server claiming both is either confused or hostile. Take the worse one.
        assertEquals(ToolRisk.DESTRUCTIVE, mcpToolRisk(readOnlyHint = true, destructiveHint = true))
        assertEquals(ToolRisk.DESTRUCTIVE, mcpToolRisk(readOnlyHint = null, destructiveHint = true))
    }

    @Test
    fun `an explicit not-read-only is treated as a write`() {
        assertEquals(ToolRisk.WRITE_REMOTE, mcpToolRisk(readOnlyHint = false, destructiveHint = false))
    }

    /**
     * The property the whole change exists for, asserted against the boundary
     * itself rather than against a specific enum constant — so it keeps holding
     * if the classification is ever retuned.
     */
    @Test
    fun `anything not declared read-only lands above the local-write boundary`() {
        val undeclared = listOf(
            mcpToolRisk(null, null),
            mcpToolRisk(false, null),
            mcpToolRisk(null, false),
            mcpToolRisk(false, true),
        )
        for (risk in undeclared) {
            assertTrue(
                risk.ordinal >= ToolRisk.WRITE_LOCAL.ordinal,
                "$risk is below WRITE_LOCAL, so it escapes the incognito gate, world-event " +
                    "recording and ToolRegistry.byRisk — the four >= comparisons this boundary feeds",
            )
        }
    }
}
