package com.aura.tools

import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.creative.CreativeBranchEntity
import com.aura.creative.CreativeBranchStore
import com.aura.creative.CreativeProjectStore
import com.aura.creative.livingworld.LivingEventEntity
import com.aura.creative.livingworld.LivingWorldEntity
import com.aura.creative.livingworld.LivingWorldStore
import com.aura.creative.livingworld.WorldEngine
import com.aura.creative.livingworld.WorldState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The plot-mining tool: reads only, ranks by the score the engine already paid for. */
class LivingWorldQueryToolTest {

    private val projectStore = mockk<CreativeProjectStore>(relaxed = true)
    private val branchStore = mockk<CreativeBranchStore>(relaxed = true)
    private val livingWorldStore = mockk<LivingWorldStore>(relaxed = true)

    private fun tool() = LivingWorldQueryTool(projectStore, branchStore, livingWorldStore)

    private fun world(id: String, branchId: String, parent: String = "", forkedAt: Long = 0L) =
        LivingWorldEntity(
            id = id, projectId = "p1", branchId = branchId, rootSeed = 7L,
            parentWorldId = parent, forkedAtTick = forkedAt, worldEpochMs = 0L,
            currentTick = 50L, stateJson = "{}", createdAt = 0L, updatedAt = 0L,
        )

    private fun branch(id: String, name: String) = CreativeBranchEntity(
        id = id, projectId = "p1", name = name, status = "active",
    )

    private fun moment(tick: Long, kind: String, notability: Double, summary: String) =
        LivingEventEntity(
            id = "w1#$tick.0", worldId = "w1", branchId = "b-main", tickIndex = tick,
            seq = 0, kind = kind, actorId = "f_a", summary = summary, notability = notability,
        )

    private fun call(vararg args: Pair<String, Any>) =
        ToolCall(id = "t1", name = "living_world_query", arguments = mapOf(*args))

    private fun context() = mockk<ToolContext>(relaxed = true)

    @Test
    fun `drama lists the engine's ranking, most notable first`() = runBlocking {
        coEvery { projectStore.get("p1") } returns mockk(relaxed = true)
        coEvery { branchStore.forProject("p1") } returns listOf(branch("b-main", "main"))
        coEvery { livingWorldStore.forProjectAndBranch("p1", "b-main") } returns world("w1", "b-main")
        coEvery { livingWorldStore.topNotableOfKinds("w1", any(), any()) } returns listOf(
            moment(40, WorldEngine.KIND_BELIEF_REVEAL, 0.91, "Bramwatch discovers the truth of Ashfall's might."),
            moment(12, WorldEngine.KIND_CLAIM_WON, 0.55, "Ashfall took territory from Cormere."),
        )

        val result = tool().tool.execute(call("projectId" to "p1", "mode" to "drama"), context())

        val text = (result as ToolResult.Ok).output
        assertTrue(text.indexOf("discovers the truth") < text.indexOf("took territory"), text)
        assertTrue(text.contains("0.91"), "the score must be visible")
    }

    @Test
    fun `divergence names the first parting and the standings, page-honestly`() = runBlocking {
        coEvery { projectStore.get("p1") } returns mockk(relaxed = true)
        coEvery { branchStore.forProject("p1") } returns listOf(
            branch("b-main", "main"), branch("b-fork", "what-if"),
        )
        coEvery { livingWorldStore.forProjectAndBranch("p1", "b-main") } returns world("w1", "b-main")
        coEvery { livingWorldStore.forProjectAndBranch("p1", "b-fork") } returns
            world("w2", "b-fork", parent = "w1", forkedAt = 30L)
        coEvery { livingWorldStore.ascAfter("w1", 30L, any()) } returns listOf(
            moment(31, WorldEngine.KIND_STOCK_SHIFT, 0.1, "A grain rose."),
        )
        coEvery { livingWorldStore.ascAfter("w2", 30L, any()) } returns listOf(
            moment(31, WorldEngine.KIND_CLAIM_WON, 0.5, "A took land."),
        )
        every { livingWorldStore.decode(any()) } returns WorldState()

        val result = tool().tool.execute(
            call("projectId" to "p1", "mode" to "divergence", "branch" to "what-if"),
            context(),
        )

        val text = (result as ToolResult.Ok).output
        assertTrue(text.contains("part at"), text)
        assertTrue(text.contains("Year"), "the parting must be dated in world time")
    }

    @Test
    fun `an unknown project is a readable error and the tool stays read-only`() = runBlocking {
        coEvery { projectStore.get("p1") } returns null

        val result = tool().tool.execute(call("projectId" to "p1", "mode" to "drama"), context())

        assertTrue(result is ToolResult.Error)
        assertEquals(ToolRisk.READ_ONLY, tool().tool.risk)
    }
}
