package com.aura.evolution

import com.aura.agent.Tool
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import com.aura.providers.ToolParameters
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvolutionPatchAuthorTest {

    private val registry = ToolRegistry().apply {
        register(
            Tool(
                name = "web_search",
                description = "search",
                risk = ToolRisk.READ_ONLY,
                parameters = ToolParameters(),
                execute = { _, _ -> ToolResult.Ok("ok") },
            )
        )
    }
    private val validator = EvolutionPatchValidator(Provider { registry }, EvolutionSafetyGuard())

    private fun skillsStoreWith(skill: Skill?): SkillsStore {
        val store = mockk<SkillsStore>(relaxed = true)
        coEvery { store.awaitLoaded() } just Runs
        every { store.findById(any()) } returns skill
        return store
    }

    private fun candidate(
        action: EvolutionAction = EvolutionAction.PATCH_SKILL,
        domain: EvolutionDomain = EvolutionDomain.SKILL,
        targetId: String = "s1",
    ) = EvolutionCandidateEntity(
        id = "c1",
        domain = domain.name,
        action = action.name,
        targetId = targetId,
        score = 0.85f,
        rationale = "failed 4 times",
        status = CandidateStatus.PENDING.name,
    )

    @Test
    fun `approve with valid patch returns Approved carrying canonical patch json`() = runTest {
        val reflection = mockk<EvolutionReflectionExecutor>()
        coEvery { reflection.reflect(any(), any()) } returns EvolutionReflectionExecutor.Result.Ok(
            """{"decision":"approve","reason":"clear fix","patch":{"body":"better body"}}"""
        )
        val author = EvolutionPatchAuthor(
            reflection, validator, Provider { registry },
            skillsStoreWith(Skill(id = "s1", name = "T", description = "", body = "old body")),
        )

        val result = author.author(candidate())

        assertTrue(result is EvolutionPatchAuthor.Result.Approved, "got $result")
        val approved = result as EvolutionPatchAuthor.Result.Approved
        assertEquals("clear fix", approved.reason)
        val patch = EvolutionPatchJson.json.decodeFromString<SkillPatch>(approved.patchJson)
        assertEquals("better body", patch.body)
    }

    @Test
    fun `fenced json output is defensively stripped`() = runTest {
        val reflection = mockk<EvolutionReflectionExecutor>()
        coEvery { reflection.reflect(any(), any()) } returns EvolutionReflectionExecutor.Result.Ok(
            """
            Here is my review:
            ```json
            {"decision":"approve","reason":"fix","patch":{"body":"fenced body"}}
            ```
            """.trimIndent()
        )
        val author = EvolutionPatchAuthor(
            reflection, validator, Provider { registry },
            skillsStoreWith(Skill(id = "s1", name = "T", description = "", body = "old")),
        )

        val result = author.author(candidate())

        assertTrue(result is EvolutionPatchAuthor.Result.Approved, "got $result")
        val patch = EvolutionPatchJson.json.decodeFromString<SkillPatch>(
            (result as EvolutionPatchAuthor.Result.Approved).patchJson
        )
        assertEquals("fenced body", patch.body)
    }

    @Test
    fun `reject decision maps to Rejected with the model reason`() = runTest {
        val reflection = mockk<EvolutionReflectionExecutor>()
        coEvery { reflection.reflect(any(), any()) } returns EvolutionReflectionExecutor.Result.Ok(
            """{"decision":"reject","reason":"not enough evidence","patch":null}"""
        )
        val author = EvolutionPatchAuthor(
            reflection, validator, Provider { registry },
            skillsStoreWith(Skill(id = "s1", name = "T", description = "", body = "old")),
        )

        val result = author.author(candidate())

        assertTrue(result is EvolutionPatchAuthor.Result.Rejected)
        assertEquals("not enough evidence", (result as EvolutionPatchAuthor.Result.Rejected).reason)
    }

    @Test
    fun `approve with invalid patch is Rejected not Approved`() = runTest {
        val reflection = mockk<EvolutionReflectionExecutor>()
        // Patch body identical to the current body → validator rejects.
        coEvery { reflection.reflect(any(), any()) } returns EvolutionReflectionExecutor.Result.Ok(
            """{"decision":"approve","reason":"looks fine","patch":{"body":"old body"}}"""
        )
        val author = EvolutionPatchAuthor(
            reflection, validator, Provider { registry },
            skillsStoreWith(Skill(id = "s1", name = "T", description = "", body = "old body")),
        )

        val result = author.author(candidate())

        assertTrue(result is EvolutionPatchAuthor.Result.Rejected, "invalid patch must never be Approved: $result")
        assertTrue((result as EvolutionPatchAuthor.Result.Rejected).reason.contains("invalid patch"))
    }

    @Test
    fun `unparseable model output is Rejected`() = runTest {
        val reflection = mockk<EvolutionReflectionExecutor>()
        coEvery { reflection.reflect(any(), any()) } returns EvolutionReflectionExecutor.Result.Ok(
            "I think this is a great idea! approve: true"
        )
        val author = EvolutionPatchAuthor(
            reflection, validator, Provider { registry },
            skillsStoreWith(Skill(id = "s1", name = "T", description = "", body = "old")),
        )

        val result = author.author(candidate())

        assertTrue(result is EvolutionPatchAuthor.Result.Rejected)
    }

    @Test
    fun `transport error surfaces as Error so the candidate stays pending`() = runTest {
        val reflection = mockk<EvolutionReflectionExecutor>()
        coEvery { reflection.reflect(any(), any()) } returns
            EvolutionReflectionExecutor.Result.Error("Reflection timed out", "timeout")
        val author = EvolutionPatchAuthor(
            reflection, validator, Provider { registry },
            skillsStoreWith(Skill(id = "s1", name = "T", description = "", body = "old")),
        )

        val result = author.author(candidate())

        assertTrue(result is EvolutionPatchAuthor.Result.Error)
        assertEquals("timeout", (result as EvolutionPatchAuthor.Result.Error).code)
    }

    @Test
    fun `missing target skill is Rejected without an LLM call`() = runTest {
        val reflection = mockk<EvolutionReflectionExecutor>()
        val author = EvolutionPatchAuthor(
            reflection, validator, Provider { registry },
            skillsStoreWith(null),
        )

        val result = author.author(candidate())

        assertTrue(result is EvolutionPatchAuthor.Result.Rejected)
        io.mockk.coVerify(exactly = 0) { reflection.reflect(any(), any()) }
    }

    @Test
    fun `consolidation shows only real memories and hallucinated ids are rejected`() = runTest {
        val target = MemoryEntity(id = "m1", content = "likes tea", source = "user", category = "preference")
        val related = MemoryEntity(id = "m2", content = "tea over coffee", source = "user", category = "preference")
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        coEvery { memoryStore.get("m1") } returns target
        coEvery { memoryStore.recent(any()) } returns listOf(related)

        val reflection = mockk<EvolutionReflectionExecutor>()
        val prompt = slot<String>()
        // Model hallucinates "m99" which was never shown.
        coEvery { reflection.reflect(any(), capture(prompt)) } returns EvolutionReflectionExecutor.Result.Ok(
            """{"decision":"approve","reason":"merge","patch":{"memoryIds":["m1","m99"],"consolidatedContent":"tea"}}"""
        )
        val author = EvolutionPatchAuthor(
            reflection, validator, Provider { registry },
            skillsStore = null, memoryStore = memoryStore,
        )

        val result = author.author(
            candidate(action = EvolutionAction.CONSOLIDATE_MEMORIES, domain = EvolutionDomain.MEMORY, targetId = "m1")
        )

        // The prompt listed both real ids…
        assertTrue(prompt.captured.contains("m1"))
        assertTrue(prompt.captured.contains("m2"))
        // …and the hallucinated deletion was blocked.
        assertTrue(result is EvolutionPatchAuthor.Result.Rejected, "got $result")
        assertTrue((result as EvolutionPatchAuthor.Result.Rejected).reason.contains("never shown"))
    }

    @Test
    fun `consolidation with no related memories is Rejected without an LLM call`() = runTest {
        val target = MemoryEntity(id = "m1", content = "solo memory", source = "user", category = "fact")
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        coEvery { memoryStore.get("m1") } returns target
        coEvery { memoryStore.recent(any()) } returns emptyList()
        val reflection = mockk<EvolutionReflectionExecutor>()
        val author = EvolutionPatchAuthor(
            reflection, validator, Provider { registry },
            skillsStore = null, memoryStore = memoryStore,
        )

        val result = author.author(
            candidate(action = EvolutionAction.CONSOLIDATE_MEMORIES, domain = EvolutionDomain.MEMORY, targetId = "m1")
        )

        assertTrue(result is EvolutionPatchAuthor.Result.Rejected)
        io.mockk.coVerify(exactly = 0) { reflection.reflect(any(), any()) }
    }
}
