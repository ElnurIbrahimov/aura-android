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
import kotlin.test.assertFalse
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
        coEvery { worldDao.upsert(any()) } answers {
            val world = firstArg<LivingWorldEntity>()
            worlds[world.id] = world
        }
        // Conditional, exactly as the SQL is: a writer working from a stale
        // read updates nothing and is told so.
        coEvery { worldDao.commitPlayedTick(any(), any(), any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            val current = worlds.getValue(id)
            if (current.currentTick != arg<Long>(5)) {
                0
            } else {
                worlds[id] = current.copy(
                    currentTick = secondArg(),
                    stateJson = thirdArg(),
                    sessionTicksBurned = current.sessionTicksBurned + arg<Long>(3),
                    updatedAt = arg(4),
                )
                1
            }
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
            // Fork-at-past is gated on genesis, so a world without one
            // could not exercise the fork tests below at all.
            genesisJson = store.encode(WorldSeeder().seed(bible())),
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

    @Test
    fun `a fork keeps your seat`() = runTest {
        start()
        store.seat("w1", "c_alder", "f_a", now = 1L)
        runner.step("w1", listOf(ActorEffect("f_a", Effect.SpreadLie("territory", 1_000))))

        val child = store.forkAt(store.byId("w1")!!, tick = 1L, branchId = "b2", branchName = "what if")
        assertEquals("c_alder", child?.playerCharacterId, "the fork put you outside your own timeline")
        assertEquals("f_a", child?.playerFactionId)
    }

    @Test
    fun `a fork of a played world does not go quiet for as long as the session was`() = runTest {
        start()
        store.seat("w1", "c_alder", "f_a", now = 1L)
        repeat(4) { runner.step("w1", emptyList()) }

        val child = store.forkAt(store.byId("w1")!!, tick = 4L, branchId = "b2", branchName = "what if")!!

        // No wall-clock time has passed, so all four of those ticks were burned
        // and the child inherits that. One real hour later it owes exactly one
        // tick. A child that recorded none would be four ticks ahead of a clock
        // that has to spend four hours catching up — the ambient half of the
        // design switched off by the act of forking, silently.
        assertEquals(4L, child.sessionTicksBurned)
        assertEquals(
            1L,
            WorldClock.behind(
                child.currentTick,
                child.worldEpochMs,
                epoch + WorldClock.TICK_REAL_MS,
                child.sessionTicksBurned,
            ),
        )
    }

    @Test
    fun `forking into the genuine past still owes the ticks since`() = runTest {
        // The other direction, and the documented one: a fork at a tick the wall
        // clock passed long ago owes everything since, and catches up along its
        // own salt. Nothing about the burn should change that.
        start()
        store.seat("w1", "c_alder", "f_a", now = 1L)
        repeat(4) { runner.step("w1", emptyList()) }

        val child = store.forkAt(store.byId("w1")!!, tick = 1L, branchId = "b3", branchName = "earlier")!!
        assertEquals(1L, child.sessionTicksBurned)
        assertEquals(
            9L,
            WorldClock.behind(
                child.currentTick,
                child.worldEpochMs,
                epoch + 9 * WorldClock.TICK_REAL_MS,
                child.sessionTicksBurned,
            ),
        )
    }

    @Test
    fun `a writer working from a stale read loses rather than winning`() = runTest {
        // step() added a second writer to a world the hourly worker also
        // commits to. Without a guard the loser overwrites the winner, and the
        // world can jump backwards — or worse, land on a state that does not
        // contain an action its own journal says happened.
        start()
        store.seat("w1", "c_alder", "f_a", now = 1L)
        val stale = store.byId("w1")!!

        assertEquals(TickOutcome.CAUGHT_UP, runner.step("w1", emptyList()))
        assertEquals(1L, store.byId("w1")!!.currentTick)

        val applied = store.commitPlayedTicks(
            world = stale,
            newState = store.decode(stale.stateJson),
            throughTick = 1L,
            burned = 1L,
            events = emptyList(),
            now = 9L,
        )
        assertFalse(applied, "the stale writer was allowed to overwrite the world")
        assertEquals(1L, store.byId("w1")!!.currentTick)
        assertEquals(1L, store.byId("w1")!!.sessionTicksBurned, "the burn was double counted")
    }

    @Test
    fun `a rejected play writes no events`() = runTest {
        // The half that matters. If the events landed and the tick did not, the
        // journal would say an action happened at a tick the world never
        // reached, and replay would diverge from stored state — which is the
        // exact failure the journal exists to prevent.
        start()
        store.seat("w1", "c_alder", "f_a", now = 1L)
        val stale = store.byId("w1")!!
        runner.step("w1", emptyList())
        val before = events.size

        store.commitPlayedTicks(
            world = stale,
            newState = store.decode(stale.stateJson),
            throughTick = 1L,
            burned = 1L,
            events = listOf(
                ScoredEvent(
                    WorldEvent(
                        tick = 1L,
                        seq = 0,
                        kind = WorldEngine.KIND_PLAYER_ACTION,
                        actorId = "f_a",
                        summary = "Ashfall acts.",
                        payload = Effect.SpreadLie("territory", 500),
                    ),
                    0.5,
                ),
            ),
            now = 9L,
        )
        assertEquals(before, events.size, "an orphan action row was written for a tick nobody reached")
    }

}
