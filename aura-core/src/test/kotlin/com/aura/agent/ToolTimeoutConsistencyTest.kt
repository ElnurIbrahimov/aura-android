package com.aura.agent

import org.junit.Test
import kotlin.test.assertTrue

/**
 * A tool that budgets longer than [DEFAULT_TOOL_TIMEOUT_MS] internally must say so
 * on its [Tool], or [ToolExecutor] cancels it before its own timeout can fire.
 *
 * This is the invariant that was violated, silently, for the whole life of the
 * chat loop. `ToolContext.timeout` defaulted to 30s and
 * `MemoryAugmentedAgenticLoop` never passed one, so:
 *
 *  - `deep_research` budgeted 120s and died at 30s — after paying for the
 *    searches, and with its gap-detection second iteration unreachable
 *  - `parallel_research` budgeted 45s of angles then 30s of synthesis, and got
 *    through roughly the angles
 *  - `delegate_to_agent` and `knowledge_graph_extract` budgeted exactly 30s
 *    against a 30s ceiling, so the executor's clock — started first — always
 *    won, and their own timeout messages were unreachable code
 *
 * Nothing failed. Every one of those returned a bare `tool_timeout`, which is
 * indistinguishable from a slow network, so the suite stayed green and the
 * feature stayed broken. `ProductionPipelineEngine` found the identical defect
 * on the agent-run path and fixed it there; its KDoc records that "the
 * generative stages of these pipelines have never completed". This test exists
 * so the third instance is caught by CI rather than by a fourth review.
 *
 * Source-scanning because the property is "these two numbers relate correctly",
 * and one of them is a private constant that no runtime handle exposes.
 * Comments are stripped first: a commented-out `timeoutMs =` must not satisfy
 * the check, which is the defect `ProjectSpineIsWiredTest` shipped with for one
 * commit and `ForegroundAppIsNeverStoredTest` shipped with for longer.
 */
class ToolTimeoutConsistencyTest {

    private val budgetConst =
        Regex("""const\s+val\s+(\w*(?:TIMEOUT|BUDGET)\w*)\s*(?::\s*Long\s*)?=\s*([\d_]+)L""")
    private val inlineWithTimeout = Regex("""withTimeout\(\s*([\d_]+)L\s*\)""")
    private val declaresTimeoutMs = Regex("""\btimeoutMs\s*=""")

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `every tool that needs longer than the default declares its own budget`() {
        val toolFiles = sourceDir("src/main/kotlin")
            .resolve("com/aura/tools")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .toList()
            .requireNonEmpty("tool sources")

        val violations = mutableListOf<String>()

        for (file in toolFiles) {
            val source = stripComments(file.readText())
            // Only files that actually register a Tool can declare a budget on
            // one. A helper file with a long HTTP timeout is not in scope.
            if (!source.contains("Tool(")) continue

            val budgets = buildList {
                budgetConst.findAll(source).forEach { m ->
                    add(m.groupValues[1] to m.groupValues[2].replace("_", "").toLong())
                }
                inlineWithTimeout.findAll(source).forEach { m ->
                    add("withTimeout literal" to m.groupValues[1].replace("_", "").toLong())
                }
            }

            val overBudget = budgets.filter { it.second >= DEFAULT_TOOL_TIMEOUT_MS }
            if (overBudget.isEmpty()) continue

            if (!declaresTimeoutMs.containsMatchIn(source)) {
                val worst = overBudget.maxBy { it.second }
                violations += "${file.name}: budgets ${worst.second / 1000}s internally " +
                    "(${worst.first}) but declares no timeoutMs, so ToolExecutor kills it " +
                    "at ${DEFAULT_TOOL_TIMEOUT_MS / 1000}s"
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Tools whose internal budget exceeds the executor's default without declaring " +
                "`timeoutMs` on their Tool:\n  ${violations.joinToString("\n  ")}\n\n" +
                "ToolExecutor runs `withTimeout(ctx.timeout ?: tool.timeoutMs)`. A tool that " +
                "needs longer than $DEFAULT_TOOL_TIMEOUT_MS ms and does not say so is cancelled " +
                "mid-work on every call, and reports a generic tool_timeout that looks like a " +
                "slow network. Declare `timeoutMs = YOUR_BUDGET_MS + TIMEOUT_HEADROOM_MS`.",
        )
    }

    @Test
    fun `headroom is large enough to let the inner timeout win`() {
        // The two budgets must not be equal. delegate_to_agent and
        // knowledge_graph_extract both sat exactly at the executor's ceiling,
        // and a tie always goes to the executor because its clock starts first
        // — so the tool's own timeout, and the specific message it would have
        // returned, could never be reached.
        assertTrue(
            TIMEOUT_HEADROOM_MS > 0,
            "headroom must be positive or a tool's own timeout can never fire first",
        )
    }
}
