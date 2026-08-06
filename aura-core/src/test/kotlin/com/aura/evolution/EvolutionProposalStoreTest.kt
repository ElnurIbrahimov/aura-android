package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import io.mockk.coVerify
import io.mockk.mockk
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvolutionProposalStoreTest {
    private lateinit var db: EvolutionDatabase
    private lateinit var store: EvolutionProposalStore
    private lateinit var rollback: EvolutionRollbackManager

    private lateinit var handRepository: com.aura.hands.HandRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = EvolutionProposalStore(db.proposalDao(), db.revisionDao(), db.candidateDao(), EvolutionMetrics(), EvolutionSafetyGuard())
        handRepository = mockk(relaxed = true)
        rollback = EvolutionRollbackManager(db.proposalDao(), db.revisionDao(), EvolutionMetrics(), null, null, handRepository)
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun `creates proposal from candidate and marks candidate promoted`() = runBlocking {
        val candidate = EvolutionCandidateEntity(
            id = "c1",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.PATCH_SKILL.name,
            targetId = "skill_1",
            score = 0.9f,
            rationale = "skill keeps failing",
            argsJson = """{"body":"fixed body"}""",
        )
        db.candidateDao().upsert(candidate)
        val proposal = store.fromCandidate(candidate)
        assertEquals(EvolutionDomain.SKILL.name, proposal.domain)
        assertEquals(EvolutionAction.PATCH_SKILL.name, proposal.action)
        assertEquals("""{"body":"fixed body"}""", proposal.patchJson)
        val promoted = db.candidateDao().getById(candidate.id)
        assertEquals(CandidateStatus.PROMOTED.name, promoted?.status)
    }

    @Test
    fun `rollback of applied promote-to-hand resolves proposal and deletes the hand`() = runBlocking {
        val snapshot = EvolutionPatchJson.json.encodeToString(
            PromoteToHandSnapshot.serializer(),
            PromoteToHandSnapshot(handId = "hand-1", handName = "digest"),
        )
        val proposal = EvolutionProposalEntity(
            id = "p1",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.PROMOTE_TO_HAND.name,
            targetId = "skill_1",
            status = ProposalStatus.APPLIED.name,
            resolvedAt = System.currentTimeMillis(),
            rollbackSnapshotJson = snapshot,
        )
        db.proposalDao().upsert(proposal)
        val result = rollback.rollback("p1") as EvolutionRollbackManager.RollbackResult.Ok
        assertTrue(result.summary.contains("removed"))
        coVerify { handRepository.deleteById("hand-1") }
        val rolled = db.proposalDao().getById("p1")
        assertEquals(ProposalStatus.ROLLED_BACK.name, rolled?.status)
    }
}
