package com.aura.backup

import com.aura.creative.livingworld.LivingEventEntity
import com.aura.creative.livingworld.LivingWorldEntity
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A living world survives a backup with everything it needs to be replayed.
 *
 * [BackupCoverageAuditTest] proves every persisted *entity* has a backup
 * representation. It says nothing about columns, and a mapper that drops one
 * is completely silent: the backup writes, the restore reads, the world is
 * there, and only the thing the column carried is gone.
 *
 * For these two types that is not a cosmetic loss.
 * [LivingEventEntity.payloadJson] is the journal
 * [com.aura.creative.livingworld.WorldReplayer] replays a played world from, so
 * losing it means a restored world replays as one where you never acted — and
 * forking to a past tick then yields a branch that never happened, plausibly
 * and quietly. [LivingWorldEntity.playerCharacterId] and its siblings are the
 * seat; losing them restores a world nobody is in.
 *
 * Both directions are checked because a backup is only half a round trip, and
 * the name-set assertions are the part that will catch the *next* column rather
 * than this one.
 */
class LivingWorldBackupRoundTripTest {

    /** Every field non-default, so a mapper that drops one cannot coincide with it. */
    private val world = LivingWorldEntity(
        id = "w1",
        projectId = "p1",
        branchId = "b_fork",
        rootSeed = 424_242L,
        branchSalt = 99L,
        parentWorldId = "w0",
        forkedAtTick = 17L,
        worldEpochMs = 1_700_000_000_000L,
        currentTick = 212L,
        stateJson = """{"entities":[]}""",
        genesisJson = """{"genesis":true}""",
        status = "paused",
        playerCharacterId = "c_you",
        playerFactionId = "f_ash",
        sessionTicksBurned = 34L,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private val event = LivingEventEntity(
        id = "w1#212.3",
        worldId = "w1",
        branchId = "b_fork",
        tickIndex = 212L,
        seq = 3,
        kind = "player_action",
        actorId = "f_ash",
        targetId = "f_bram",
        ruleId = "player_action",
        magnitudeMilli = 1_250L,
        summary = "Ashfall acts.",
        notability = 0.72,
        narration = "The banners went up before dawn.",
        narratedAt = 3L,
        payloadJson = """{"type":"claim_pool","poolId":"territory","key":"territory","amountMilli":1250}""",
        createdAt = 4L,
    )

    @Test
    fun `a world survives the round trip whole`() {
        assertEquals(world, world.toBackup().toEntity())
    }

    @Test
    fun `an event keeps the action it journals`() {
        val restored = event.toBackup().toEntity()
        assertEquals(event, restored)
        assertEquals(event.payloadJson, restored.payloadJson, "the world was restored, the moves were not")
    }

    @Test
    fun `every column of a living world has somewhere to go`() {
        // The tripwire for the next column rather than this one. Adding a field
        // to the entity and forgetting the backup DTO fails here, at the point
        // the field is added, instead of failing on somebody's phone during a
        // restore months later.
        // Named explicitly so the comparison below cannot pass by both sides
        // reflecting to nothing.
        assertTrue(names<LivingWorldEntity>().containsAll(listOf("playerCharacterId", "sessionTicksBurned")))
        assertEquals(names<LivingWorldEntity>(), names<LivingWorldBackup>())
    }

    @Test
    fun `every column of a living event has somewhere to go`() {
        assertTrue(names<LivingEventEntity>().contains("payloadJson"))
        assertEquals(names<LivingEventEntity>(), names<LivingEventBackup>())
    }

    // Java reflection rather than kotlin-reflect, which is not on the test
    // classpath. A data class's declared instance fields are its constructor
    // properties; statics and compiler-generated fields are not.
    private inline fun <reified T : Any> names(): List<String> =
        T::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()
}
