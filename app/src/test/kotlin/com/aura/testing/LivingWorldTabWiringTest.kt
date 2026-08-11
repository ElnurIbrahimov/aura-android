package com.aura.testing

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the Living tab connected to the thing it is supposed to show.
 *
 * The tab labels are a positional `listOf` and the dispatch below it is a
 * `when` over bare integers, so inserting a tab anywhere but the end silently
 * renumbers every tab after it. That happened while this feature was being
 * built: adding "Living" at position 1 moved Manuscript from 2 to 3, and
 * nothing in the compiler or the test suite would have noticed the Manuscript
 * tab quietly rendering the Write room.
 */
class LivingWorldTabWiringTest {

    private val screen by lazy {
        sourceDir("src/main/kotlin/com/aura/ui/screens/creative")
            .resolve("CreativeProjectScreen.kt")
            .also { check(it.isFile) { "CreativeProjectScreen.kt not found" } }
            .readText()
    }

    private fun tabLabels(): List<String> {
        val line = screen.lineSequence()
            .firstOrNull { it.contains("listOf(\"World\"") }
            ?: error("could not find the tab label list in CreativeProjectScreen.kt")
        return Regex("\"([^\"]+)\"").findAll(line).map { it.groupValues[1] }.toList()
            .requireNonEmpty("tab labels")
    }

    private fun constant(name: String): Int {
        val match = Regex("private const val $name = (\\d+)").find(screen)
            ?: error("constant $name not found in CreativeProjectScreen.kt")
        return match.groupValues[1].toInt()
    }

    @Test
    fun `the Living tab exists`() {
        assertTrue("Living" in tabLabels(), "the Living tab was removed from the tab list")
    }

    @Test
    fun `tab constants match the positions of their labels`() {
        val labels = tabLabels()
        assertEquals(labels.indexOf("Living"), constant("LIVING_TAB"), "LIVING_TAB points at '${labels.getOrNull(constant("LIVING_TAB"))}'")
        assertEquals(
            labels.indexOf("Manuscript"),
            constant("MANUSCRIPT_TAB"),
            "MANUSCRIPT_TAB points at '${labels.getOrNull(constant("MANUSCRIPT_TAB"))}'",
        )
    }

    @Test
    fun `the Living tab renders the living world section`() {
        assertTrue(
            screen.contains("LIVING_TAB -> livingWorldSection"),
            "the Living tab is listed but nothing renders it",
        )
    }

    /**
     * A year of history is thousands of rows. Inside the shared `item { }` the
     * other tabs sit in, Compose measures all of them on every frame.
     */
    @Test
    fun `the living world section is a LazyListScope extension`() {
        val section = sourceDir("src/main/kotlin/com/aura/ui/screens/creative")
            .resolve("LivingWorldSection.kt")
            .also { check(it.isFile) { "LivingWorldSection.kt not found" } }
            .readText()
        assertTrue(
            section.contains("fun LazyListScope.livingWorldSection"),
            "livingWorldSection must be a LazyListScope extension, not a @Composable in a single item",
        )
    }

    @Test
    fun `the view model observes the world and can start one`() {
        val vm = sourceDir("src/main/kotlin/com/aura/ui/viewmodel")
            .resolve("CreativeStudioViewModel.kt")
            .also { check(it.isFile) { "CreativeStudioViewModel.kt not found" } }
            .readText()
        assertTrue(vm.contains("observeLivingWorld("), "nothing observes the living world")
        assertTrue(vm.contains("fun startLivingWorld("), "no way to start a world from the UI")
        assertTrue(
            vm.contains("LivingWorldScheduler.schedule"),
            "starting a world does not schedule it, so it would never tick",
        )
    }
}
