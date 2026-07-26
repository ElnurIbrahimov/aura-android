package com.aura.world

import com.aura.kg.EdgeEntity
import com.aura.kg.KgId
import com.aura.kg.KnowledgeGraphDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class BeliefConflictProbeTest {

    private val kgDao = mockk<KnowledgeGraphDao>(relaxed = true)
    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val evidenceDao = mockk<EvidenceDao>(relaxed = true)
    private val reviser = mockk<BeliefReviser>(relaxed = true)
    private fun probe() = BeliefConflictProbe(kgDao, beliefDao, evidenceDao, reviser)

    // "located_at" is the one predicate the probe currently treats as
    // single-valued (see BeliefConflictProbe.singleValuedPredicates).
    private fun edge(target: String, type: String = "LOCATED_AT") = EdgeEntity(
        id = "e_$target",
        type = type,
        sourceId = KgId.USER_NODE_ID,
        targetId = target,
        confidence = 0.9f,
        createdAt = 1_000L,
        lastReinforced = 2_000L,
    )

    @Test
    fun `same value is not a conflict`() = runBlocking {
        coEvery { kgDao.getNode(any()) } returns null
        coEvery { beliefDao.active("user", "LOCATED_AT") } returns BeliefEntity(
            id = "b1", subject = "user", predicate = "LOCATED_AT", valueJson = "\"kotlin\"",
        )

        assertEquals(0, probe().check(listOf(edge("kotlin")), now = 5_000L))
        coVerify(exactly = 0) { reviser.applyVerdict(any(), any()) }
    }

    @Test
    fun `different value on the same predicate is a conflict`() = runBlocking {
        coEvery { kgDao.getNode(any()) } returns null
        coEvery { beliefDao.active("user", "LOCATED_AT") } returns BeliefEntity(
            id = "b1", subject = "user", predicate = "LOCATED_AT", valueJson = "\"kotlin\"",
        )
        coEvery { evidenceDao.forBelief("b1") } returns emptyList()
        coEvery { reviser.applyVerdict(any(), any()) } returns true

        assertEquals(1, probe().check(listOf(edge("rust")), now = 5_000L))
    }

    @Test
    fun `edges not about the user are ignored`() = runBlocking {
        val other = edge("rust").copy(sourceId = "someone_else")

        assertEquals(0, probe().check(listOf(other), now = 5_000L))
        coVerify(exactly = 0) { beliefDao.active(any(), any()) }
    }

    @Test
    fun `the incoming belief losing writes nothing at all`() = runBlocking {
        // The existing belief is well supported and recent; the incoming edge
        // is a single weak signal. Nothing should be written — in particular
        // the candidate must not be superseded, because it was never stored.
        val now = 10_000_000L
        coEvery { kgDao.getNode(any()) } returns null
        coEvery { beliefDao.active("user", "LOCATED_AT") } returns BeliefEntity(
            id = "b1", subject = "user", predicate = "LOCATED_AT", valueJson = "\"kotlin\"",
        )
        coEvery { evidenceDao.forBelief("b1") } returns (1..4).map {
            EvidenceEntity(
                id = "ev$it",
                beliefId = "b1",
                source = "user_statement",
                summary = "s$it",
                detailJson = """{"turn":"t$it"}""",
                timestamp = now - it,
            )
        }

        assertEquals(0, probe().check(listOf(edge("rust")), now = now))

        coVerify(exactly = 0) { reviser.applyVerdict(any(), any()) }
        coVerify(exactly = 0) { beliefDao.upsert(any()) }
    }

    @Test
    fun `no existing belief means nothing to revise`() = runBlocking {
        coEvery { beliefDao.active(any(), any()) } returns null

        assertEquals(0, probe().check(listOf(edge("rust")), now = 5_000L))
        coVerify(exactly = 0) { reviser.applyVerdict(any(), any()) }
    }

    @Test
    fun `a many-valued predicate is skipped even with a differing value`() = runBlocking {
        // "uses" is legitimately many-valued (a user can use Kotlin AND
        // Rust), so it must never reach the conflict check at all -- not
        // even the beliefDao lookup, since the probe stays conservative
        // until predicates are judged by value class instead of by name.
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
        )

        assertEquals(0, probe().check(listOf(edge("rust", type = "USES")), now = 5_000L))

        coVerify(exactly = 0) { beliefDao.active(any(), any()) }
        coVerify(exactly = 0) { reviser.applyVerdict(any(), any()) }
        coVerify(exactly = 0) { beliefDao.upsert(any()) }
    }

    @Test
    fun `a single-valued predicate is still processed`() = runBlocking {
        // Sanity check alongside the many-valued skip above: "located_at"
        // must still go all the way through to a revision.
        coEvery { kgDao.getNode(any()) } returns null
        coEvery { beliefDao.active("user", "LOCATED_AT") } returns BeliefEntity(
            id = "b1", subject = "user", predicate = "LOCATED_AT", valueJson = "\"berlin\"",
        )
        coEvery { evidenceDao.forBelief("b1") } returns emptyList()
        coEvery { reviser.applyVerdict(any(), any()) } returns true

        assertEquals(1, probe().check(listOf(edge("lisbon", type = "LOCATED_AT")), now = 5_000L))
        coVerify(exactly = 1) { beliefDao.active("user", "LOCATED_AT") }
    }
}
