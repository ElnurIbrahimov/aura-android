package com.aura.testing

/**
 * Knobs for the fake provider, which now lives inside the one test that wants it.
 *
 * ## Why the module moved
 *
 * This file used to declare
 *
 * ```
 * @Module
 * @TestInstallIn(components = [SingletonComponent::class], replaces = [ProviderModule::class])
 * object FakeProviderModule
 * ```
 *
 * `@TestInstallIn` is **APK-global with no per-class opt-out**. Every
 * instrumented test in `:app`, present and future, therefore got a provider
 * whose `chat` returned `flowOf(ProviderChunk(text = "ok"))` and which bound
 * only `ollama` — every other prefix unbound. That is fine for the one test
 * asserting the key→verify→pick flow, and it makes it impossible to ever assert
 * anything against a real model, which is the entire premise of a device smoke
 * suite: the device already holds the keys.
 *
 * The replacement is Hilt's per-test pattern — `@UninstallModules` on the test
 * class plus a `@Module @InstallIn` nested inside it, which is scoped to that
 * class alone. A top-level `@InstallIn` module in this source set would have
 * reintroduced exactly the problem, since it installs everywhere too.
 *
 * The controller stays here because it is shared state, not a binding, and
 * nesting it would put a mutable object inside a test class where its `reset()`
 * discipline is easier to lose track of.
 */
object FakeProviderController {
    var models: List<String> = listOf("model-a", "model-b")
    var validKey: String = "test-key-valid"
    var failure: Throwable? = null

    fun reset() {
        models = listOf("model-a", "model-b")
        validKey = "test-key-valid"
        failure = null
    }
}
