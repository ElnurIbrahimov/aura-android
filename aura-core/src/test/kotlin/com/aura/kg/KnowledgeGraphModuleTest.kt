package com.aura.kg

import com.aura.provenance.ConversationProvenance
import com.aura.world.BeliefConflictProbe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Pins `KnowledgeGraphModule.provideKnowledgeGraphRepository` to actually pass
 * the [BeliefConflictProbe] through to [KnowledgeGraphRepository].
 *
 * `KnowledgeGraphRepository`'s `beliefConflictProbe` constructor param is
 * nullable with a `null` default so existing direct constructions (in other
 * tests) keep compiling — but that same default is exactly what silently
 * disables belief revision in production if the Hilt provider ever drops the
 * argument. This test exists so that regression fails loudly here instead of
 * shipping a build where `saveGraph` never calls the probe.
 */
class KnowledgeGraphModuleTest {

    @Test
    fun `provideKnowledgeGraphRepository wires the belief conflict probe into saveGraph`() = runTest {
        val dao = mockk<KnowledgeGraphDao>(relaxed = true)
        val probe = mockk<BeliefConflictProbe>(relaxed = true)
        coEvery { probe.check(any(), any()) } returns 0

        val repo = KnowledgeGraphModule.provideKnowledgeGraphRepository(dao, probe)
        val edge = KgEdge(id = "", type = EdgeType.USES, sourceId = KgId.USER_NODE_ID, targetId = "kotlin")
        val provenance = ConversationProvenance("conv-1", 123L)

        repo.saveGraph(emptyList(), listOf(edge), provenance)

        coVerify(exactly = 1) { probe.check(any(), any()) }
    }
}
