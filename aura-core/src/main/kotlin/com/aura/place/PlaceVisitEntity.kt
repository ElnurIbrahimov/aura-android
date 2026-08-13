package com.aura.place

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Somewhere the user was, and roughly when.
 *
 * The knowledge graph is built essentially from chat. No amount of work on the
 * retrieval stack raises that ceiling — six-signal RRF over "things I told it" is
 * still a corpus of things the user told it — and the only way past it is a
 * second source of truth about their life that does not depend on them narrating
 * it. Place was chosen over screenshots and messages because it is passive, costs
 * **zero model calls**, and the permission and plumbing already exist.
 *
 * **Deliberately coarse.** Coordinates are rounded to [PLACES_DP] decimal places
 * — about 100 m — before they are ever written. That is enough to answer "were
 * you at the usual place", and not enough to be a movement trace. The rounding
 * happens at the point of capture rather than at the point of display, so no
 * precise coordinate is ever stored and no future feature can decide to start
 * using one. `PlaceLogTest` asserts the rounding, because it is the privacy claim
 * this whole table rests on.
 *
 * A row is a *visit*, not a fix: arrival, last-seen, and the count of samples
 * that agreed. One row per stay, not one per poll.
 */
@Entity(
    tableName = "place_visits",
    indices = [Index("arrivedAt"), Index(value = ["lat", "lon"])],
)
data class PlaceVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Rounded to [PLACES_DP] decimals. Never the raw fix. */
    val lat: Double,
    val lon: Double,
    val arrivedAt: Long,
    /** Last sample that still matched this place. Equals [arrivedAt] for a single sample. */
    val lastSeenAt: Long,
    /** How many samples agreed. A high count on a long stay is what makes a place "usual". */
    val samples: Int = 1,
    /**
     * What the user calls it, when they have said.
     *
     * Never inferred from a geocoder: reverse-geocoding every visit would send
     * the coordinates off-device, which is the one thing this table is careful
     * not to do.
     */
    val label: String = "",
) {
    val durationMs: Long get() = (lastSeenAt - arrivedAt).coerceAtLeast(0)

    companion object {
        /**
         * Three decimal places — roughly 100 m at the equator, less further north.
         *
         * Four would be ~10 m, which distinguishes rooms in a building and is a
         * different and much more invasive product. Two would be ~1 km, which
         * cannot tell home from the shop at the end of the road and would make
         * the whole table useless.
         */
        const val PLACES_DP = 3

        /** Two fixes within this distance are the same place. Matches [PLACES_DP]. */
        const val SAME_PLACE_DEGREES = 0.0015

        /** A gap longer than this ends a visit, even at the same coordinates. */
        const val VISIT_GAP_MS = 90L * 60 * 1000

        fun coarsen(value: Double): Double {
            val factor = Math.pow(10.0, PLACES_DP.toDouble())
            return (value * factor).roundToLong() / factor
        }

        fun samePlace(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Boolean =
            abs(aLat - bLat) <= SAME_PLACE_DEGREES && abs(aLon - bLon) <= SAME_PLACE_DEGREES
    }
}

@Dao
interface PlaceVisitDao {
    @Insert
    suspend fun insert(row: PlaceVisitEntity): Long

    @Query("UPDATE place_visits SET lastSeenAt = :lastSeenAt, samples = samples + 1 WHERE id = :id")
    suspend fun extend(id: Long, lastSeenAt: Long)

    @Query("UPDATE place_visits SET label = :label WHERE id = :id")
    suspend fun label(id: Long, label: String)

    @Query("SELECT * FROM place_visits ORDER BY arrivedAt DESC LIMIT 1")
    suspend fun mostRecent(): PlaceVisitEntity?

    @Query("SELECT * FROM place_visits ORDER BY arrivedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<PlaceVisitEntity>

    @Query("SELECT * FROM place_visits WHERE arrivedAt >= :since ORDER BY arrivedAt DESC LIMIT :limit")
    suspend fun since(since: Long, limit: Int): List<PlaceVisitEntity>

    /** Places seen most often, for "the usual place". */
    @Query(
        "SELECT * FROM place_visits GROUP BY lat, lon " +
            "ORDER BY SUM(lastSeenAt - arrivedAt) DESC LIMIT :limit",
    )
    suspend fun mostFrequent(limit: Int): List<PlaceVisitEntity>

    @Query("SELECT COUNT(*) FROM place_visits")
    suspend fun count(): Int

    @Query("SELECT * FROM place_visits ORDER BY arrivedAt ASC")
    suspend fun allForBackup(): List<PlaceVisitEntity>

    @Insert
    suspend fun insertAll(rows: List<PlaceVisitEntity>)

    /** Bounded, like the worker run log. Swept from `DecayWorker`. */
    @Query("DELETE FROM place_visits WHERE lastSeenAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM place_visits")
    suspend fun deleteAll()
}
