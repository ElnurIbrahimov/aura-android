package com.aura.creative.livingworld

import com.aura.creative.WorldBible
import com.aura.creative.WorldCharacter
import com.aura.creative.WorldFaction
import com.aura.creative.WorldLocation
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * An evening of play, end to end.
 *
 * Every other test in this suite proves one piece in isolation: the projection
 * is fogged, the moves are legal against belief, the journal replays. This one
 * runs the actual road a player takes — seed a bible, take a seat, read the
 * moves the seat is offered, submit one, and read the world back out of the
 * store — because each of those pieces can be right while the wiring between
 * them is wrong, and the wiring is where a feature quietly turns out to be
 * unreachable.
 *
 * The DAOs are hand-rolled in memory rather than relaxed mocks, because a
 * relaxed mock answers every read with a default and would let a step that
 * persisted nothing pass.
 */
class SessionPlayTest {

    private val worlds = LinkedHashMap<String, LivingWorldEntity>()
    private val events = mutableListOf<LivingEventEntity>()

    private val worldDao = mockk<LivingWorldDao>()
    private val eventDao = mockk<LivingEventDao>()
    private val store = LivingWorldStore(worldDao, eventDao)
    private val runner = LivingWorldRunner(store)

    private val epoch = 1_700_000_000_000L

