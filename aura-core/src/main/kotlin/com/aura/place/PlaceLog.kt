package com.aura.place

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aura.data.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a stream of coarse location samples into a short list of visits.
 *
 * The value is not in any single fix. It is in "you were at the usual place from
 * nine to six" — which is a fact about a *stay*, and a stay is what this
 * assembles from samples that would otherwise be a movement trace.
 *
 * Three conditions, all of which must hold, and all of which produce the same
 * silence so no caller has to know which failed: the switch is on, the runtime
 * permission is granted, and a fix exists. Aura's own switch is separate from
 * Android's grant for the same reason `ForegroundAppReader` keeps them separate
 * — revoking a system permission is buried several screens deep, and "stop doing
 * this" should be one tap inside Aura.
 */
@Singleton
class PlaceLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PlaceVisitDao,
    private val userPreferences: UserPreferences,
) {

    /**
     * Take one sample and fold it into the visit log.
     *
     * @return what happened, so a worker can record a reason rather than a shrug.
     */
    suspend fun sample(now: Long = System.currentTimeMillis()): Outcome {
        if (!enabled()) return Outcome.Disabled
        if (!permitted()) return Outcome.NoPermission
        val fix = lastKnown() ?: return Outcome.NoFix

        // Rounded before anything else touches it. Nothing downstream ever sees
        // the precise coordinate, which is what makes "never a movement trace" a
        // property of the code rather than a promise in a comment.
        val lat = PlaceVisitEntity.coarsen(fix.latitude)
        val lon = PlaceVisitEntity.coarsen(fix.longitude)

        val open = runCatching { dao.mostRecent() }
            .onFailure { Log.w(TAG, "place read failed", it) }
            .getOrNull()

        val continues = open != null &&
            PlaceVisitEntity.samePlace(open.lat, open.lon, lat, lon) &&
            now - open.lastSeenAt <= PlaceVisitEntity.VISIT_GAP_MS

        return runCatching {
            if (continues) {
                dao.extend(open!!.id, now)
                Outcome.Extended
            } else {
                dao.insert(PlaceVisitEntity(lat = lat, lon = lon, arrivedAt = now, lastSeenAt = now))
                Outcome.Arrived
            }
        }.onFailure { Log.w(TAG, "place write failed", it) }
            .getOrDefault(Outcome.NoFix)
    }

    /** Trim the log. Called from `DecayWorker`, beside the worker-run prune. */
    suspend fun prune(now: Long = System.currentTimeMillis()): Int =
        runCatching { dao.deleteOlderThan(now - RETENTION_MS) }
            .onFailure { Log.w(TAG, "place prune failed", it) }
            .getOrDefault(0)

    suspend fun recent(limit: Int = 20): List<PlaceVisitEntity> =
        runCatching { dao.recent(limit) }
            .onFailure { Log.w(TAG, "place recent failed", it) }
            .getOrDefault(emptyList())

    private suspend fun enabled(): Boolean =
        runCatching { userPreferences.placeLogEnabled.first() }
            .onFailure { Log.w(TAG, "place switch read failed", it) }
            .getOrDefault(false)

    private fun permitted(): Boolean = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Last-known fix only — never a live subscription.
     *
     * Requesting updates would be more accurate and would also be a continuous
     * location listener running all day, which is both the battery cost this
     * design exists to avoid and a far more invasive product than "roughly where
     * were you today". Something else on the device has almost always asked for
     * a fix recently; this rides on that.
     */
    // Guarded twice, and lint can see neither: [permitted] runs in `sample`
    // before this is reached, and the per-provider read below catches
    // SecurityException anyway. Same annotation and same reason as
    // `LocationNowTool.lastKnown` and `TriggerEngine`.
    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnown(): Location? {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return null
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider ->
                    try {
                        manager.getLastKnownLocation(provider)
                    } catch (denied: SecurityException) {
                        Log.w(TAG, "location denied for $provider", denied)
                        null
                    }
                }
                .maxByOrNull { it.time }
        }.onFailure { Log.w(TAG, "location read failed", it) }.getOrNull()
    }

    /** What one sample did. Named so a worker can say it out loud. */
    enum class Outcome(val reason: String) {
        Disabled("the place log is switched off"),
        NoPermission("location permission is not granted"),
        NoFix("no location fix is available"),
        Arrived("arrived somewhere new"),
        Extended("still in the same place"),
        ;

        val wrote: Boolean get() = this == Arrived || this == Extended
    }

    private companion object {
        const val TAG = "PlaceLog"

        /**
         * 90 days. Long enough for "the usual place" to mean something across a
         * season, short enough that a phone does not accumulate a year of
         * movement nobody asked for.
         */
        const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    }
}
