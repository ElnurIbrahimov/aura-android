package com.aura.situation

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Which app the user is in must not outlive the cache that holds it.
 *
 * This is the most invasive thing Aura reads, and the difference between
 * "knowing what you're doing right now" and "keeping a record of what you do"
 * is the whole of why it is acceptable at all. `NotificationCaptureStore` takes
 * the same stance for the same reason and states it in a comment; a comment is
 * not enforcement.
 *
 * Source-scanning because the invariant is about which types a value can reach,
 * and nothing at runtime can observe a value *not* being written.
 */
class ForegroundAppIsNeverStoredTest {

    private fun kotlinSources(): List<File> =
        sourceDir("src/main/kotlin/com/aura").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .requireNonEmpty("Kotlin sources")

    /**
     * Comments stripped before matching.
     *
     * The first version of this test matched raw file text, so a KDoc in
     * `PlaceLog` that merely *cited* `ForegroundAppReader` as precedent — "keeps
     * the switch and the grant separate for the same reason" — was reported as a
     * second consumer of the foreground app. A privacy gate that fires on prose
     * is one that gets weakened the first time it is wrong, which is the worst
     * possible outcome for this particular gate. Referencing the class in an
     * explanation is not reading it.
     */
    private fun code(file: File): String = file.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    @Test
    fun `only the situation reader consumes the foreground app`() {
        val callers = kotlinSources()
            .filter { "foregroundAppReader" in code(it) || "ForegroundAppReader" in code(it) }
            .map { it.name }
            .filterNot { it == "ForegroundAppReader.kt" }
            .sorted()

        assertTrue(
            callers == listOf("SituationReader.kt"),
            "the foreground app must reach exactly one consumer, which holds it for 60s and never writes it. " +
                "Found: $callers",
        )
    }

    @Test
    fun `Situation is not persisted anywhere`() {
        val offenders = kotlinSources().filter { file ->
            val text = file.readText()
            // A Room entity or a backup DTO that mentions the type would mean
            // the snapshot — foreground app and all — reaches disk.
            ("@Entity" in text || "Backup(" in text || "@Serializable" in text) &&
                Regex("""\bSituation\b""").containsMatchIn(text)
        }.map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "Situation reached a persisted or serialised type: $offenders",
        )
    }

    @Test
    fun `the reader is gated on both the switch and the grant`() {
        val source = sourceDir("src/main/kotlin/com/aura")
            .resolve("situation/ForegroundAppReader.kt")
            .readText()

        // Two independent conditions, deliberately: revoking a special
        // permission is buried in system settings, so "stop doing this" has to
        // be one tap inside Aura as well.
        assertTrue("appAwarenessEnabled" in source, "the in-app switch is not consulted")
        assertTrue("granted()" in source, "Android's usage-access grant is not consulted")
    }
}
