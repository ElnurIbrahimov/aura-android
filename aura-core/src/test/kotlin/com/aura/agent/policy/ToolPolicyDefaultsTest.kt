package com.aura.agent.policy

import com.aura.agent.ToolRisk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolPolicyDefaultsTest {

    // ── Lock in the IMPLICIT regression guard ──────────────────────
    // The 4f40e406 fix removed IMPLICIT confirmation from WRITE tools
    // because it blocked 28 write tools. A future change that puts
    // IMPLICIT back MUST be intentional — these tests fail.

    @Test fun `READ_ONLY defaults to enabled, NONE confirmation`() {
        val p = ToolPolicyDefaults.forTool("web_search", ToolRisk.READ_ONLY)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.NONE, p.confirmation)
        assertEquals("web_search", p.toolName)
    }

    @Test fun `REMOTE_COST defaults to enabled, NONE confirmation`() {
        // REMOTE_COST tools use the per-run approval gate
        // (approvedRemoteCostTools) — not the confirmation level —
        // so they default to NONE.
        val p = ToolPolicyDefaults.forTool("image_gen", ToolRisk.REMOTE_COST)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.NONE, p.confirmation)
    }

    @Test fun `WRITE_LOCAL defaults to NONE confirmation (regression guard)`() {
        // 4f40e406 regression: WRITE_LOCAL had IMPLICIT confirmation
        // which blocked 28 write tools. This test pins the NONE default.
        val p = ToolPolicyDefaults.forTool("remember", ToolRisk.WRITE_LOCAL)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.NONE, p.confirmation)
    }

    @Test fun `WRITE_REMOTE defaults to NONE confirmation (regression guard)`() {
        val p = ToolPolicyDefaults.forTool("send_sms", ToolRisk.WRITE_REMOTE)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.NONE, p.confirmation)
    }

    @Test fun `PRIVACY defaults to NONE confirmation (regression guard)`() {
        val p = ToolPolicyDefaults.forTool("memory_query", ToolRisk.PRIVACY)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.NONE, p.confirmation)
    }

    @Test fun `DESTRUCTIVE defaults to EXPLICIT confirmation`() {
        // The only risk level that requires the user to type "yes".
        // Locked in to prevent accidental weakening.
        val p = ToolPolicyDefaults.forTool("delete_account", ToolRisk.DESTRUCTIVE)
        assertTrue(p.enabled)
        assertEquals(ConfirmationLevel.EXPLICIT, p.confirmation)
    }

    @Test fun `every risk produces an enabled policy with the same name`() {
        // The defaults factory should never produce a disabled-by-default
        // tool — the user can disable, but the default is permissive.
        val allRisks = ToolRisk.entries
        for (risk in allRisks) {
            val p = ToolPolicyDefaults.forTool("test_tool_$risk", risk)
            assertEquals("test_tool_$risk", p.toolName)
            assertTrue(p.enabled, "$risk should default to enabled")
        }
    }

    @Test fun `DESTRUCTIVE is the only risk with EXPLICIT confirmation`() {
        // The contrast test: of all 6 risk levels, only DESTRUCTIVE
        // requires explicit user confirmation in the default policy.
        val explicitTools = ToolRisk.entries
            .map { it to ToolPolicyDefaults.forTool("x", it) }
            .filter { it.second.confirmation == ConfirmationLevel.EXPLICIT }
        assertEquals(listOf(ToolRisk.DESTRUCTIVE), explicitTools.map { it.first })
    }
}
