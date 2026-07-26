package com.aura.world

import com.aura.kg.EdgeEntity
import com.aura.kg.KgId
import com.aura.kg.KnowledgeGraphDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

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
        assert(captured.captured.detailJson.contains("turn_kotlin"))
    }
}
