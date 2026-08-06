package com.aura.ui.settings

import com.aura.agent.policy.ToolPolicy
import com.aura.ui.settings.sections.filterToolPolicies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tool Permissions section must show EVERY registered tool (the
 * old UI silently truncated to the first 8 sorted by name) and filter
 * them with a simple case-insensitive search.
 */
class ToolPermissionsFilterTest {

    private fun policies(vararg names: String): Map<String, ToolPolicy> =
        names.associateWith { ToolPolicy(toolName = it) }

    @Test
    fun `blank query returns every tool sorted by name — no truncation`() {
        // 12 tools — more than the old hard cap of 8.
        val all = policies(
            "web_search", "read_url", "recall", "calendar_read",
            "send_email", "capture_screen", "run_skill", "set_reminder",
            "create_task", "kg_query", "transcribe_audio", "delegate_to_agent",
        )

        val visible = filterToolPolicies(all, "")

        assertEquals(12, visible.size)
        assertEquals(all.keys.sorted(), visible.map { it.first })
    }

    @Test
    fun `query matches raw tool name case-insensitively`() {
        val all = policies("web_search", "read_url", "recall")

        val visible = filterToolPolicies(all, "WEB_SE")

        assertEquals(listOf("web_search"), visible.map { it.first })
    }

    @Test
    fun `query matches the humanized name with spaces`() {
        val all = policies("web_search", "read_url", "recall")

        // The UI renders "web_search" as "Web search" — searching the
        // way the list is displayed must work too.
        val visible = filterToolPolicies(all, "web search")

        assertEquals(listOf("web_search"), visible.map { it.first })
    }

    @Test
    fun `substring anywhere in the name matches`() {
        val all = policies("web_search", "search_memories", "recall")

        val visible = filterToolPolicies(all, "search")

        assertEquals(listOf("search_memories", "web_search"), visible.map { it.first })
    }

    @Test
    fun `no match returns empty list`() {
        val all = policies("web_search", "recall")

        assertTrue(filterToolPolicies(all, "zzz").isEmpty())
    }

    @Test
    fun `whitespace-only query behaves like blank`() {
        val all = policies("b_tool", "a_tool")

        val visible = filterToolPolicies(all, "   ")

        assertEquals(listOf("a_tool", "b_tool"), visible.map { it.first })
    }
}
