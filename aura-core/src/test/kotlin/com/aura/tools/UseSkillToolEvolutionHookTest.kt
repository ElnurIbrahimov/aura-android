package com.aura.tools

import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.evolution.EvolutionDomain
import com.aura.evolution.EvolutionEvidenceRecorder
import com.aura.evolution.EvolutionEvidenceEntity
import com.aura.evolution.EvolutionHooks
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UseSkillToolEvolutionHookTest {

    private lateinit var skillsStore: SkillsStore
    private lateinit var evidenceDao: com.aura.evolution.EvolutionEvidenceDao
    private lateinit var hooks: EvolutionHooks
    private lateinit var tool: UseSkillTool

    @Before
    fun setUp() {
        skillsStore = mockk(relaxed = true)
        evidenceDao = mockk(relaxed = true)
        val recorder = EvolutionEvidenceRecorder(evidenceDao)
        hooks = EvolutionHooks(recorder)
        tool = UseSkillTool(skillsStore, hooks)
    }

    @Test
    fun `invoke existing skill records skill_invoked evidence`() = runBlocking {
        val skill = Skill(id = "s1", name = "Summarize", description = "", body = "Be brief.")
        coEvery { skillsStore.awaitLoaded() } returns Unit
        coEvery { skillsStore.findByName("Summarize") } returns skill
        coEvery { skillsStore.skills } returns MutableStateFlow(listOf(skill))

        val captured = mutableListOf<EvolutionEvidenceEntity>()
        coEvery { evidenceDao.upsert(capture(captured)) } returns Unit

        tool.tool.execute(ToolCall("c1", "use_skill", mapOf("name" to "Summarize")), ToolContext("conv-1"))

        assertEquals(1, captured.size)
        assertEquals(EvolutionDomain.SKILL.name, captured[0].domain)
        assertEquals("skill_invoked", captured[0].kind)
        assertEquals("s1", captured[0].sourceEntityId)
        assertEquals("conv-1", captured[0].conversationId)
    }

    @Test
    fun `a name that matches no skill is a lookup miss, not a skill failure`() = runBlocking {
        // This branch was the ONLY writer of skill_failed in the app, and it
        // recorded the literal id "_unknown_" — so the one signal feeding the
        // PATCH_SKILL detector accumulated under an id that resolves to
        // nothing, and every candidate it could raise named a skill that could
        // not be fetched, patched, or displayed. No skill ran here, so nothing
        // about a skill failed.
        coEvery { skillsStore.awaitLoaded() } returns Unit
        coEvery { skillsStore.findByName("Missing") } returns null
        coEvery { skillsStore.skills } returns MutableStateFlow(emptyList())

        val captured = mutableListOf<EvolutionEvidenceEntity>()
        coEvery { evidenceDao.upsert(capture(captured)) } returns Unit

        tool.tool.execute(ToolCall("c1", "use_skill", mapOf("name" to "Missing")), ToolContext("conv-2"))

        assertEquals(1, captured.size)
        assertEquals("skill_lookup_missed", captured[0].kind)
        assertEquals("Missing", captured[0].sourceEntityId)
        assertTrue(!captured[0].sourceEntityId.contains("_unknown_"))
    }
}
