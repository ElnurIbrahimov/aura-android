package com.aura.testing

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Every periodic worker the app schedules must be rescheduled after a reboot.
 *
 * `BootReceiver` exists because some OEMs clear WorkManager state on cold boot.
 * It covered seven of the eleven workers and omitted `BackupWorker`,
 * `PlaceLogWorker` and `ProjectLedgerWorker` — so on exactly the phones it was
 * written for, the weekly automatic backup stopped at the first reboot and
 * stayed stopped until the app was next opened. `allowBackup="false"` is the
 * right call, which makes that backup the only off-device copy of the memory
 * store, and ENGINEERING_HISTORY calls losing it "the only unrecoverable
 * failure mode in the app".
 *
 * Nothing caught it. `BootReceiver` had no test of any kind, and a worker that
 * was never scheduled leaves no run record to be conspicuously absent — the
 * absence looks exactly like a quiet week.
 *
 * The list is **derived from `ProactiveScheduler`**, not written down here, so
 * adding a `scheduleX()` there fails this test until boot covers it too. A
 * hand-maintained list would have been correct on the day it was written and
 * wrong by the next worker, which is how the original gap opened.
 */
class BootReschedulesEveryWorkerTest {

    /**
     * Workers deliberately not rescheduled at boot, with the reason.
     *
     * Empty is the correct state. An entry here is a claim that a worker loses
     * nothing by being skipped until the next app launch, and it has to be true
     * of the worker, not merely convenient.
     */
    private val exempt = mapOf<String, String>()

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { it.substringBefore("//") }

    /**
     * The repo root, located by walking up to `settings.gradle.kts`.
     *
     * Not derived from [sourceDir]: Gradle runs these with the `app` module as
     * the working directory and IDE runners use the repo root, so a fixed number
     * of `parentFile` hops is right in one harness and wrong in the other.
     * Failing loudly beats reading the wrong module's sources, which would make
     * every assertion below vacuous.
     */
    private fun repoRoot(): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir ?: error("repo root not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `boot reschedules every worker ProactiveScheduler knows how to schedule`() {
        val schedulerSource = stripComments(
            repoRoot()
                .resolve("aura-core/src/main/kotlin/com/aura/proactive/ProactiveScheduler.kt")
                .also { check(it.isFile) { "ProactiveScheduler not found at ${it.absolutePath}" } }
                .readText(),
        )
        val bootSource = stripComments(
            sourceDir("src/main/kotlin")
                .resolve("com/aura/proactive/BootReceiver.kt")
                .also { check(it.isFile) { "BootReceiver not found at ${it.absolutePath}" } }
                .readText(),
        )

        val scheduleFns = Regex("""fun\s+(schedule\w+)\s*\(""")
            .findAll(schedulerSource)
            .map { it.groupValues[1] }
            .toList()
            .requireNonEmpty("schedule functions on ProactiveScheduler")

        val missing = scheduleFns
            .filter { it !in exempt }
            .filterNot { bootSource.contains("$it(") }

        assertTrue(
            missing.isEmpty(),
            "BootReceiver does not reschedule: ${missing.joinToString(", ")}\n\n" +
                "Some OEMs clear WorkManager state on cold boot, which is the only reason " +
                "BootReceiver exists. A worker missing from it stops at the first reboot and " +
                "stays stopped until the app is next opened, leaving no run record behind. " +
                "Either call it there or add it to `exempt` with a reason that is true of the " +
                "worker — LivingWorldTickWorker qualifies because WorldClock derives the due " +
                "tick from wall time, so nothing is lost by ticking late.",
        )
    }

    @Test
    fun `boot schedules through the shared scheduler rather than rebuilding requests`() {
        // The inline copies had already drifted: the dream request lost its
        // two-hour initial delay, so a reboot could start a full consolidation
        // pass while the phone was still settling. `UPDATE` policy means the
        // weaker copy silently replaces the real schedule rather than failing.
        val bootSource = stripComments(
            sourceDir("src/main/kotlin").resolve("com/aura/proactive/BootReceiver.kt").readText(),
        )
        assertTrue(
            !bootSource.contains("PeriodicWorkRequestBuilder"),
            "BootReceiver builds a PeriodicWorkRequest itself. Every request has exactly one " +
                "correct definition, in ProactiveScheduler (or the worker's own scheduler), and " +
                "a second copy here drifts from it silently — constraints, tags and initial " +
                "delays are all lost without any failure.",
        )
    }
}
