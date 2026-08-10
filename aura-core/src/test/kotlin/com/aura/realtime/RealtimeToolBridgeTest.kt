package com.aura.realtime

import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolExecutor
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolParameters
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a model may do while it has the user's microphone.
 *
 * The design makes gates mostly impossible rather than handling them: the loop's
 * gate machinery is modal dialogs, and a dialog appearing mid-call — requiring
 * the user to look at the screen — is worse UX than "I can't do that on a call".
 * So the filtering at connect is the whole safety story, and these are the tests
 * of it.
 */
class RealtimeToolBridgeTest {

    private fun registryWith(vararg tools: Tool) = ToolRegistry().apply { tools.forEach(::register) }

    private fun tool(
        name: String,
        risk: ToolRisk,
        permissions: List<String> = emptyList(),
    ) = Tool(
        name = name,
        description = "Tool $name",
        risk = risk,
        requiredPermissions = permissions,
        parameters = ToolParameters(),
        execute = { _, _ -> ToolResult.Ok("ok") },
        category = "test",
    )

    private fun bridge(registry: ToolRegistry) = RealtimeToolBridge(
        toolRegistry = registry,
        toolExecutor = ToolExecutor(registry, context = mockk(relaxed = true)),
        policyEngine = null,
    )

    private val ctx = ToolContext(conversationId = "voice-1")

    // ---- the risk ceiling ------------------------------------------------

    @Test
    fun `read-only and local-write tools are offered`() {
        val registry = registryWith(
            tool("recall", ToolRisk.READ_ONLY),
            tool("web_search", ToolRisk.READ_ONLY),
            tool("remember", ToolRisk.WRITE_LOCAL),
            tool("deep_research", ToolRisk.REMOTE_COST),
        )
        val names = runBlocking { bridge(registry).advertisableTools(ctx) }.map { it.name }
        assertTrue("recall" in names)
        assertTrue("remember" in names)
        assertTrue("web_search" in names)
        assertTrue("deep_research" in names, "REMOTE_COST sits below WRITE_LOCAL in the ordinal order")
    }

    @Test
    fun `remote writes and destructive tools are never offered`() {
        // The load-bearing rule. The only consent available mid-call is a
        // spoken "yes" from a speaker nobody can verify — a television in the
        // room can say it — so anything irreversible stays unavailable rather
        // than being protected by a mechanism that cannot bear the weight.
        val registry = registryWith(
            tool("email_send", ToolRisk.WRITE_REMOTE),
            tool("sms_send", ToolRisk.WRITE_REMOTE),
            tool("delete_everything", ToolRisk.DESTRUCTIVE),
            tool("recall", ToolRisk.READ_ONLY),
        )
        val names = runBlocking { bridge(registry).advertisableTools(ctx) }.map { it.name }
        assertEquals(listOf("recall"), names, "an irreversible tool reached a voice session")
    }

    @Test
    fun `privacy tools are not offered`() {
        // PRIVACY sits above WRITE_LOCAL in the ordinal order, which is what
        // keeps location, contacts and the notification list out of a session
        // whose only confirmation mechanism is spoken.
        val registry = registryWith(
            tool("location_now", ToolRisk.PRIVACY),
            tool("recall", ToolRisk.READ_ONLY),
        )
        val names = runBlocking { bridge(registry).advertisableTools(ctx) }.map { it.name }
        assertTrue("location_now" !in names)
    }

    @Test
    fun `tools needing a runtime permission are not offered`() {
        // Calling one would raise a permission gate, and there is no way to
        // grant a runtime permission mid-call.
        val registry = registryWith(
            tool("calendar_read", ToolRisk.READ_ONLY, listOf("android.permission.READ_CALENDAR")),
            tool("recall", ToolRisk.READ_ONLY),
        )
        val names = runBlocking { bridge(registry).advertisableTools(ctx) }.map { it.name }
        assertEquals(listOf("recall"), names)
    }

    @Test
    fun `screen control is excluded by name as well as by risk`() {
        // Belt and braces. Both are above the ceiling already, but a voice
        // session that can drive other apps is a product decision nobody has
        // made — it must not arrive by accident if a risk level ever changes.
        val registry = registryWith(
            // Deliberately understated risk, to prove the name filter carries
            // the rule on its own.
            tool("screen_read", ToolRisk.READ_ONLY),
            tool("screen_act", ToolRisk.WRITE_LOCAL),
            tool("recall", ToolRisk.READ_ONLY),
        )
        val names = runBlocking { bridge(registry).advertisableTools(ctx) }.map { it.name }
        assertEquals(listOf("recall"), names, "screen control reached a voice session")
    }

