package com.aura.world

import com.aura.kg.EdgeEntity
import com.aura.kg.KgId
import com.aura.kg.KnowledgeGraphDao
import com.aura.kg.NodeEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeliefPromoterTest {

    private val kgDao = mockk<KnowledgeGraphDao>(relaxed = true)
    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val evidenceDao = mockk<EvidenceDao>(relaxed = true)

    private fun promoter() = BeliefPromoter(kgDao, beliefDao, evidenceDao)

    private fun edge(
        target: String,
        confidence: Float = 0.9f,
        createdAt: Long = 1_000L,
        lastReinforced: Long = 2_000L,
    ) = EdgeEntity(
        id = "e_$target",
        type = "USES",
        sourceId = KgId.USER_NODE_ID,
        targetId = target,
        confidence = confidence,
        sourceTurnId = "turn_$target",
        createdAt = createdAt,
        lastReinforced = lastReinforced,
        sourceConversationId = "conv1",
        sourceTurnTimestamp = lastReinforced,
    )

    @Test
    fun `promotes a reinforced high-confidence edge about the user`() = runBlocking {
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin"))
        coEvery { beliefDao.active(any(), any()) } returns null

        val promoted = promoter().promote(now = 5_000L)

        assertEquals(1, promoted)
        val captured = slot<BeliefEntity>()
        coVerify { beliefDao.upsert(capture(captured)) }
        assertEquals("user", captured.captured.subject)
        assertEquals("USES", captured.captured.predicate)
        assertEquals("active", captured.captured.status)
    }

    @Test
    fun `skips an edge seen only once`() = runBlocking {
        // lastReinforced == createdAt means the edge has never been seen
        // again. One offhand remark is not a belief.
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns
            listOf(edge("kotlin", createdAt = 1_000L, lastReinforced = 1_000L))
        coEvery { beliefDao.active(any(), any()) } returns null

        assertEquals(0, promoter().promote(now = 5_000L))
        coVerify(exactly = 0) { beliefDao.upsert(any()) }
    }

    @Test
    fun `skips a low-confidence edge`() = runBlocking {
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin", confidence = 0.5f))
        coEvery { beliefDao.active(any(), any()) } returns null

        assertEquals(0, promoter().promote(now = 5_000L))
        coVerify(exactly = 0) { beliefDao.upsert(any()) }
    }

    @Test
    fun `re-promoting an unchanged edge verifies instead of duplicating`() = runBlocking {
        coEvery { kgDao.getNode(any()) } returns null
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin"))
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1",
            subject = "user",
            predicate = "USES",
            valueJson = "\"kotlin\"",
        )

        assertEquals(1, promoter().promote(now = 5_000L))
        coVerify(exactly = 1) { beliefDao.verify("b1", any(), 5_000L) }
        coVerify(exactly = 0) { beliefDao.upsert(any()) }
    }

    @Test
    fun `writes evidence carrying the source turn`() = runBlocking {
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin"))
        coEvery { beliefDao.active(any(), any()) } returns null

        promoter().promote(now = 5_000L)

        val captured = slot<EvidenceEntity>()
        coVerify { evidenceDao.upsert(capture(captured)) }
        assertEquals("kg_edge", captured.captured.source)
        // "because Z" has to resolve back to a turn — this is that link.
        assertTrue(captured.captured.detailJson.contains("turn_kotlin"))
    }

    @Test
    fun `reinforcing an existing belief writes an evidence row`() = runBlocking {
        // Round-1 fix: the reinforcement branch used to `continue` right
        // after `verify()` without ever writing evidence, so no belief in
        // production could hold more than one evidence row and
        // BeliefArbiter.corroboration() was permanently stuck at 1/4.
        coEvery { kgDao.getNode(any()) } returns null
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin"))
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1",
            subject = "user",
            predicate = "USES",
            valueJson = "\"kotlin\"",
        )

        promoter().promote(now = 5_000L)

        coVerify(exactly = 1) { evidenceDao.upsert(any()) }
    }

    @Test
    fun `re-promoting the same edge twice produces the same evidence id`() = runBlocking {
        coEvery { kgDao.getNode(any()) } returns null
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1",
            subject = "user",
            predicate = "USES",
            valueJson = "\"kotlin\"",
        )
        val capturedEvidence = mutableListOf<EvidenceEntity>()
        coEvery { evidenceDao.upsert(capture(capturedEvidence)) } returns Unit
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin"))

        promoter().promote(now = 5_000L)
        promoter().promote(now = 6_000L)

        assertEquals(2, capturedEvidence.size)
        // Same belief, same sourceTurnId both times -> deterministic id
        // means the second upsert REPLACEs the first row rather than
        // accumulating a duplicate for a turn that was never re-seen.
        assertEquals(capturedEvidence[0].id, capturedEvidence[1].id)
    }

    @Test
    fun `an edge with a different sourceTurnId produces a different evidence id`() = runBlocking {
        coEvery { kgDao.getNode(any()) } returns null
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1",
            subject = "user",
            predicate = "USES",
            valueJson = "\"kotlin\"",
        )
        val capturedEvidence = mutableListOf<EvidenceEntity>()
        coEvery { evidenceDao.upsert(capture(capturedEvidence)) } returns Unit

        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin"))
        promoter().promote(now = 5_000L)

        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns
            listOf(edge("kotlin").copy(sourceTurnId = "turn_kotlin_2"))
        promoter().promote(now = 6_000L)

        assertEquals(2, capturedEvidence.size)
        // A genuinely new supporting turn must add a new evidence row, not
        // replace the old one -- this is what makes corroboration count
        // distinct turns for real.
        assertTrue(capturedEvidence[0].id != capturedEvidence[1].id)
    }

    @Test
    fun `evidence timestamp records when the edge was seen, not when promote ran`() = runBlocking {
        // Round-3 fix: toEvidence used to stamp `timestamp = now` (the time
        // promote() ran). promote() runs every dream cycle and qualifies()
        // stays true forever once an edge is reinforced, so that would pin
        // BeliefArbiter.recency() -- weight 0.40, the heaviest signal -- at
        // ~1.0 forever. The timestamp must come from the edge's own
        // lastReinforced instead, which is deliberately far from `now` here
        // so the assertion can't pass by coincidence.
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns
            listOf(edge("kotlin", lastReinforced = 2_000L))
        coEvery { beliefDao.active(any(), any()) } returns null

        promoter().promote(now = 9_999_999L)

        val captured = slot<EvidenceEntity>()
        coVerify { evidenceDao.upsert(capture(captured)) }
        assertEquals(2_000L, captured.captured.timestamp)
    }

    @Test
    fun `re-promoting the same unchanged edge with different now values keeps the same evidence timestamp`() = runBlocking {
        // Regression guard for the round-3 fix: two promote() calls with
        // very different `now` values must not move the evidence timestamp,
        // because the edge itself was not seen again between them.
        coEvery { kgDao.getNode(any()) } returns null
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1",
            subject = "user",
            predicate = "USES",
            valueJson = "\"kotlin\"",
        )
        val capturedEvidence = mutableListOf<EvidenceEntity>()
        coEvery { evidenceDao.upsert(capture(capturedEvidence)) } returns Unit
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns
            listOf(edge("kotlin", lastReinforced = 2_000L))

        promoter().promote(now = 5_000L)
        promoter().promote(now = 9_999_999L)

        assertEquals(2, capturedEvidence.size)
        assertEquals(capturedEvidence[0].timestamp, capturedEvidence[1].timestamp)
        assertEquals(2_000L, capturedEvidence[0].timestamp)
    }

    @Test
    fun `promoted belief stores the node's label, not its hashed id`() = runBlocking {
        // Finding A: sourceId/targetId on an edge are sha256 hashes (see
        // KgId.node), not human-readable text. Every test fixture elsewhere
        // in this suite uses a readable string ("kotlin") as the targetId,
        // which is exactly why this bug survived nine per-task reviews. Use
        // a real hash here so the assertion can't pass by coincidence.
        val nodeId = com.aura.kg.KgId.node(com.aura.kg.NodeType.SKILL, "Kotlin")
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge(nodeId))
        coEvery { beliefDao.active(any(), any()) } returns null
        coEvery { kgDao.getNode(nodeId) } returns NodeEntity(
            id = nodeId,
            label = "Kotlin",
            type = "skill",
        )

        promoter().promote(now = 5_000L)

        val captured = slot<BeliefEntity>()
        coVerify { beliefDao.upsert(capture(captured)) }
        assertEquals("\"Kotlin\"", captured.captured.valueJson)
        assertTrue(nodeId.length == 64, "test fixture must actually be sha256-shaped")
        assertTrue(!captured.captured.valueJson.contains(nodeId))
    }

    @Test
    fun `does not reinforce a belief whose value the new evidence contradicts`() = runBlocking {
        // Finding B: an existing belief must never be verified/reinforced by
        // an edge carrying a DIFFERENT value for the same subject+predicate.
        // That is a conflict for BeliefConflictProbe/the adjudicator to
        // resolve, not something promotion may paper over by refreshing
        // recency and corroboration on the outdated belief.
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("rust"))
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1",
            subject = "user",
            predicate = "USES",
            valueJson = "\"kotlin\"",
        )

        val promoted = promoter().promote(now = 5_000L)

        assertEquals(0, promoted)
        coVerify(exactly = 0) { beliefDao.verify(any(), any(), any()) }
        coVerify(exactly = 0) { evidenceDao.upsert(any()) }
        coVerify(exactly = 0) { beliefDao.upsert(any()) }
    }
}
