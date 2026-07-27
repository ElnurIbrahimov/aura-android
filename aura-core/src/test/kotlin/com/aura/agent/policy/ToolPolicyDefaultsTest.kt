package com.aura.agent.policy

import com.aura.agent.ToolRisk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolPolicyDefaultsTest {

    @Test fun `READ_ONLY defaults to enabled, NONE confirmation`() {
        val p = ToolPolicyDefaults.forTool("web_search", ToolRisk.READ_ONLY)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.NONE, p.confirmation)
        assertEquals("web_search", p.toolName)
    }

    @Test fun `REMOTE_COST defaults to enabled, NONE confirmation`() {
        val p = ToolPolicyDefaults.forTool("image_gen", ToolRisk.REMOTE_COST)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.NONE, p.confirmation)
    }

    @Test fun `WRITE_LOCAL defaults to NONE confirmation (regression guard)`() {
        val p = ToolPolicyDefaults.forTool("remember", ToolRisk.WRITE_LOCAL)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.NONE, p.confirmation)
    }

    @Test fun `WRITE_REMOTE defaults to EXPLICIT confirmation`() {
        val p = ToolPolicyDefaults.forTool("http_file_write", ToolRisk.WRITE_REMOTE)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.EXPLICIT, p.confirmation)
    }

    @Test fun `PRIVACY defaults to IMPLICIT confirmation`() {
        val p = ToolPolicyDefaults.forTool("capture_screen", ToolRisk.PRIVACY)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.IMPLICIT, p.confirmation)
    }

    @Test fun `DESTRUCTIVE defaults to EXPLICIT confirmation`() {
        val p = ToolPolicyDefaults.forTool("delete_account", ToolRisk.DESTRUCTIVE)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.EXPLICIT, p.confirmation)
    }

    @Test fun `every risk produces an enabled policy with the same name`() {
        val allRisks = ToolRisk.entries
        for (risk in allRisks) {
            val p = ToolPolicyDefaults.forTool("test_tool_$risk", risk)
            assertEquals("test_tool_$risk", p.toolName)
            assertTrue(p.enabled, "$risk should default to enabled")
        }
    }

    @Test fun `only WRITE_REMOTE and DESTRUCTIVE default to EXPLICIT confirmation`() {
        val explicitTools = ToolRisk.entries
            .map { it to ToolPolicyDefaults.forTool("x", it) }
            .filter { it.second.confirmation == ConfirmationLevel.EXPLICIT }
        assertEquals(listOf(ToolRisk.WRITE_REMOTE, ToolRisk.DESTRUCTIVE), explicitTools.map { it.first })
    }
}
