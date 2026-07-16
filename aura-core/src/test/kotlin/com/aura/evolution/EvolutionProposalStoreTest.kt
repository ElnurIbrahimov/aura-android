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
import com.aura.proactive.ProactiveEventDao
import io.mockk.coEvery
import io.mockk.mockk
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvolutionProposalStoreTest {
    private lateinit var db: EvolutionDatabase
    private lateinit var store: EvolutionProposalStore
    private lateinit var rollback: EvolutionRollbackManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = EvolutionProposalStore(db.proposalDao(), db.revisionDao(), db.candidateDao(), EvolutionMetrics(), EvolutionSafetyGuard())
        val proactiveDao = mockk<ProactiveEventDao>(relaxed = true)
        coEvery { proactiveDao.deleteByCorrelationTag(any()) } returns 1
        rollback = EvolutionRollbackManager(db.proposalDao(), db.revisionDao(), EvolutionMetrics(), null, null, proactiveDao)
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun `creates proposal from candidate and marks candidate promoted`() = runBlocking {
        val candidate = EvolutionCandidateEntity(
            id = "c1",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.CREATE_SKILL.name,
            targetId = "skill_new",
            score = 0.9f,
            rationale = "user needs a summarize skill",
        )
        db.candidateDao().upsert(candidate)
        val proposal = store.fromCandidate(candidate)
        assertEquals(EvolutionDomain.SKILL.name, proposal.domain)
        assertEquals(EvolutionAction.CREATE_SKILL.name, proposal.action)
        val promoted = db.candidateDao().getById(candidate.id)
        assertEquals(CandidateStatus.PROMOTED.name, promoted?.status)
    }

    @Test
    fun `rollback returns before ciphertext when revision exists`() = runBlocking {
        val proposal = EvolutionProposalEntity(
            id = "p1",
            domain = EvolutionDomain.PROACTIVE.name,
            action = EvolutionAction.NEW_PROACTIVE_RULE.name,
            targetId = "",
            status = ProposalStatus.APPLIED.name,
            resolvedAt = System.currentTimeMillis(),
        )
        db.proposalDao().upsert(proposal)
        db.revisionDao().upsert(
            EvolutionRevisionEntity(
                id = "r1",
                domain = EvolutionDomain.SKILL.name,
                targetId = "skill_1",
                proposalId = "p1",
                snapshotCiphertext = "old-skill",
                
            )
        )
        val result = rollback.rollback("p1") as EvolutionRollbackManager.RollbackResult.Ok
        assertTrue(result.summary.contains("removed"))
        val rolled = db.proposalDao().getById("p1")
        assertEquals(ProposalStatus.ROLLED_BACK.name, rolled?.status)
    }
}
