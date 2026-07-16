package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryFeedbackEntity
import kotlinx.coroutines.runBlocking

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvolutionMemorySynthesizerTest {
    @Test
    fun `synthesizes belief from repeated scope memories`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java).allowMainThreadQueries().build()
        db.memoryDao().insertAll(listOf(
            MemoryEntity(id = "m1", content = "I love Kotlin", source = "user", category = "preference", scope = "general"),
            MemoryEntity(id = "m2", content = "Kotlin is my favorite language", source = "user", category = "preference", scope = "general"),
            MemoryEntity(id = "m3", content = " unrelated thing", source = "user", category = "fact", scope = "general"),
        ))
        val synth = EvolutionMemorySynthesizer(
            db.memoryDao(),
            db.memoryFeedbackDao(),
            db.beliefDao(),
            db.evidenceDao(),
        )
        val belief = synth.synthesizeScope("general", now = 1L)
        assertNotNull(belief)
        assertTrue(belief!!.subject.isNotBlank())
        db.close()
    }
}
