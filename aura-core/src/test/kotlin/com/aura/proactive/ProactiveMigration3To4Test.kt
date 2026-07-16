package com.aura.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProactiveMigration3To4Test {
    @Test
    fun `migration 3 to 4 creates interaction table`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ProactiveEventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val eventId = db.proactiveEventDao().insert(ProactiveEventEntity(eventType = "x", title = "t", body = "b", timestamp = 1L))
        val id = db.proactiveInteractionDao().insert(ProactiveInteractionEntity(eventId = eventId, action = "dismissed"))
        assertEquals(1L, id)
        val rows = db.proactiveInteractionDao().forEvent(eventId)
        assertEquals(1, rows.size)
        db.close()
    }
}
