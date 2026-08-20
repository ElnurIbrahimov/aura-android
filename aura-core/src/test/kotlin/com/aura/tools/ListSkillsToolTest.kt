package com.aura.tools

import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.security.SecureDataStore
import com.aura.skills.BuiltinSkills
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the model finds out which skills exist.
 *
 * Before this, it could not — not without guessing. `use_skill` returns the list of
 * available names only inside the *error* for a name that does not exist, so discovery
 * meant calling a tool wrongly on purpose and reading the failure. With two creative-craft
 * skills nobody invoked, that never mattered. With ten general ones it decides whether the
 * feature is used at all.
 *
 * Deliberately a tool rather than a line in the system prompt. The prompt is assembled
 * inside the loop's memoised context block, and a tool description baked at registry build
 * time goes stale the moment the user writes a skill of their own — this cannot.
 */
class ListSkillsToolTest {

    private fun store(initial: Map<String, String?> = emptyMap()): SkillsStore {
        val secure = mockk<SecureDataStore>(relaxed = true)
        coEvery { secure.getString(any()) } answers { firstArg<String>().let { initial[it] } }
        coEvery { secure.putString(any(), any()) } returns Unit
        return SkillsStore(secure)
    }

    private val ctx = ToolContext(conversationId = "c1")

    private suspend fun run(store: SkillsStore): String {
        val tool = ListSkillsTool(store).tool
        val call = com.aura.agent.ToolCall(id = "1", name = "list_skills", arguments = emptyMap())
        return when (val r = tool.execute(call, ctx)) {
            is ToolResult.Ok -> r.output
            else -> error("list_skills failed: $r")
        }
    }

    @Test
    fun `it names every skill and says what each is for`() = runTest {
        val s = store()
        s.seedBuiltins(BuiltinSkills.seeds())

        val output = run(s)

        BuiltinSkills.seeds().forEach { seed ->
            assertTrue(seed.name in output, "${seed.name} is invocable but not listed")
            assertTrue(seed.description in output, "${seed.name} is listed with no description")
        }
    }

    @Test
    fun `it does not return the bodies`() = runTest {
        // The whole point of the split: an index is cheap, ten procedures is most of a
        // context window. use_skill fetches the body once the model has chosen.
        val s = store()
        s.seedBuiltins(BuiltinSkills.seeds())

        val output = run(s)

        val firstBodyLine = BuiltinSkills.seeds().first().body.lineSequence().first()
        assertTrue(firstBodyLine !in output, "list_skills returned a skill body, not an index")
    }

    @Test
    fun `a skill written just now appears without a restart`() = runTest {
        val s = store()
        s.seedBuiltins(BuiltinSkills.seeds())
        s.add(Skill(name = "feed-the-cat", description = "At 7 and at 6.", body = "Wet food."))

        assertTrue("feed-the-cat" in run(s))
    }

    @Test
    fun `an empty store says so rather than returning nothing`() = runTest {
        val output = run(store())

        assertTrue(output.isNotBlank(), "an empty result reads as a broken tool")
        assertTrue("none" in output.lowercase(), "expected a statement that there are no skills yet")
    }

    @Test
    fun `listing is read-only`() = runTest {
        // Reading a list of the user's own skill names mutates nothing and reaches no
        // network. Anything above READ_ONLY makes the policy engine demand a confirmation
        // for it — the same mismatch use_skill's KDoc records being fixed.
        assertEquals(ToolRisk.READ_ONLY, ListSkillsTool(store()).tool.risk)
    }
}
