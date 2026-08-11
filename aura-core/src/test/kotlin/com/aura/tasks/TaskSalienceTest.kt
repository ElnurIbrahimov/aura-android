package com.aura.tasks

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claim this feature rests on: **deferral is evidence against a task.**
 *
 * That is the opposite of what every other todo system does, so it is the thing
 * most likely to be "fixed" later by someone who assumes overdue must mean
 * urgent. These tests exist to make that a deliberate decision rather than an
 * accidental one.
 */
class TaskSalienceTest {

    private val day = 86_400_000L
    private val now = 1_760_000_000_000L

    private fun task(
        createdAt: Long = now,
        lastTouchedAt: Long = 0L,
        dueAt: Long? = null,
        salience: Double = 1.0,
        deferCount: Int = 0,
        status: String = "pending",
    ) = TaskEntity(
        id = "t1", title = "Renew the passport", createdAt = createdAt,
        lastTouchedAt = lastTouchedAt, dueAt = dueAt, salience = salience,
        deferCount = deferCount, status = status,
    )

    @Test
    fun `pushing a task away makes it quieter, not louder`() {
        val fresh = task()
        val once = TaskSalience.deferred(fresh)
        assertTrue(once < fresh.salience, "deferring raised salience — the model is inverted")
    }

    @Test
    fun `deferring repeatedly is what eventually silences a task`() {
        var current = task()
        repeat(5) { current = current.copy(salience = TaskSalience.deferred(current)) }
        assertTrue(
            TaskSalience.isQuiet(current.salience),
            "five pushes left the task at ${current.salience}, still shouting",
        )
    }

    @Test
    fun `a deadline that passed without consequence is strong evidence against`() {
        val overdue = task(lastTouchedAt = now - 20 * day, dueAt = now - 30 * day)
        val neglectedOnly = task(lastTouchedAt = now - 20 * day, dueAt = null)
        assertTrue(
            TaskSalience.decayed(overdue, now) < TaskSalience.decayed(neglectedOnly, now),
            "a missed deadline did not count against the task",
        )
    }

    @Test
    fun `an approaching deadline outranks neglect`() {
        // The one signal the user set explicitly beats the ones inferred.
        val ignoredButDueTomorrow = task(lastTouchedAt = now - 90 * day, salience = 0.1, dueAt = now + day)
        val scored = TaskSalience.decayed(ignoredButDueTomorrow, now)
        assertTrue(
            !TaskSalience.isQuiet(scored),
            "a task due tomorrow stayed quiet at $scored — deadlines must pull back",
        )
    }

    @Test
    fun `neglect alone takes about three weeks to halve`() {
        val fresh = task(lastTouchedAt = now - 21 * day)
        val halved = TaskSalience.decayed(fresh, now)
        assertTrue(halved in 0.45..0.55, "half-life drifted: $halved")
    }

    @Test
    fun `a completed task is left exactly as it was`() {
        // Decay is a statement about attention owed. Nothing is owed on a
        // finished task, and moving its number would make the history lie.
        val done = task(lastTouchedAt = now - 400 * day, salience = 0.9, status = "done")
        assertEquals(0.9, TaskSalience.decayed(done, now))
    }

    @Test
    fun `touching a task brings it most of the way back`() {
        val forgotten = task(salience = 0.05)
        val touched = TaskSalience.revived(forgotten)
        assertTrue(touched > forgotten.salience)
        assertTrue(touched <= 1.0)
    }

    @Test
    fun `salience never escapes zero to one`() {
        val extreme = task(lastTouchedAt = now - 10_000 * day, salience = 1.0, dueAt = now - 9_000 * day)
        val scored = TaskSalience.decayed(extreme, now)
        assertTrue(scored in 0.0..1.0, "escaped range: $scored")
        assertTrue(TaskSalience.revived(task(salience = 1.0)) <= 1.0)
        assertTrue(TaskSalience.deferred(task(salience = 0.0)) >= 0.0)
    }

    @Test
    fun `a task that went quiet can always say why`() {
        val ignored = task(lastTouchedAt = now - 40 * day, dueAt = now - 30 * day, deferCount = 4, salience = 0.05)
        val why = TaskSalience.explain(ignored, now)
        assertTrue(why.contains("4 times"), "explanation lost the defer count: $why")
        assertTrue(why.contains("deadline"), "explanation lost the missed deadline: $why")
        assertTrue(why.contains("untouched"), "explanation lost the neglect: $why")
    }

    @Test
    fun `a quiet task with no story still says something rather than nothing`() {
        // A blank explanation would be the worst outcome: a task that vanished
        // and cannot account for itself is exactly what destroys trust here.
        val why = TaskSalience.explain(task(salience = 0.1), now)
        assertTrue(why.isNotBlank())
    }
}