    @Test
    fun `the advertised list is stable in order`() {
        // It feeds the session config, which is sent once — but an unstable
        // order here would make the wire tests flaky for no reason.
        val registry = registryWith(
            tool("zebra", ToolRisk.READ_ONLY),
            tool("alpha", ToolRisk.READ_ONLY),
        )
        val names = runBlocking { bridge(registry).advertisableTools(ctx) }.map { it.name }
        assertEquals(names.sorted(), names)
    }

    // ---- execution -------------------------------------------------------

    @Test
    fun `a successful call returns its output`() {
        val registry = registryWith(tool("recall", ToolRisk.READ_ONLY))
        val out = runBlocking {
            bridge(registry).execute(RealtimeEvent.ToolCall("c1", "recall", "{}"), ctx)
        }
        assertEquals("ok", out)
    }

    @Test
    fun `an unknown tool comes back as an error the model can explain`() {
        val registry = registryWith(tool("recall", ToolRisk.READ_ONLY))
        val out = runBlocking {
            bridge(registry).execute(RealtimeEvent.ToolCall("c1", "nope", "{}"), ctx)
        }
        assertTrue(out.startsWith("Error:"), out)
    }

    @Test
    fun `a body-raised gate is reported in words rather than as a dialog`() {
        // A tool that was Allowed at connect can still refuse from its own body
        // — the pseudo-permission pattern. Mid-call there is nothing useful to
        // do about it, so the model is told and explains it out loud. Better
        // than a dialog, and better than silence.
        val gated = Tool(
            name = "notification_list",
            description = "x",
            risk = ToolRisk.READ_ONLY,
            parameters = ToolParameters(),
            execute = { _, _ -> ToolResult.NeedsPermission("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", "enable it") },
            category = "test",
        )
        val registry = registryWith(gated)
        val out = runBlocking {
            bridge(registry).execute(RealtimeEvent.ToolCall("c1", "notification_list", "{}"), ctx)
        }
        assertTrue(out.startsWith("BLOCKED:"), out)
        assertTrue("Settings" in out, "the model should be able to tell the user what to do: $out")
    }

    // ---- budget ----------------------------------------------------------

    @Test
    fun `a session expires at its cap`() {
        val now = 1_000_000L
        val budget = RealtimeBudget(maxDurationMs = 60_000)
        budget.start(now)
        assertTrue(!budget.isExpired(now + 59_999))
        assertTrue(budget.isExpired(now + 60_000))
        assertEquals(0, budget.remainingMs(now + 90_000))
    }

    @Test
    fun `the warning fires before the cap, not at it`() {
        // Spoken rather than shown, because the user is on a call and may not
        // be looking at the screen — and a session that simply stops
        // mid-sentence reads as a crash.
        val now = 1_000_000L
        val budget = RealtimeBudget(maxDurationMs = 100_000)
        budget.start(now)
        assertTrue(!budget.shouldWarn(now + 79_000))
        assertTrue(budget.shouldWarn(now + 80_000))
        assertTrue(!budget.shouldWarn(now + 100_000), "an expired session should end, not warn")
    }

    @Test
    fun `an unstarted budget neither warns nor expires`() {
        val budget = RealtimeBudget()
        assertTrue(!budget.shouldWarn(System.currentTimeMillis()))
        assertEquals(0, budget.elapsedMs(System.currentTimeMillis()))
    }

    @Test
    fun `audio usage accumulates for the ledger`() {
        // Billed per audio-minute in both directions, so this is cost, not
        // telemetry.
        val budget = RealtimeBudget()
        budget.start(0)
        budget.record(RealtimeEvent.AudioUsage(inputMs = 30_000, outputMs = 45_000))
        budget.record(RealtimeEvent.AudioUsage(inputMs = 30_000, outputMs = 15_000))
        assertEquals(60L to 60L, budget.billedAudioSeconds())
    }

    @Test
    fun `starting a session resets the previous one's usage`() {
        val budget = RealtimeBudget()
        budget.start(0)
        budget.record(RealtimeEvent.AudioUsage(10_000, 10_000))
        budget.start(1_000)
        assertEquals(0L to 0L, budget.billedAudioSeconds())
    }
}
