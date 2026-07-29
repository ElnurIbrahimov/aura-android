package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the proposal purge case-mismatch fixed 2026-07-29.
 *
 * ProposalStatus.name returns uppercase strings, and SQLite TEXT comparison
 * is case-sensitive without COLLATE NOCASE. The deleteResolvedOlderThan query
 * must use uppercase literals to match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvolutionProposalPurgeRegressionTest {

    @Test
    fun `deleteResolvedOlderThan removes uppercase REJECTED`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries().build()

        val dao = db.proposalDao()
        val now = System.currentTimeMillis()
        val oldCutoff = now - 1000L // 1 second ago

        // Insert proposals with statuses matching ProposalStatus.name (uppercase).
        dao.upsert(
            EvolutionProposalEntity(
                id = "p_rejected", domain = "SKILL", action = "CREATE_SKILL",
                targetId = "x", status = ProposalStatus.REJECTED.name,
                resolvedAt = oldCutoff,
            ),
        )
        dao.upsert(
            EvolutionProposalEntity(
                id = "p_rolled_back", domain = "SKILL", action = "PATCH_SKILL",
                targetId = "y", status = ProposalStatus.ROLLED_BACK.name,
                resolvedAt = oldCutoff,
            ),
        )
        dao.upsert(
            EvolutionProposalEntity(
                id = "p_superseded", domain = "MEMORY", action = "FORGET_MEMORY",
                targetId = "z", status = ProposalStatus.SUPERSEDED.name,
                resolvedAt = oldCutoff,
            ),
        )
        // This one should NOT be deleted — APPLIED is not in the purge set.
        dao.upsert(
            EvolutionProposalEntity(
                id = "p_applied", domain = "SKILL", action = "CREATE_SKILL",
                targetId = "w", status = ProposalStatus.APPLIED.name,
                resolvedAt = oldCutoff,
            ),
        )
        // This one is recent — should NOT be deleted regardless of status.
        dao.upsert(
            EvolutionProposalEntity(
                id = "p_recent_rejected", domain = "SKILL", action = "CREATE_SKILL",
                targetId = "v", status = ProposalStatus.REJECTED.name,
                resolvedAt = now + 86_400_000L, // tomorrow
            ),
        )

        // Purge: delete resolved proposals with cutoff = now (only old ones).
        val deleted = dao.deleteResolvedOlderThan(now)

        // Should have deleted exactly 3: the old REJECTED, ROLLED_BACK, SUPERSEDED.
        assertEquals("Should delete exactly 3 old resolved proposals", 3, deleted)

        // Verify the remaining ones exist.
        val remaining = dao.open() + listOfNotNull(
            dao.getById("p_applied"),
            dao.getById("p_recent_rejected"),
        ).filter { it.status !in setOf("PENDING_REVIEW", "APPROVED", "APPLY_FAILED") }

        // p_applied and p_recent_rejected should survive.
        assertTrue(
            "APPLIED proposal should survive purge",
            remaining.any { it.id == "p_applied" },
        )
        assertTrue(
            "Recent REJECTED proposal should survive purge",
            remaining.any { it.id == "p_recent_rejected" },
        )

        db.close()
    }
}
