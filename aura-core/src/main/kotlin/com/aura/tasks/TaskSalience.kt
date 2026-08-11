package com.aura.tasks

import kotlin.math.pow

/**
 * Decides how much of your attention a task still deserves.
 *
 * Pure, so the reason a task went quiet is always a sentence someone can read
 * back rather than the output of a model nobody can interrogate.
 *
 * **The load-bearing idea: deferral counts as evidence against a task.**
 *
 * Every other todo system treats an overdue task as more urgent — red badges,
 * escalating counts, "3 overdue". That is defensible for work someone else is
 * waiting on and wrong for a personal list. If you have skipped a thing nine
 * times, the skipping *is* the information: some part of you decided already
 * and has not said so. Amplifying it each time is exactly how these systems
 * become guilt machines that get abandoned wholesale — and a list thrown away
 * is a worse outcome than any single task missed.
 *
 * So here, pushing something away makes it quieter, and a deadline passing
 * with nothing breaking makes it much quieter, because nothing breaking is the
 * strongest available evidence the deadline was fictional.
 *
 * Nothing here deletes. Below [QUIET_THRESHOLD] a task leaves the default view
 * and stays in the table, in search, and in the quiet list.
 */
object TaskSalience {

    /** Below this a task drops out of the main list. */
    const val QUIET_THRESHOLD = 0.25

    /** Untouched, unmentioned, no deadline: salience halves every this many days. */
    const val HALF_LIFE_DAYS = 21.0

    /** Each deliberate push-away multiplies salience by this. */
    const val DEFER_FACTOR = 0.7

    /** Applied once when a deadline passes and nothing happens. */
    const val MISSED_DEADLINE_FACTOR = 0.5

    /** How long after a due date before "nothing broke" counts as evidence. */
    const val DEADLINE_GRACE_DAYS = 7.0

    /** Inside this window before a deadline, a task is pulled back up. */
    const val DEADLINE_APPROACH_DAYS = 3.0

    /** How far a touch or a mention closes the gap to full brightness. */
    const val REVIVE_FRACTION = 0.6

    private const val DAY_MS = 86_400_000.0

    /**
     * The task's salience after time has passed, with no new evidence.
     *
     * Deliberately not called from a getter: decay is applied by a pass that
     * writes the result, so what the UI sorts by and what a later explanation
     * reads back are the same number.
     */
    fun decayed(task: TaskEntity, now: Long): Double {
        if (task.status != STATUS_PENDING) return task.salience
        val touched = if (task.lastTouchedAt > 0L) task.lastTouchedAt else task.createdAt
        val days = ((now - touched).coerceAtLeast(0L)) / DAY_MS
        var value = task.salience * 0.5.pow(days / HALF_LIFE_DAYS)

        val due = task.dueAt
        if (due != null) {
            val daysUntil = (due - now) / DAY_MS
            when {
                // Closing in: pull back toward full, regardless of neglect. A
                // deadline the user set is the one signal the system did not
                // infer, so it outranks the ones it did.
                daysUntil in 0.0..DEADLINE_APPROACH_DAYS ->
                    value = value + (1.0 - value) * REVIVE_FRACTION
                // Long past, still pending, and the sky did not fall.
                daysUntil < -DEADLINE_GRACE_DAYS ->
                    value *= MISSED_DEADLINE_FACTOR
            }
        }
        return value.coerceIn(0.0, 1.0)
    }

    /** After the user pushes it away. */
    fun deferred(task: TaskEntity): Double =
        (task.salience * DEFER_FACTOR).coerceIn(0.0, 1.0)

    /** After the user opens, edits, or mentions it. */
    fun revived(task: TaskEntity): Double =
        (task.salience + (1.0 - task.salience) * REVIVE_FRACTION).coerceIn(0.0, 1.0)

    fun isQuiet(salience: Double): Boolean = salience < QUIET_THRESHOLD

    /**
     * Why this task is where it is, in one sentence.
     *
     * The whole design rests on the user trusting a list that shrinks by
     * itself, and trust here is just legibility: every disappearance has to be
     * answerable.
     */
    fun explain(task: TaskEntity, now: Long): String {
        val parts = mutableListOf<String>()
        if (task.deferCount >= 3) {
            parts += "pushed back ${task.deferCount} times"
        } else if (task.deferCount > 0) {
            parts += "pushed back once"
        }
        val due = task.dueAt
        if (due != null && (now - due) / DAY_MS > DEADLINE_GRACE_DAYS) {
            parts += "its deadline passed without consequence"
        }
        val touched = if (task.lastTouchedAt > 0L) task.lastTouchedAt else task.createdAt
        val idleDays = ((now - touched) / DAY_MS).toInt()
        if (idleDays >= 14) parts += "untouched for $idleDays days"

        return when {
            parts.isEmpty() && isQuiet(task.salience) -> "Quiet — nothing has come up about it."
            parts.isEmpty() -> ""
            else -> "Quiet — " + parts.joinToString(", ") + "."
        }
    }

    const val STATUS_PENDING = "pending"
}
