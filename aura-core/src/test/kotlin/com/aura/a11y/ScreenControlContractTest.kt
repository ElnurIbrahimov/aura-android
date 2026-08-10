package com.aura.a11y

import com.aura.agent.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Pins the privacy surface of screen control against a well-meaning future edit.
 *
 * Everything here is a text scan over sources that ship, so it runs in CI with
 * no device and no emulator — which matters because the alternative for a
 * feature like this is "someone would have noticed on a phone", and nobody
 * notices a flag that quietly starts delivering keystrokes.
 *
 * Modelled on the existing source-scanning tests, including their central
 * lesson: a scan that finds no files must FAIL, not pass. Four tests in this
 * repo once reported OK over an empty file list.
 */
class ScreenControlContractTest {

    private fun repoFile(relative: String): File {
        val candidates = listOf(File(relative), File("../$relative"), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: error("could not locate $relative from ${File(".").absolutePath}")
    }

    /**
     * The config with XML comments removed.
     *
     * Every absence assertion below failed on the first run, because the
     * comment explaining WHY a flag is absent names the flag. That is the same
     * false positive `SilentRunCatchingAuditTest` and `CheapModelResolutionScanTest`
     * both had to fix: an audit that punishes explaining the code is one that
     * gets worked around instead of kept.
     */
    private fun accessibilityConfig(): String =
        repoFile("app/src/main/res/xml/aura_accessibility_config.xml")
            .readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun manifest(): String =
        repoFile("app/src/main/AndroidManifest.xml")
            .readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    /** Kotlin source with comments removed, for the same reason. */
    private fun strippedKotlin(file: File): String =
        file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    // ---- what MUST be present -------------------------------------------

    @Test
    fun `the service declares the capabilities it needs`() {
        val xml = accessibilityConfig()
        assertTrue(xml.isNotBlank(), "the accessibility config is empty")
        assertTrue("canRetrieveWindowContent=\"true\"" in xml, "cannot read the tree without this")
        assertTrue("canPerformGestures=\"true\"" in xml, "cannot tap without this")
        // Without flagReportViewIds, viewIdResourceName is null on EVERY node
        // and element selectors collapse to text-and-bounds only — which is
        // already the Compose case and should not be the universal one.
        assertTrue("flagReportViewIds" in xml, "view ids would be null everywhere")
    }

    @Test
    fun `the manifest declaration is bindable by the system`() {
        val m = manifest()
        val block = m.substringAfter("AuraAccessibilityService", "").substringBefore("</service>")
        assertTrue(block.isNotBlank(), "AuraAccessibilityService is not declared")
        // exported="true" is REQUIRED: AccessibilityManagerService binds it
        // cross-process. Copying the notification listener's exported="false"
        // — which works only through special-case system handling — produces a
        // service Android will never bind, and the failure is silent.
        assertTrue("android:exported=\"true\"" in block, "the system cannot bind an unexported a11y service")
        assertTrue(
            "android.permission.BIND_ACCESSIBILITY_SERVICE" in block,
            "without the permission, any app could bind this service",
        )
        assertTrue("android.accessibilityservice" in block, "the meta-data pointer to the config is missing")
    }

    // ---- what MUST NOT be present ---------------------------------------

    @Test
    fun `key event filtering is not requested`() {
        // The important half. flagRequestFilterKeyEvents would deliver every
        // keystroke in every app — including passwords typed into other apps —
        // and buys nothing: reading and tapping a screen never needs it.
        assertTrue(
            "flagRequestFilterKeyEvents" !in accessibilityConfig(),
            "key event filtering would turn this into a device-wide keylogger",
        )
    }

    @Test
    fun `a static package allowlist is not used`() {
        // Scoping is enforced in code so a denylist can change at runtime and
        // cover Aura's own package. A manifest allowlist cannot be updated
        // without a release, and its presence would make the code-side rules
        // look redundant to a future reader.
        assertTrue(
            "android:packageNames" !in accessibilityConfig(),
            "scoping belongs in code, where it can cover Aura's own package",
        )
    }

    @Test
    fun `unimportant views are not included`() {
        assertTrue(
            "flagIncludeNotImportantViews" !in accessibilityConfig(),
            "roughly doubles the node count for marginal recall; the element budget is the constraint",
        )
    }

    // ---- logging hygiene -------------------------------------------------

    @Test
    fun `screen content is never logged`() {
        // One leaked log line here dumps another app's UI into logcat, where
        // any app with READ_LOGS on older devices — and anyone with adb — can
        // read it. The repo already gates logging hygiene in CI
        // (scripts/lint-logging.sh); this is the content-specific half.
        val dir = sourceDir("src/main/kotlin/com/aura/a11y")
        val files = dir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "scanned no files — a vacuous scan is the defect, not the gate")

        val offenders = mutableListOf<String>()
        files.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                if (!code.contains("Log.")) return@forEachIndexed
                // The fields that carry other apps' content. Package and
                // activity names are structural and fine to log; text,
                // contentDescription and rendered labels are not.
                val leaks = listOf(".text", "contentDescription", "label", "elements")
                if (leaks.any { it in code }) offenders += "${file.name}:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue(offenders.isEmpty(), "screen content reached a log statement:\n${offenders.joinToString("\n")}")
    }

    @Test
    fun `the traversal never returns a platform node`() {
        // minSdk 26 means recycle() is not yet a no-op, so a leaked
        // AccessibilityNodeInfo exhausts the obtain pool — and the symptom is
        // the service silently returning nothing, long after and far from the
        // leak. The rule that prevents it is that nodes never escape a
        // traversal, which is only checkable by reading the source.
        val traversal = strippedKotlin(sourceDir("src/main/kotlin/com/aura/a11y").resolve("UiTraversal.kt"))
        assertTrue(
            "AccessibilityNodeInfo" !in traversal,
            "UiTraversal must work through NodeLike only; the platform type belongs at the service edge",
        )
    }
}
