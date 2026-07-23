package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvolutionProposalDaoPendingCountTest {
    @Test
    fun `observePendingCount emits 0 for an empty database`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assertEquals(0, db.proposalDao().observePendingCount().first())
        db.close()
    }

    @Test
    fun `observePendingCount emits 1 after inserting a single PENDING_REVIEW proposal`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.proposalDao().upsert(
            EvolutionProposalEntity(
                id = "p1",
                domain = "SKILL",
                action = "CREATE_SKILL",
                targetId = "x",
                status = ProposalStatus.PENDING_REVIEW.name,
            ),
        )
        assertEquals(1, db.proposalDao().observePendingCount().first())
        db.close()
    }

    @Test
    fun `observePendingCount excludes APPROVED and APPLIED proposals`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.proposalDao().upsert(
            EvolutionProposalEntity(id = "p1", domain = "SKILL", action = "CREATE_SKILL", targetId = "x"),
        )
        db.proposalDao().setStatus("p1", ProposalStatus.APPROVED.name, "test")
        assertEquals(0, db.proposalDao().observePendingCount().first())
        db.close()
    }

    @Test
    fun `observePendingCount reacts to status changes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.proposalDao().upsert(
            EvolutionProposalEntity(id = "p1", domain = "SKILL", action = "CREATE_SKILL", targetId = "x"),
        )
        assertEquals(1, db.proposalDao().observePendingCount().first())
        db.proposalDao().setStatus("p1", ProposalStatus.REJECTED.name, "user said no")
        assertEquals(0, db.proposalDao().observePendingCount().first())
        db.close()
    }
}
