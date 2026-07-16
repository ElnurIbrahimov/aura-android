package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.security.KeyManager
import com.aura.skills.Skill
import kotlinx.coroutines.runBlocking
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
class EvolutionSkillRevisionStoreTest {
    private lateinit var db: EvolutionDatabase
    private lateinit var store: EvolutionSkillRevisionStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = EvolutionSkillRevisionStore(db.revisionDao(), KeyManager())
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun `can snapshot and restore a skill`() = runBlocking {
        val skill = Skill(id = "s1", name = "Summarize", description = "Short summary", body = "Summarize any text.")
        val revId = store.snapshot(skill, summary = "created")
        assertNotNull(revId)
        val restored = store.latest("s1")
        assertNotNull(restored)
        assertEquals("Summarize", restored?.name)
        assertEquals("Summarize any text.", restored?.body)
    }
}
