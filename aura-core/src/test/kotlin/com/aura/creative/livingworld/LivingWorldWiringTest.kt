package com.aura.creative.livingworld

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Guards the two ways this feature could quietly become dead substrate.
 *
 * This codebase has a documented history of it — a canon fact store with
 * tables, DAOs, migrations, backup coverage and tests that nothing ever writes
 * a row to; an entire realtime voice stack that nothing ever starts. Both
 * compile, both are tested, and neither runs. A living world is a bigger
 * temptation than either, so the two things that make it real are asserted by
 * the build rather than remembered.
 */
class LivingWorldWiringTest {

    @Test
    fun `the scheduler is reachable from application start`() {
        val bootstrap = sourceDir("src/main/kotlin/com/aura/proactive")
            .listFiles { f -> f.name == "ProactiveBootstrap.kt" }
            ?.toList()
            .orEmpty()
            .requireNonEmpty("ProactiveBootstrap.kt")
            .first()
            .readText()

        assertTrue(
            bootstrap.contains("LivingWorldScheduler"),
            "nothing schedules the world ticker from app start, so worlds would only ever move " +
                "while someone was looking at them",
        )
        assertTrue(
            bootstrap.contains("livingWorldEnabled"),
            "the ticker is not gated on its preference, so the Settings toggle would do nothing",
        )
    }

    /**
     * `ProactiveEventBus` has `replay = 0`, so an emit from a background worker
     * with no live collector is dropped and never persisted. `DaemonWorker` has
     * exactly this bug at five sites today and those insights are simply gone.
     * `ProactiveEvents.record` inserts to Room first, which is why it is the
     * only sanctioned path out of this package.
     */
    @Test
    fun `nothing in the living world package emits on the proactive bus`() {
        val files = sourceDir("src/main/kotlin/com/aura/creative/livingworld")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .requireNonEmpty("living world source files")

        val offenders = files.filter { file ->
            val body = file.readText().lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
            // Emitting is the hazard, not naming. The report type itself lives
            // inside `ProactiveEventBus.Event`, so constructing one is both
            // necessary and safe; what must not happen is holding the bus and
            // pushing to it. Banning the bare name flagged the correct code.
            body.contains("eventBus.emit") ||
                body.contains("bus.emit") ||
                body.contains(".tryEmit(") ||
                Regex(""":\s*ProactiveEventBus\s*[,)]""").containsMatchIn(body)
        }

        assertTrue(
            offenders.isEmpty(),
            "these files reach the proactive bus directly, where a background emit is dropped silently: " +
                offenders.joinToString { it.name } + ". Use ProactiveEvents.record instead.",
        )
    }

    /**
     * The engine must stay free of Android and of the network. It is the reason
     * a world can be caught up from a cold start, replayed to reconstruct the
     * past, and tested without a key — and all three are lost the moment
     * something in here needs a `Context` or a round-trip.
     */
    @Test
    fun `the engine has no Android or network dependencies`() {
        val pure = listOf(
            "WorldEngine.kt", "WorldClock.kt", "WorldRng.kt", "WorldModel.kt", "WorldSeeder.kt",
            "WorldReplayer.kt", "TimelineDiff.kt",
        )
        val dir = sourceDir("src/main/kotlin/com/aura/creative/livingworld")
        val files = pure.map { name ->
            dir.resolve(name).also { check(it.isFile) { "expected engine file $name is missing" } }
        }.requireNonEmpty("engine source files")

        val offenders = files.filter { file ->
            val text = file.readText()
            text.contains("import android.") ||
                text.contains("import androidx.") ||
                text.contains("okhttp3") ||
                text.contains("Brain") ||
                text.contains("ProviderRegistry")
        }
        assertTrue(
            offenders.isEmpty(),
            "the world engine picked up a platform or network dependency: " + offenders.joinToString { it.name },
        )
    }

    /**
     * A model call inside the tick loop would break determinism, break the
     * bounded catch-up, break testability, and uncap the cost — four properties
     * traded for one adjective.
     */
    @Test
    fun `the tick loop never calls a model`() {
        val engine = sourceDir("src/main/kotlin/com/aura/creative/livingworld").resolve("WorldEngine.kt").readText()
        // Deliberately precise rather than substring-broad: a bare `stream(`
        // scan matches the engine's own `WorldRng.substream(`, which is exactly
        // the sort of false positive that gets a useful gate deleted.
        for (forbidden in listOf("suspend fun tick", "brain.", "providerRegistry.", ".chat(")) {
            assertTrue(
                !engine.contains(forbidden),
                "WorldEngine contains '$forbidden' — the tick must stay pure and synchronous",
            )
        }
    }
}
