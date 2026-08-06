package com.aura.evolution

import com.aura.agent.Tool
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolParameters
import javax.inject.Provider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression suite for the empty-patch defect: the old evolution system
 * shipped "{}" patches all the way to the apply saga. The validator must
 * reject "{}" and every missing-required-field variant for every action, so
 * an invalid patch can never become a proposal.
 */
class EvolutionPatchValidatorTest {

    private val registry = ToolRegistry().apply {
        register(tool("web_search"))
        register(tool("send_email"))
    }
    private val validator = EvolutionPatchValidator(Provider { registry }, EvolutionSafetyGuard())

    private fun tool(name: String) = Tool(
        name = name,
        description = "test tool",
        risk = ToolRisk.READ_ONLY,
        parameters = ToolParameters(),
        execute = { _, _ -> ToolResult.Ok("ok") },
    )

    private fun context(
        targetId: String = "t1",
        currentSkillBody: String? = null,
        shownMemoryIds: Set<String> = emptySet(),
    ) = EvolutionPatchValidator.Context(targetId, currentSkillBody, shownMemoryIds)

    private fun assertInvalid(action: EvolutionAction, patch: String, ctx: EvolutionPatchValidator.Context = context()) {
        val result = validator.validate(action, patch, ctx)
        assertTrue(result is EvolutionPatchValidator.Result.Invalid, "$action should reject: $patch → $result")
    }

    // ── "{}" + missing fields, per action ───────────────────────

    @Test
    fun `empty patch is rejected for every action`() {
        for (action in EvolutionAction.entries) {
            assertInvalid(action, "{}")
            assertInvalid(action, "")
            assertInvalid(action, "   ")
        }
    }

    @Test
    fun `patch skill without body is rejected`() {
        assertInvalid(EvolutionAction.PATCH_SKILL, """{"description":"only description"}""")
        assertInvalid(EvolutionAction.PATCH_SKILL, """{"body":""}""")
        assertInvalid(EvolutionAction.PATCH_SKILL, """{"body":"   "}""")
    }

    @Test
    fun `retire skill without reason is rejected`() {
        assertInvalid(EvolutionAction.RETIRE_SKILL, """{"reason":""}""")
        assertInvalid(EvolutionAction.RETIRE_SKILL, """{"unrelated":"x"}""")
    }

    @Test
    fun `promote to hand with missing fields is rejected`() {
        assertInvalid(EvolutionAction.PROMOTE_TO_HAND, """{"handName":"h"}""")
        assertInvalid(EvolutionAction.PROMOTE_TO_HAND, """{"handName":"h","steps":[]}""")
        assertInvalid(EvolutionAction.PROMOTE_TO_HAND, """{"steps":[{"tool":"web_search"}]}""")
        assertInvalid(EvolutionAction.PROMOTE_TO_HAND, """{"handName":"h","steps":[{"tool":""}]}""")
    }

    @Test
    fun `consolidate memories with missing fields is rejected`() {
        val ctx = context(targetId = "m1", shownMemoryIds = setOf("m1", "m2"))
        assertInvalid(EvolutionAction.CONSOLIDATE_MEMORIES, """{"memoryIds":["m1","m2"]}""", ctx)
        assertInvalid(EvolutionAction.CONSOLIDATE_MEMORIES, """{"consolidatedContent":"c"}""", ctx)
        assertInvalid(EvolutionAction.CONSOLIDATE_MEMORIES, """{"memoryIds":["m1"],"consolidatedContent":"c"}""", ctx)
    }

    @Test
    fun `malformed json is rejected for every action`() {
        for (action in EvolutionAction.entries) {
            assertInvalid(action, "not json at all")
            assertInvalid(action, """{"body": """)
        }
    }

    // ── PATCH_SKILL semantic rules ──────────────────────────────

    @Test
    fun `patch skill identical to current body is rejected`() {
        val ctx = context(currentSkillBody = "same body")
        assertInvalid(EvolutionAction.PATCH_SKILL, """{"body":"same body"}""", ctx)
    }

    @Test
    fun `patch skill over length cap is rejected`() {
        val huge = "x".repeat(24_001)
        assertInvalid(EvolutionAction.PATCH_SKILL, """{"body":"$huge"}""")
    }

    @Test
    fun `patch skill with credential leak is rejected`() {
        assertInvalid(
            EvolutionAction.PATCH_SKILL,
            """{"body":"call the api with sk-abcdEFGH1234567890wxyz please"}""",
        )
    }

    @Test
    fun `valid skill patch is accepted and canonicalized`() {
        val result = validator.validate(
            EvolutionAction.PATCH_SKILL,
            """{"description":"better","body":"new improved body","extraField":"ignored"}""",
            context(currentSkillBody = "old body"),
        )
        assertTrue(result is EvolutionPatchValidator.Result.Valid)
        val canonical = (result as EvolutionPatchValidator.Result.Valid).canonicalJson
        val decoded = EvolutionPatchJson.json.decodeFromString<SkillPatch>(canonical)
        assertEquals("new improved body", decoded.body)
        assertEquals("better", decoded.description)
    }

    // ── PROMOTE_TO_HAND registry rule ───────────────────────────

    @Test
    fun `promote to hand step with unknown tool is rejected`() {
        assertInvalid(
            EvolutionAction.PROMOTE_TO_HAND,
            """{"handName":"h","steps":[{"tool":"imaginary_tool","args":{}}]}""",
        )
    }

    @Test
    fun `promote to hand with registered tools is accepted`() {
        val result = validator.validate(
            EvolutionAction.PROMOTE_TO_HAND,
            """{"handName":"digest","triggerPhrase":"daily digest","steps":[
                {"tool":"web_search","args":{"query":"news"}},
                {"tool":"send_email","args":{"to":"me"}}
            ]}""",
            context(),
        )
        assertTrue(result is EvolutionPatchValidator.Result.Valid)
    }

    // ── CONSOLIDATE_MEMORIES safety property ────────────────────

    @Test
    fun `consolidate memories with hallucinated id not shown to the model is rejected`() {
        val ctx = context(targetId = "m1", shownMemoryIds = setOf("m1", "m2", "m3"))
        // "m9" was never shown — a hallucinated deletion must be blocked.
        assertInvalid(
            EvolutionAction.CONSOLIDATE_MEMORIES,
            """{"memoryIds":["m1","m9"],"consolidatedContent":"merged"}""",
            ctx,
        )
    }

    @Test
    fun `consolidate memories must include the target id`() {
        val ctx = context(targetId = "m1", shownMemoryIds = setOf("m1", "m2", "m3"))
        assertInvalid(
            EvolutionAction.CONSOLIDATE_MEMORIES,
            """{"memoryIds":["m2","m3"],"consolidatedContent":"merged"}""",
            ctx,
        )
    }

    @Test
    fun `consolidate memories needs at least 2 distinct ids`() {
        val ctx = context(targetId = "m1", shownMemoryIds = setOf("m1", "m2"))
        assertInvalid(
            EvolutionAction.CONSOLIDATE_MEMORIES,
            """{"memoryIds":["m1","m1"],"consolidatedContent":"merged"}""",
            ctx,
        )
    }

    @Test
    fun `valid consolidation subset is accepted`() {
        val ctx = context(targetId = "m1", shownMemoryIds = setOf("m1", "m2", "m3"))
        val result = validator.validate(
            EvolutionAction.CONSOLIDATE_MEMORIES,
            """{"memoryIds":["m1","m3"],"consolidatedContent":"one merged memory","category":"fact"}""",
            ctx,
        )
        assertTrue(result is EvolutionPatchValidator.Result.Valid)
    }
}