    init {
        coEvery { worldDao.byId(any()) } answers { worlds[firstArg()] }
        coEvery { worldDao.commitPlayedTick(any(), any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            worlds[id] = worlds.getValue(id).copy(
                currentTick = secondArg(),
                stateJson = thirdArg(),
                sessionTicksBurned = worlds.getValue(id).sessionTicksBurned + arg<Long>(3),
                updatedAt = arg(4),
            )
        }
        coEvery { worldDao.seat(any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            worlds[id] = worlds.getValue(id).copy(
                playerCharacterId = secondArg(),
                playerFactionId = thirdArg(),
                updatedAt = arg(3),
            )
        }
        coEvery { eventDao.upsertAll(any()) } answers { events += firstArg<List<LivingEventEntity>>() }
        coEvery { eventDao.recent(any(), any()) } answers {
            val worldId = firstArg<String>()
            events.filter { it.worldId == worldId }
                .sortedWith(compareByDescending<LivingEventEntity> { it.tickIndex }.thenByDescending { it.seq })
                .take(secondArg())
        }
        coEvery { eventDao.ofKindUpTo(any(), any(), any()) } answers {
            val worldId = firstArg<String>()
            val kind = secondArg<String>()
            val through = thirdArg<Long>()
            events.filter { it.worldId == worldId && it.kind == kind && it.tickIndex <= through }
                .sortedWith(compareBy({ it.tickIndex }, { it.seq }))
        }
    }

    /** Two houses, two halls, three people. Alder leads Ashfall. */
    private fun bible() = WorldBible(
        factions = listOf(
            WorldFaction(id = "f_a", name = "Ashfall", members = listOf("c_alder"), rivals = listOf("Bramwatch")),
            WorldFaction(id = "f_b", name = "Bramwatch", members = listOf("c_bryn"), rivals = listOf("Ashfall")),
        ),
        locations = listOf(
            WorldLocation(id = "l_keep", name = "The Keep"),
            WorldLocation(id = "l_road", name = "The Low Road"),
        ),
        characters = listOf(
            WorldCharacter(id = "c_alder", name = "Alder"),
            WorldCharacter(id = "c_bryn", name = "Bryn"),
            WorldCharacter(id = "c_corr", name = "Corr"),
        ),
    )

    private fun start(): LivingWorldEntity {
        val world = LivingWorldEntity(
            id = "w1",
            projectId = "p1",
            branchId = "main",
            rootSeed = 99L,
            worldEpochMs = epoch,
            currentTick = 0L,
            stateJson = store.encode(WorldSeeder().seed(bible())),
        )
        worlds[world.id] = world
        return world
    }

    private suspend fun seatedView(): PlayerView.View {
        val world = store.byId("w1")!!
        return PlayerView.of(
            store.decode(world.stateJson),
            world.playerCharacterId,
            world.playerFactionId,
        )
    }

    @Test
    fun `a seat can be taken, played, and read back`() = runTest {
        start()
        store.seat("w1", "c_alder", "f_a", now = 1L)

        // The seat reads back as a place with people in it, which is the whole
        // reason the seeder places anybody at all.
        val view = seatedView()
        assertEquals("l_keep", view.locationId, "Alder is not standing anywhere")
        assertTrue(view.others.any { it.canObserve }, "the hall is empty, so presence means nothing")

        // A move off the list, submitted and burned.
        val spend = PlayerMoves.available(view).first { it.verb == PlayerMoves.SPEND && it.legal }
        val outcome = runner.step("w1", listOf(spend.effect!!))
        assertEquals(TickOutcome.CAUGHT_UP, outcome)

        val after = store.byId("w1")!!
        assertEquals(1L, after.currentTick)
        assertEquals(1L, after.sessionTicksBurned, "the tick was not burned, so the world will stall for an hour")
        assertNotEquals(
            store.encode(WorldSeeder().seed(bible())),
            after.stateJson,
            "the tick was committed but the state was not",
        )

        // And the action reached the journal with its payload, which is what
        // makes the played world replayable at all.
        val journalled = events.filter { it.kind == WorldEngine.KIND_PLAYER_ACTION }
        assertEquals(1, journalled.size)
        assertEquals("f_a", journalled.single().actorId)
        assertTrue(journalled.single().payloadJson.contains("adjust_stock"))
    }

    @Test
    fun `an evening of play advances the world and leaves ambient time alone`() = runTest {
        start()
        store.seat("w1", "c_alder", "f_a", now = 1L)
        repeat(6) { runner.step("w1", emptyList()) }

        val after = store.byId("w1")!!
        assertEquals(6L, after.currentTick)
        assertEquals(6L, after.sessionTicksBurned)

        // The wall clock produced nothing during the session, and the world is
        // level rather than six ticks ahead of what it owes. If burned ticks
        // did not count toward the due tick, this would read -6 and the ambient
        // worker would go quiet for six hours.
        assertEquals(
            0L,
            WorldClock.behind(after.currentTick, after.worldEpochMs, epoch, after.sessionTicksBurned),
        )
        // An hour of real time still owes exactly one tick, on top of the six.
        assertEquals(
            1L,
            WorldClock.behind(
                after.currentTick,
                after.worldEpochMs,
                epoch + WorldClock.TICK_REAL_MS,
                after.sessionTicksBurned,
            ),
        )
    }

    @Test
    fun `the played world replays from its own journal`() = runTest {
        start()
        store.seat("w1", "c_alder", "f_a", now = 1L)

        // Travel, then look around, then spend — three ticks with a move in each.
        runner.step("w1", listOf(ActorEffect("c_alder", Effect.MoveTo("l_road"))))
        runner.step("w1", listOf(ActorEffect("c_alder", Effect.Observe(forId = "f_a"))))
        runner.step("w1", listOf(ActorEffect("f_a", Effect.SpreadLie("territory", 2_000))))

        val played = store.byId("w1")!!
        val segments = listOf(WorldReplayer.Segment("w1", played.rootSeed, played.branchSalt, 0L, 3L))
        val actions = store.resolveActions(segments, 3L)
        assertEquals(3, actions.size, "an action did not survive the round trip through the store")

        val replayed = WorldReplayer.stateAt(
            WorldSeeder().seed(bible()),
            segments,
            emptyList(),
            actions,
            3L,
        )
        assertEquals(
            store.encode(store.decode(played.stateJson)),
            store.encode(replayed),
            "the world replayed differently than it was played",
        )
    }
}
