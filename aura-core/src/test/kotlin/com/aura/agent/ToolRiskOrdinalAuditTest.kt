package com.aura.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ToolRisk]'s declaration order is a security invariant, and until this test
 * nothing held it.
 *
 * Three checks compare risks with `>=` on `ordinal`, so the order of the enum
 * *is* the policy:
 *
 *  - `PolicyEngine.evaluate` — the incognito gate
 *  - `ToolExecutor.execute` — the same gate again, as a hard fallback
 *  - `ToolRegistry.byRisk` — the "at least this risky" filter
 *
 * There was a fourth, and removing it is why this file changed. World-event recording asked
 * the same `>= WRITE_LOCAL` question, and one ordering cannot answer two different ones:
 * incognito wants "this risky **or worse**", which PRIVACY correctly clears, while recording
 * wants "does this **change** anything", which PRIVACY does not. Sharing the boundary meant
 * every notification, contact and screen read a privacy tool returned was summarised into
 * `world_events` — plaintext, unpurged, carried into backups, and readable back by
 * `query_world_model`, which is READ_ONLY and asks no confirmation. Recording now asks
 * [ToolRisk.mutatesState], and the ordering below is free to keep meaning severity.
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
            "ToolRisk is compared with >= on ordinal in PolicyEngine, ToolExecutor and " +
                "ToolRegistry.byRisk. Reordering these entries changes what incognito " +
                "blocks. If the order must change, change those three call sites with it.",
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
    fun `severity gating and state-change are separate questions`() {
        // The bug this file now records. PRIVACY is above WRITE_LOCAL on purpose, so the
        // incognito gate catches it — reading your notifications in incognito should be
        // refused. It must simultaneously NOT count as a state change, because a privacy tool
        // sees rather than alters, and what it saw must not be written down.
        //
        // One ordinal boundary was asked both questions and could only answer one.
        assertTrue(
            ToolRisk.PRIVACY.ordinal >= ToolRisk.WRITE_LOCAL.ordinal,
            "PRIVACY must stay above the boundary so incognito keeps blocking it",
        )
        assertTrue(
            !ToolRisk.PRIVACY.mutatesState,
            "a privacy tool reads; it changes nothing, and its output must not be persisted",
        )
        for (risk in listOf(ToolRisk.WRITE_LOCAL, ToolRisk.WRITE_REMOTE, ToolRisk.DESTRUCTIVE)) {
            assertTrue(risk.mutatesState, "$risk changes something and should be recorded")
        }
        for (risk in listOf(ToolRisk.READ_ONLY, ToolRisk.REMOTE_COST)) {
            assertTrue(!risk.mutatesState, "$risk changes nothing")
        }
    }

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
                "$risk must be caught by the incognito gates",
            )
        }
    }
}
