package com.aura.skills

import com.aura.evolution.EvolutionDomain
import com.aura.evolution.EvolutionEvidenceDao
import com.aura.evolution.EvolutionEvidenceEntity
import com.aura.evolution.EvolutionEvidenceRecorder
import com.aura.evolution.EvolutionHooks
import com.aura.evolution.EvolutionSkillRevisionStore
import com.aura.security.SecureDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SkillsStoreEvolutionHookTest {

    private lateinit var secureDataStore: SecureDataStore
    private lateinit var revisionStore: EvolutionSkillRevisionStore
    private lateinit var evidenceDao: EvolutionEvidenceDao
    private lateinit var hooks: EvolutionHooks
    private lateinit var store: SkillsStore

    @Before
    fun setUp() {
        secureDataStore = mockk(relaxed = true)
        revisionStore = mockk(relaxed = true)
        evidenceDao = mockk(relaxed = true)
        hooks = EvolutionHooks(EvolutionEvidenceRecorder(evidenceDao))
        store = SkillsStore(secureDataStore, revisionStore, hooks)
    }

    @Test
    fun `add emits skill_added evidence`() = runBlocking {
        val captured = mutableListOf<EvolutionEvidenceEntity>()
        coEvery { evidenceDao.upsert(capture(captured)) } returns Unit
        coEvery { secureDataStore.getString("aura_skills_v1") } returns "[]"

        store.add(Skill(id = "s1", name = "Test", description = "", body = "body"))

        assertTrue(captured.any { it.kind == "skill_added" && it.sourceEntityId == "s1" })
    }

    @Test
    fun `update emits skill_edited evidence`() = runBlocking {
        val captured = mutableListOf<EvolutionEvidenceEntity>()
        coEvery { evidenceDao.upsert(capture(captured)) } returns Unit
        coEvery { secureDataStore.getString("aura_skills_v1") } returns """[{"id":"s1","name":"Old","description":"","body":"old"}]"""

        store.update(Skill(id = "s1", name = "New", description = "", body = "new"))

        assertTrue(captured.any { it.kind == "skill_edited" && it.sourceEntityId == "s1" })
    }

    @Test
    fun `remove emits skill_removed evidence`() = runBlocking {
        val captured = mutableListOf<EvolutionEvidenceEntity>()
        coEvery { evidenceDao.upsert(capture(captured)) } returns Unit
        coEvery { secureDataStore.getString("aura_skills_v1") } returns """[{"id":"s1","name":"Old","description":"","body":"old"}]"""

        store.remove("s1")

        assertTrue(captured.any { it.kind == "skill_removed" && it.sourceEntityId == "s1" })
    }
}
