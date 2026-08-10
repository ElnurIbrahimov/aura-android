package com.aura.agent.policy

import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {

    private val policyStore = mockk<ToolPolicyStore>(relaxed = true)
    private val engine = PolicyEngine(policyStore)

    private fun makeTool(risk: ToolRisk, name: kotlin.String = "test_tool") = Tool(
        name = name,
        description = "Test tool",
        risk = risk,
        execute = { _, _ -> ToolResult.Ok("ok") },
    )

    private fun ctx(
        memoryEnabled: kotlin.Boolean = true,
        approvedTools: Set<kotlin.String> = emptySet(),
        confirmedTools: Set<kotlin.String> = emptySet(),
    ) = ToolContext(
        conversationId = "test",
        memoryEnabled = memoryEnabled,
        approvedRemoteCostTools = approvedTools,
        confirmedTools = confirmedTools,
    )

    @Test
    fun allows_read_only_tool_with_default_policy() = runTest {
        val tool = makeTool(ToolRisk.READ_ONLY)
        coEvery { policyStore.getPolicy("test_tool") } returns null
        val result = engine.evaluate(tool, ctx())
        assertTrue("Expected Allowed, got $result", result is PolicyResult.Allowed)
    }

    // ---- the scope allowlist, which was declared and never evaluated --------

    private fun scoped(vararg scopes: kotlin.String) = ToolPolicy(
        toolName = "test_tool",
        allowedScopes = scopes.toList(),
    )

    @Test
    fun `an empty allowlist restricts nothing`() = runTest {
        // The default and the common case. Empty must mean "no restriction",
        // not "nothing is allowed", or every tool in the app stops working.
        coEvery { policyStore.getPolicy("test_tool") } returns ToolPolicy(toolName = "test_tool")
        val result = engine.evaluate(makeTool(ToolRisk.READ_ONLY), ctx(), scope = "com.whatsapp")
        assertTrue("Expected Allowed, got $result", result is PolicyResult.Allowed)
    }

    @Test
    fun `a scope outside the allowlist is denied`() = runTest {
        coEvery { policyStore.getPolicy("test_tool") } returns scoped("com.google", "example.com")
        val result = engine.evaluate(makeTool(ToolRisk.READ_ONLY), ctx(), scope = "com.evil.app")
        assertTrue("Expected ScopeDenied, got $result", result is PolicyResult.ScopeDenied)
        assertEquals("com.evil.app", (result as PolicyResult.ScopeDenied).scope)
    }

    @Test
    fun `a missing scope against a configured allowlist FAILS CLOSED`() = runTest {
        // The decision that makes this a control rather than a decoration. A
        // call site that forgets to pass its scope must not silently bypass a
        // restriction the user deliberately set. Denying is visible and
        // debuggable; allowing is neither.
        coEvery { policyStore.getPolicy("test_tool") } returns scoped("com.google")
        val result = engine.evaluate(makeTool(ToolRisk.READ_ONLY), ctx(), scope = null)
        assertTrue("Expected ScopeDenied, got $result", result is PolicyResult.ScopeDenied)
    }

    @Test
    fun `an allowlist entry matches exactly or extends at a path separator`() = runTest {
        // This test failed on its first run and was right to. The matcher also
        // extended at a dot, so "com.google" would cover "com.google.android.gm"
        // — and the same rule let "example.com.evil.net" past an "example.com"
        // allowlist. A package hierarchy and a lookalike domain are the same
        // string shape; nothing here can tell them apart, so the permissive
        // reading was dropped rather than special-cased.
        coEvery { policyStore.getPolicy("test_tool") } returns scoped("com.google", "example.com")
        val tool = makeTool(ToolRisk.READ_ONLY)

        assertTrue(engine.evaluate(tool, ctx(), scope = "com.google") is PolicyResult.Allowed)
        assertTrue(engine.evaluate(tool, ctx(), scope = "example.com/inbox") is PolicyResult.Allowed)

        // The lookalike, which is the whole reason for the rule.
        assertTrue(engine.evaluate(tool, ctx(), scope = "example.com.evil.net") is PolicyResult.ScopeDenied)
        assertTrue(engine.evaluate(tool, ctx(), scope = "com.googlemail") is PolicyResult.ScopeDenied)
        // A sub-package is NOT covered. Name the app you mean.
        assertTrue(engine.evaluate(tool, ctx(), scope = "com.google.android.gm") is PolicyResult.ScopeDenied)
    }

    @Test
    fun `scopeDenial is callable on its own for tools that learn their target late`() = runTest {
        // screen_act only knows its package after reading the foreground app,
        // long after the policy gate ran, so it calls this directly.
        coEvery { policyStore.getPolicy("screen_act") } returns
            ToolPolicy(toolName = "screen_act", allowedScopes = listOf("com.whatsapp"))

        assertEquals(null, engine.scopeDenial("screen_act", "com.whatsapp"))
        assertTrue(engine.scopeDenial("screen_act", "com.bank.app") is PolicyResult.ScopeDenied)
    }

    @Test
    fun `no stored policy means no scope restriction`() = runTest {
        // Defaults come from ToolPolicyDefaults, which sets no scopes, so an
        // unconfigured tool must not be gated by a check it never opted into.
        coEvery { policyStore.getPolicy("screen_act") } returns null
        assertEquals(null, engine.scopeDenial("screen_act", "com.anything"))
    }

    @Test
    fun blocks_write_local_in_incognito_mode() = runTest {
        val tool = makeTool(ToolRisk.WRITE_LOCAL)
        coEvery { policyStore.getPolicy("test_tool") } returns null
        val result = engine.evaluate(tool, ctx(memoryEnabled = false))
        assertTrue("Expected Disabled, got $result", result is PolicyResult.Disabled)
    }

    @Test
    fun allows_read_only_in_incognito_mode() = runTest {
        val tool = makeTool(ToolRisk.READ_ONLY)
        coEvery { policyStore.getPolicy("test_tool") } returns null
        val result = engine.evaluate(tool, ctx(memoryEnabled = false))
        assertTrue("Expected Allowed, got $result", result is PolicyResult.Allowed)
    }

    @Test
    fun blocks_disabled_tool() = runTest {
        val tool = makeTool(ToolRisk.READ_ONLY)
        coEvery { policyStore.getPolicy("test_tool") } returns ToolPolicy(
            toolName = "test_tool",
            enabled = false,
        )
        val result = engine.evaluate(tool, ctx())
        assertTrue("Expected Disabled, got $result", result is PolicyResult.Disabled)
    }

    @Test
    fun requires_confirmation_when_policy_set() = runTest {
        val tool = makeTool(ToolRisk.READ_ONLY)
        coEvery { policyStore.getPolicy("test_tool") } returns ToolPolicy(
            toolName = "test_tool",
            confirmation = ConfirmationLevel.EXPLICIT,
        )
        val result = engine.evaluate(tool, ctx())
        assertTrue("Expected NeedsConfirmation, got $result", result is PolicyResult.NeedsConfirmation)
        val confirmation = result as PolicyResult.NeedsConfirmation
        assertEquals(ConfirmationLevel.EXPLICIT, confirmation.level)
    }

    @Test
    fun confirmation_satisfied_by_ctx_confirmedTools_returns_allowed() = runTest {
        val tool = makeTool(ToolRisk.READ_ONLY)
        coEvery { policyStore.getPolicy("test_tool") } returns ToolPolicy(
            toolName = "test_tool",
            confirmation = ConfirmationLevel.EXPLICIT,
        )
        val result = engine.evaluate(tool, ctx(confirmedTools = setOf("test_tool")))
        assertTrue("Expected Allowed, got $result", result is PolicyResult.Allowed)
    }

    @Test
    fun confirmation_grant_for_other_tool_does_not_satisfy() = runTest {
        val tool = makeTool(ToolRisk.READ_ONLY)
        coEvery { policyStore.getPolicy("test_tool") } returns ToolPolicy(
            toolName = "test_tool",
            confirmation = ConfirmationLevel.EXPLICIT,
        )
        val result = engine.evaluate(tool, ctx(confirmedTools = setOf("some_other_tool")))
        assertTrue("Expected NeedsConfirmation, got $result", result is PolicyResult.NeedsConfirmation)
    }

    @Test
    fun confirmed_remote_cost_tool_still_needs_approval() = runTest {
        val tool = makeTool(ToolRisk.REMOTE_COST, "web_search")
        coEvery { policyStore.getPolicy("web_search") } returns ToolPolicy(
            toolName = "web_search",
            confirmation = ConfirmationLevel.EXPLICIT,
        )
        // The confirmation is granted but the cost approval is not —
        // a confirmed REMOTE_COST tool falls through to the approval gate.
        val result = engine.evaluate(tool, ctx(confirmedTools = setOf("web_search")))
        assertTrue("Expected NeedsApproval, got $result", result is PolicyResult.NeedsApproval)
    }

    @Test
    fun confirmed_and_approved_remote_cost_tool_is_allowed() = runTest {
        val tool = makeTool(ToolRisk.REMOTE_COST, "web_search")
        coEvery { policyStore.getPolicy("web_search") } returns ToolPolicy(
            toolName = "web_search",
            confirmation = ConfirmationLevel.EXPLICIT,
        )
        val result = engine.evaluate(
            tool,
            ctx(approvedTools = setOf("web_search"), confirmedTools = setOf("web_search")),
        )
        assertTrue("Expected Allowed, got $result", result is PolicyResult.Allowed)
    }

    @Test
    fun remote_cost_needs_approval_without_explicit_approval() = runTest {
        val tool = makeTool(ToolRisk.REMOTE_COST, "web_search")
        coEvery { policyStore.getPolicy("web_search") } returns null
        val result = engine.evaluate(tool, ctx())
        assertTrue("Expected NeedsApproval, got $result", result is PolicyResult.NeedsApproval)
    }

    @Test
    fun remote_cost_allowed_with_explicit_approval() = runTest {
        val tool = makeTool(ToolRisk.REMOTE_COST, "web_search")
        coEvery { policyStore.getPolicy("web_search") } returns null
        val result = engine.evaluate(tool, ctx(approvedTools = setOf("web_search")))
        assertTrue("Expected Allowed, got $result", result is PolicyResult.Allowed)
    }

    @Test
    fun per_run_approval_required_when_policy_set() = runTest {
        val tool = makeTool(ToolRisk.WRITE_LOCAL, "set_reminder")
        coEvery { policyStore.getPolicy("set_reminder") } returns ToolPolicy(
            toolName = "set_reminder",
            requireApprovalPerRun = true,
        )
        val result = engine.evaluate(tool, ctx())
        assertTrue("Expected NeedsApproval, got $result", result is PolicyResult.NeedsApproval)
    }
}