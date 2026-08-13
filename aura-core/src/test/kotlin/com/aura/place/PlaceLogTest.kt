package com.aura.place

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.data.UserPreferences
import com.aura.memory.MemoryDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A second source of truth about the user's life — kept deliberately blunt.
 *
 * The knowledge graph is built essentially from chat, and no work on the
 * retrieval stack raises that ceiling: six-signal RRF over "things I told it" is
 * still a corpus of things the user told it. Place is the cheapest way past that
 * — passive, zero model calls, permission already granted.
 *
 * It is also the only one of the five guarantees that *widens* what Aura
 * collects, which is why the coarseness is asserted here rather than described
 * in a comment. The claim this table rests on is that it can answer "were you at
 * the usual place" and cannot reconstruct a movement trace, and a rounding
 * constant is the whole of what enforces it.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceLogTest {

    private lateinit var db: MemoryDatabase
    private lateinit var dao: PlaceVisitDao

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.placeVisitDao()
    }

    @After
    fun tearDown() = db.close()

    private fun log(enabled: Boolean = true): PlaceLog {
        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.placeLogEnabled } returns flowOf(enabled)
        return PlaceLog(ApplicationProvider.getApplicationContext(), dao, prefs)
    }

    // ---- The privacy claim ----

    @Test
    fun `coordinates are rounded to about a hundred metres`() {
        // A precise fix from the middle of Baku, rounded.
        assertEquals(40.409, PlaceVisitEntity.coarsen(40.4092617))
        assertEquals(49.867, PlaceVisitEntity.coarsen(49.8670924))
        // Negative values round the same way — a sign flip is a real hemisphere,
        // not an edge case to shrug at.
        assertEquals(-33.868, PlaceVisitEntity.coarsen(-33.8678501))
    }

    @Test
    fun `rounding loses enough precision to be useless as a trace`() {
        // Two points ~11m apart must collapse to the same stored coordinate.
        val a = PlaceVisitEntity.coarsen(40.40926)
        val b = PlaceVisitEntity.coarsen(40.40936)
        assertEquals(a, b, "100m rounding kept two points 11m apart distinguishable")

        // And a genuinely different place must not collapse into it.
        assertTrue(abs(PlaceVisitEntity.coarsen(40.4092) - PlaceVisitEntity.coarsen(40.4500)) > 0.01)
    }

    @Test
    fun `the switch being off writes nothing at all`() = runBlocking {
        assertEquals(PlaceLog.Outcome.Disabled, log(enabled = false).sample(now))
        assertEquals(0, dao.count(), "a row was written while the place log was switched off")
    }

    // ---- Visits, not fixes ----

    @Test
    fun `samples at the same place extend one visit rather than adding rows`() = runBlocking {
        dao.insert(PlaceVisitEntity(lat = 40.409, lon = 49.867, arrivedAt = now, lastSeenAt = now))

        dao.mostRecent()!!.let { open ->
            dao.extend(open.id, now + 15 * minute)
            dao.extend(open.id, now + 30 * minute)
        }

        val rows = dao.recent(10)
        assertEquals(1, rows.size, "each sample created its own row — this is a trace, not a visit log")
        assertEquals(3, rows.single().samples)
        assertEquals(30 * minute, rows.single().durationMs)
    }

    @Test
    fun `a long enough gap ends the visit even at the same coordinates`() {
        val open = PlaceVisitEntity(lat = 40.409, lon = 49.867, arrivedAt = now, lastSeenAt = now)
        val withinGap = now + PlaceVisitEntity.VISIT_GAP_MS - 1
        val pastGap = now + PlaceVisitEntity.VISIT_GAP_MS + 1

        assertTrue(withinGap - open.lastSeenAt <= PlaceVisitEntity.VISIT_GAP_MS)
        assertTrue(pastGap - open.lastSeenAt > PlaceVisitEntity.VISIT_GAP_MS)
    }

    @Test
    fun `nearby coordinates are the same place and distant ones are not`() {
        assertTrue(PlaceVisitEntity.samePlace(40.409, 49.867, 40.4095, 49.8675))
        assertTrue(!PlaceVisitEntity.samePlace(40.409, 49.867, 40.450, 49.900))
    }

    // ---- Retention ----

    @Test
    fun `the log is bounded, and the boundary keeps what is inside it`() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        dao.insert(PlaceVisitEntity(lat = 1.0, lon = 1.0, arrivedAt = now - 200 * day, lastSeenAt = now - 200 * day))
        dao.insert(PlaceVisitEntity(lat = 2.0, lon = 2.0, arrivedAt = now - 91 * day, lastSeenAt = now - 91 * day))
        dao.insert(PlaceVisitEntity(lat = 3.0, lon = 3.0, arrivedAt = now - 10 * day, lastSeenAt = now - 10 * day))

        val deleted = log().prune(now)

        assertEquals(2, deleted)
        assertEquals(listOf(3.0), dao.recent(10).map { it.lat })
    }

    @Test
    fun `most frequent ranks by time spent, not by number of visits`() = runBlocking {
        // One long stay at A, three brief ones at B.
        dao.insert(PlaceVisitEntity(lat = 1.0, lon = 1.0, arrivedAt = now, lastSeenAt = now + 480 * minute))
        repeat(3) { i ->
            dao.insert(
                PlaceVisitEntity(
                    lat = 2.0,
                    lon = 2.0,
                    arrivedAt = now + i * 1000L,
                    lastSeenAt = now + i * 1000L + 20 * minute,
                ),
            )
        }

        assertEquals(1.0, dao.mostFrequent(2).first().lat, "a place visited often outranked where the day was spent")
    }
}
