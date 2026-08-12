package com.aura.testing

import com.aura.proactive.ProactiveAction
import com.aura.proactive.ProactiveActions
import com.aura.proactive.ProactiveFindingType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every route a proactive suggestion can send you to must exist.
 *
 * Two of them did not. `contradiction_alert` carried `"graph"` while the real
 * route is `"knowledge_graph"`, and `deadline_approaching` carried `"calendar"`
 * when there is no calendar screen at all. Both would have thrown the moment
 * anything navigated — and nothing ever did, which is exactly why they survived
 * being written, reviewed and shipped.
 *
 * A source scan rather than a runtime check because the route table is a
 * `sealed class` of `data object`s with no registry to enumerate at runtime,
 * and because the opportunity side's targets are string literals in Kotlin
 * source. Same genre as `ProactiveFindingTypeCoverageTest`, which guards the
 * finding-type registry the same way.
 */
class ProactiveActionRouteTest {

    /** Every `Route`/`TopLevelRoute` path declared in the nav file. */
    private val declaredRoutes: Set<String> by lazy {
        val nav = sourceDir("src/main/kotlin/com/aura/ui/nav")
            .resolve("AuraBottomNavigation.kt")
            .also { check(it.isFile) { "AuraBottomNavigation.kt not found" } }
            .readText()
        Regex("""Route\("([^"]+)"""").findAll(nav)
            .map { it.groupValues[1] }
            .toMutableSet()
            .also { routes ->
                // TopLevelRoute entries declare their path as the first ctor arg.
                Regex("""data object \w+ : TopLevelRoute\(\s*"([^"]+)"""")
                    .findAll(nav)
                    .forEach { routes += it.groupValues[1] }
            }
            .toSet()
            .also { assertTrue(it.size >= 10, "only found ${it.size} routes — the scan is not reading the file") }
    }

    /** A route may carry arguments; the base path is what must exist. */
    private fun basePath(route: String) = route.substringBefore('?').substringBefore('/')

    @Test
    fun `every finding type navigates somewhere that exists`() {
        val broken = ProactiveFindingType.entries.mapNotNull { type ->
            val action = type.action
            if (action !is ProactiveAction.Navigate) return@mapNotNull null
            val base = basePath(action.route)
            if (declaredRoutes.any { basePath(it) == base }) null else "${type.wire} -> ${action.route}"
        }
        assertTrue(
            broken.isEmpty(),
            "these finding types point at routes that do not exist: $broken. " +
                "Declared routes: ${declaredRoutes.sorted()}",
        )
    }

    /**
     * The opportunity engine writes its targets as JSON string literals. They
     * were never parsed, so nothing has ever checked that they resolve either.
     */
    @Test
    fun `every opportunity target exists`() {
        val engine = sourceDir("../aura-core/src/main/kotlin/com/aura/world")
            .resolve("OpportunityEngine.kt")
            .also { check(it.isFile) { "OpportunityEngine.kt not found" } }
            .readText()

        val targets = Regex(""""target"\s*:\s*"([^"]+)"""").findAll(engine)
            .map { it.groupValues[1] }
            .toList()
            .requireNonEmpty("opportunity targets")

        val broken = targets.filter { target ->
            declaredRoutes.none { basePath(it) == basePath(target) }
        }
        assertTrue(broken.isEmpty(), "opportunity targets that resolve to nothing: ${broken.distinct()}")
    }

    @Test
    fun `the finding that proposes nothing offers no affordance`() {
        // pattern_alert describes a state rather than proposing a move; a card
        // with a button that goes nowhere is worse than one without a button.
        assertEquals(ProactiveAction.None, ProactiveFindingType.PATTERN_ALERT.action)
        assertEquals("", ProactiveActions.label(ProactiveAction.None))
    }

    @Test
    fun `every other finding type offers a label`() {
        val unlabelled = ProactiveFindingType.entries
            .filter { it.action != ProactiveAction.None }
            .filter { ProactiveActions.label(it.action).isBlank() }
        assertTrue(unlabelled.isEmpty(), "these have an action but no label: ${unlabelled.map { it.wire }}")
    }

    @Test
    fun `the opportunity json shape round-trips`() {
        // parse() must read exactly what OpportunityEngine already writes; this
        // pins the contract from the consumer side.
        val fromEngine = """{"action":"navigate","target":"reminders"}"""
        assertEquals(ProactiveAction.Navigate("reminders"), ProactiveActions.parse(fromEngine))
        assertEquals(fromEngine, ProactiveActions.encode(ProactiveAction.Navigate("reminders")))
    }

    @Test
    fun `a malformed suggestion makes a button inert rather than crashing the screen`() {
        assertEquals(ProactiveAction.None, ProactiveActions.parse("not json"))
        assertEquals(ProactiveAction.None, ProactiveActions.parse(""))
        assertEquals(ProactiveAction.None, ProactiveActions.parse("""{"action":"teleport","target":"x"}"""))
        assertEquals(ProactiveAction.None, ProactiveActions.parse("""{"action":"navigate","target":""}"""))
    }
}
