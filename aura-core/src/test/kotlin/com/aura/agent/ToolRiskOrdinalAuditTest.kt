package com.aura.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ToolRisk]'s declaration order is a security invariant, and until this test
 * nothing held it.
 *
 * Four checks compare risks with `>=` on `ordinal`, so the order of the enum
 * *is* the policy:
 *
 *  - `PolicyEngine.evaluate` — the incognito gate
 *  - `ToolExecutor.execute` — the same gate again, as a hard fallback
 *  - `MemoryAugmentedAgenticLoop` — whether a call is recorded as a world event
 *  - `ToolRegistry.byRisk` — the "at least this risky" filter
 *
 * `ScreenActTool`'s KDoc already states the order is load-bearing. The only test
 * that touched it, `BeyondSotaBaselineTest.every_tool_has_a_known_risk_level`,
 * maps the entries `.toSet()` and asserts membership — which passes under every
 * permutation. So alphabetising the enum, or grouping the two WRITE_ risks
 * together because they read better that way, would silently change what
 * incognito blocks and what the world model records, with a green suite.
 *
 * This is a pure declaration test with no subject beyond the enum, which is the
 * point: the invariant lives in the source order and nowhere else.
 */
class ToolRiskOrdinalAuditTest {

    @Test
    fun `risk order is the one the ordinal comparisons assume`() {
        assertEquals(
            listOf(
                ToolRisk.READ_ONLY,
                ToolRisk.REMOTE_COST,
                ToolRisk.WRITE_LOCAL,
                ToolRisk.WRITE_REMOTE,
                ToolRisk.PRIVACY,
                ToolRisk.DESTRUCTIVE,
            ),
            ToolRisk.entries.toList(),
            "ToolRisk is compared with >= on ordinal in PolicyEngine, ToolExecutor, " +
                "MemoryAugmentedAgenticLoop and ToolRegistry.byRisk. Reordering these " +
                "entries changes what incognito blocks and what is recorded as a world " +
                "event. If the order must change, change those four call sites with it.",
        )
    }

    /**
     * The specific boundary the three `>= WRITE_LOCAL` gates draw.
     *
     * Stated as its own case because this is the line that decides behaviour, and
     * because it records a real consequence rather than a tautology: REMOTE_COST
     * sits *below* it, so a tool classified by what it costs rather than by what
     * it can do escapes all three. That is deliberate for the native tools —
     * `deep_research` and `code_interpreter` write nothing local — and is the
     * reason `McpToolBridge`, which classifies every third-party tool
     * REMOTE_COST by default because it cannot know their capabilities, is called
     * out in §3 as the case where the assumption does not hold.
     */
    @Test
    fun `REMOTE_COST sits below the local-write boundary`() {
        assertTrue(
            ToolRisk.READ_ONLY.ordinal < ToolRisk.WRITE_LOCAL.ordinal,
            "read-only tools must stay usable in incognito",
        )
        assertTrue(
            ToolRisk.REMOTE_COST.ordinal < ToolRisk.WRITE_LOCAL.ordinal,
            "REMOTE_COST is a billing classification and is deliberately below the " +
                "capability boundary; moving it above would block metered reads in incognito",
        )
        for (risk in listOf(ToolRisk.WRITE_REMOTE, ToolRisk.PRIVACY, ToolRisk.DESTRUCTIVE)) {
            assertTrue(
                risk.ordinal >= ToolRisk.WRITE_LOCAL.ordinal,
                "$risk must be caught by the >= WRITE_LOCAL gates",
            )
        }
    }
}
