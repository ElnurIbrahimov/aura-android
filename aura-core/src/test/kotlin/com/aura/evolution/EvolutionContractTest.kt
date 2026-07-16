package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvolutionContractTest {
    private lateinit var db: EvolutionDatabase
    private lateinit var evidenceDao: EvolutionEvidenceDao
    private lateinit var candidateDao: EvolutionCandidateDao
    private lateinit var proposalDao: EvolutionProposalDao
    private lateinit var revisionDao: EvolutionRevisionDao
    private lateinit var settingsDao: EvolutionSettingsDao
    private lateinit var settingsStore: EvolutionSettingsStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        evidenceDao = db.evidenceDao()
        candidateDao = db.candidateDao()
        proposalDao = db.proposalDao()
        revisionDao = db.revisionDao()
        settingsDao = db.settingsDao()
        settingsStore = EvolutionSettingsStore(settingsDao)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `settings store creates defaults for all domains`() = runBlocking {
        val all = settingsStore.all()
        assertEquals(EvolutionDomain.entries.size, all.size)
        EvolutionDomain.entries.forEach { domain ->
            val s = settingsStore.get(domain)
            assertNotNull(s)
            assertEquals(domain.name, s.domain)
            assertEquals(true, s.enabled)
            assertEquals(false, s.autoApplyApproved)
        }
    }

    @Test
    fun `can write and read back evidence candidate proposal and revision`() = runBlocking {
        val evidence = EvolutionEvidenceEntity(
            id = "ev1",
            domain = EvolutionDomain.SKILL.name,
            kind = "skill_invoked",
            sourceEntityId = "skill_1",
        )
        evidenceDao.upsert(evidence)
        assertEquals(1, evidenceDao.byKind(EvolutionDomain.SKILL.name, "skill_invoked").size)

        val candidate = EvolutionCandidateEntity(
            id = "c1",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.PATCH_SKILL.name,
            targetId = "skill_1",
            score = 0.85f,
            status = CandidateStatus.PENDING.name,
        )
        candidateDao.upsert(candidate)
        assertEquals(1, candidateDao.byStatus(EvolutionDomain.SKILL.name, CandidateStatus.PENDING.name).size)

        val proposal = EvolutionProposalEntity(
            id = "p1",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.PATCH_SKILL.name,
            targetId = "skill_1",
            title = "Fix greeting skill",
        )
        proposalDao.upsert(proposal)
        assertEquals(1, proposalDao.byDomain(EvolutionDomain.SKILL.name).size)

        val revision = EvolutionRevisionEntity(
            id = "r1",
            domain = EvolutionDomain.SKILL.name,
            targetId = "skill_1",
            proposalId = "p1",
            summary = "Initial revision",
        )
        revisionDao.upsert(revision)
        assertEquals(1, revisionDao.history(EvolutionDomain.SKILL.name, "skill_1").size)
    }

    @Test
    fun `proposal status update is reflected in observeOpen`() = runBlocking {
        val proposal = EvolutionProposalEntity(
            id = "p2",
            domain = EvolutionDomain.MEMORY.name,
            action = EvolutionAction.CONSOLIDATE_MEMORIES.name,
            targetId = "m1",
        )
        proposalDao.upsert(proposal)
        val open = proposalDao.observeOpen()
        val openList = open.first()
        assertEquals(1, openList.size)

        proposalDao.resolve(proposal.id, ProposalStatus.APPLIED.name, "applied ok")
        val closedList = proposalDao.observeOpen().first()
        assertEquals(0, closedList.size)
    }
}
