package com.aura.realtime

import com.aura.agent.ToolContext
import com.aura.agent.ToolExecutor
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.agent.policy.PolicyEngine
import com.aura.agent.policy.PolicyResult
import com.aura.providers.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs tool calls during a live voice session.
 *
 * The hard problem: the loop's gate machinery is a pause/resume flow with modal
 * dialogs. None of that fits a phone call — a dialog appearing mid-conversation,
 * requiring the user to look at the screen, is worse UX than "I can't do that on
 * a call".
 *
 * The solution is to make gates mostly impossible rather than to handle them.
 * At session start every registered tool is evaluated once, and only those that
 * come back `Allowed` are advertised to the model. A tool the model cannot see
 * is a gate that cannot fire.
 */
@Singleton
class RealtimeToolBridge @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val policyEngine: PolicyEngine? = null,
) {

    /**
     * Tools safe to advertise for a voice session.
     *
     * Three filters, and the risk ceiling is the important one. Voice
     * confirmation is genuinely weaker than a dialog — a television in the room
     * can say "yes", and there is no way to be sure who spoke — so anything
     * irreversible stays unavailable rather than being protected by a
     * spoken-consent mechanism that cannot bear the weight.
     */
    suspend fun advertisableTools(context: ToolContext): List<ToolDefinition> {
        val ceiling = MAX_VOICE_RISK.ordinal
        // Sequential rather than a parallel filter: ~76 cheap evaluations run
        // once at connect, and the policy store is a DataStore read that is
        // already cached by the time the second one lands.
        return toolRegistry.definitions().filter { def ->
            val tool = toolRegistry.get(def.name) ?: return@filter false
            when {
                tool.risk.ordinal > ceiling -> false
                // A tool needing a runtime permission would raise a gate the
                // moment it is called, and there is no way to grant one mid-call.
                tool.requiredPermissions.isNotEmpty() -> false
                // Screen control, explicitly. Both tools are above the ceiling
                // anyway, so this is belt-and-braces — but a voice session that
                // can drive other apps is a product decision nobody has made,
                // and it should not arrive by accident if a risk level changes.
                def.name in EXCLUDED_TOOLS -> false
                policyEngine == null -> true
                else -> kotlinx.coroutines.runBlocking { policyEngine.evaluate(tool, context) } is PolicyResult.Allowed
            }
        }
    }

    /**
     * Execute one call and return the text to send back.
     *
     * A tool that was `Allowed` at connect can still refuse from its own body —
     * the pseudo-permission pattern. There is nothing useful to do about that
     * mid-call, so it is reported to the model as a plain result and the model
     * explains it in words. That is better than a dialog and better than
     * silence.
     */
    suspend fun execute(call: RealtimeEvent.ToolCall, context: ToolContext): String =
        when (val result = toolExecutor.execute(call.name, call.argumentsJson, context)) {
            is ToolResult.Ok -> result.output
            is ToolResult.Error -> "Error: ${result.message}"
            is ToolResult.NeedsPermission ->
                "BLOCKED: this needs a permission that cannot be granted during a call. " +
                    "Tell the user to enable it in Settings and try again afterwards."
            is ToolResult.NeedsApproval ->
                "BLOCKED: this needs approval that cannot be given during a call."
            is ToolResult.NeedsConfirmation ->
                "BLOCKED: this needs confirmation that cannot be given during a call."
        }

    companion object {
        /**
         * The highest risk a tool may carry and still be offered in voice.
         *
         * WRITE_LOCAL: reading, searching, remembering and local writes are
         * fine. WRITE_REMOTE and above — sending mail, spending money, driving
         * the screen — are not, because the only consent mechanism available
         * mid-call is a spoken "yes" from an unverified speaker.
         */
        val MAX_VOICE_RISK = ToolRisk.WRITE_LOCAL

        val EXCLUDED_TOOLS = setOf("screen_read", "screen_act")
    }
}

/**
 * Wall-clock and cost bounds on a session.
 *
 * Realtime audio bills per audio-minute in both directions, so a forgotten call
 * is real money in a way a forgotten chat window is not. The cap is a hard stop
 * rather than a warning.
 */
class RealtimeBudget(
    private val maxDurationMs: Long = DEFAULT_MAX_MS,
) {
    private var startedAt: Long = 0
    private var inputMs: Long = 0
    private var outputMs: Long = 0

    fun start(now: Long = System.currentTimeMillis()) {
        startedAt = now
        inputMs = 0
        outputMs = 0
    }

    fun record(usage: RealtimeEvent.AudioUsage) {
        inputMs += usage.inputMs
        outputMs += usage.outputMs
    }

    fun elapsedMs(now: Long = System.currentTimeMillis()): Long =
        if (startedAt == 0L) 0 else now - startedAt

    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        (maxDurationMs - elapsedMs(now)).coerceAtLeast(0)

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = remainingMs(now) == 0L

    /**
     * Whether to warn the user that time is nearly up.
     *
     * Spoken, not shown: the user is on a call and may not be looking at the
     * screen. A session that simply stops mid-sentence reads as a crash.
     */
    fun shouldWarn(now: Long = System.currentTimeMillis()): Boolean =
        startedAt != 0L && !isExpired(now) && elapsedMs(now) >= (maxDurationMs * WARN_AT_FRACTION).toLong()

    /** Billed audio seconds, for the usage ledger. */
    fun billedAudioSeconds(): Pair<Long, Long> = (inputMs / 1000) to (outputMs / 1000)

    companion object {
        const val DEFAULT_MAX_MS = 10 * 60 * 1000L
        const val WARN_AT_FRACTION = 0.8
    }
}
