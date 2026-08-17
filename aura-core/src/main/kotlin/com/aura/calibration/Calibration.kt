package com.aura.calibration

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How often Aura's stated confidence turned out to be earned.
 *
 * **A read, not a store** — the `ChangeLog` pattern. No new table, no model call,
 * no writes, so it needed no migration, backup mapper or schema export of its
 * own. Everything here is computed from `claim_resolutions` at the moment it is
 * asked for.
 *
 * It also does not *apply* anything. Feeding a curve derived from thirty biased
 * samples back into stored confidence would change what every future recall
 * surfaces, and would do it invisibly. `BeliefDao.verify` is the write path that
 * would close that loop and this class deliberately never calls it — watch the
 * curve for a cycle first, the discipline `docs/RETRIEVAL_EVAL.md` already
 * applies to Gate B, where model judging stays advisory until a person decides
 * to trust it.
 */
@Singleton
class Calibration @Inject constructor(
    private val dao: ClaimResolutionDao,
) {

    /**
     * A confidence range and what actually happened inside it.
     *
     * Human labels rather than numbers because the output of this feature is a
     * sentence — "when I say *likely* I am right about half the time" — and
     * "0.6–0.8" is not a thing anyone says.
     */
    enum class Band(val label: String, val lower: Float, val upper: Float) {
        UNSURE("unsure", 0f, 0.6f),
        LIKELY("likely", 0.6f, 0.8f),
        CONFIDENT("confident", 0.8f, 0.95f),
        CERTAIN("certain", 0.95f, 1.01f);

        companion object {
            fun of(confidence: Float): Band =
                entries.firstOrNull { confidence >= it.lower && confidence < it.upper } ?: CERTAIN
        }
    }

    data class Bucket(
        val label: String,
        val resolved: Int,
        val held: Int,
    ) {
        /** Null below the per-bucket floor: a rate over four samples is not a rate. */
        val rate: Float? get() = if (resolved >= MIN_PER_BUCKET) held.toFloat() / resolved else null
    }

    data class Report(
        /** Rows carrying a right/wrong signal. The number that governs everything. */
        val scored: Int,
        /** Every verdict including `no_longer_true`, so the screen can show the split. */
        val total: Int,
        val bands: List<Bucket>,
        val sources: List<Bucket>,
        /**
         * Mean squared error between stated confidence and outcome. 0 is perfect,
         * 0.25 is what you get by always saying 50%. Null below the floor.
         */
        val brier: Float?,
        /**
         * True when there is enough to say anything at all.
         *
         * The single most important field. A confident figure over a handful of
         * samples is worse than no figure, because it launders a guess into a
         * statistic and nothing downstream can tell the difference.
         */
        val reportable: Boolean,
    )

    suspend fun report(since: Long = 0L): Report {
        val rows = runCatching { dao.scoredSince(since) }
            .onFailure { Log.w(TAG, "could not read resolutions: ${it.message}", it) }
            .getOrDefault(emptyList())
        val total = runCatching { dao.totalCount() }
            .onFailure { Log.w(TAG, "could not count resolutions: ${it.message}", it) }
            .getOrDefault(rows.size)

        val bands = Band.entries.map { band ->
            val inBand = rows.filter { Band.of(it.assertedConfidence) == band }
            Bucket(
                label = band.label,
                resolved = inBand.size,
                held = inBand.count { it.verdict == ClaimResolutionEntity.VERDICT_CONFIRMED },
            )
        }

        val sources = rows.groupBy { it.beliefSource }
            .map { (source, inSource) ->
                Bucket(
                    label = source,
                    resolved = inSource.size,
                    held = inSource.count { it.verdict == ClaimResolutionEntity.VERDICT_CONFIRMED },
                )
            }
            .sortedByDescending { it.resolved }

        // Brier: mean (stated - outcome)^2. Computed over the scored rows only —
        // `outcome` returns null for `no_longer_true` and those rows never reach
        // here, but the null-filter stays as the second guard on the one rule
        // this whole feature depends on.
        val brier = if (rows.size >= MIN_SCORED) {
            rows.mapNotNull { row ->
                ClaimResolutionEntity.outcome(row.verdict)?.let { outcome ->
                    val error = row.assertedConfidence - outcome
                    error * error
                }
            }.average().toFloat()
        } else {
            null
        }

        return Report(
            scored = rows.size,
            total = total,
            bands = bands,
            sources = sources,
            brier = brier,
            reportable = rows.size >= MIN_SCORED,
        )
    }

    companion object {
        /**
         * Scored verdicts needed before any rate is shown.
         *
         * Twenty is not a statistically satisfying number and is not meant to
         * be. It is the point past which the figure stops being noise dressed as
         * measurement, on a feature whose input is one question every three
         * days — a higher bar would mean the report never appears at all, which
         * is its own kind of dishonest.
         */
        const val MIN_SCORED = 20

        /** Per band and per source. A rate over four samples is not a rate. */
        const val MIN_PER_BUCKET = 8

        private const val TAG = "Calibration"
    }
}
