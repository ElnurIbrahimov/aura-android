package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvolutionHooksTest {
    private lateinit var db: EvolutionDatabase
    private lateinit var hooks: EvolutionHooks

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        hooks = EvolutionHooks(EvolutionEvidenceRecorder(db.evidenceDao()))
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun `records skill and memory and proactive evidence`() = runBlocking {
        hooks.onSkillInvoked("skill_1")
        hooks.onMemoryStored("m1", "fact")
        hooks.onProactiveDelivered("e1", "morning_brief")

        val skill = db.evidenceDao().byKind(EvolutionDomain.SKILL.name, "skill_invoked")
        assertEquals(1, skill.size)
        assertEquals("skill_1", skill.first().sourceEntityId)

        val memory = db.evidenceDao().byKind(EvolutionDomain.MEMORY.name, "memory_stored")
        assertEquals(1, memory.size)

        val proactive = db.evidenceDao().byKind(EvolutionDomain.PROACTIVE.name, "proactive_delivered")
        assertEquals(1, proactive.size)
    }
}
