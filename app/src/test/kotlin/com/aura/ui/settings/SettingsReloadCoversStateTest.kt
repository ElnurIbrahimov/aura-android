package com.aura.ui.settings

import com.aura.testing.requireNonEmpty
import com.aura.testing.sourceDir
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Every field of [SettingsUiState] is either restored by `reload()` or listed
 * here as deliberately reset.
 *
 * `reload()` does not patch the state, it *assigns a whole new*
 * `SettingsUiState`. Anything it forgets silently reverts to that field's
 * default and never comes back, because the collectors that fill those fields
 * are `StateFlow` subscriptions and a `StateFlow` does not re-emit a value a
 * consumer discarded.
 *
 * That defect has now occurred **five** times in this one function:
 *
 *  - `credentialStates` — every saved API key displayed as "Unsaved draft".
 *  - `keyDrafts` — a key typed during the ~80 sequential DataStore reads
 *    `reload()` performs was erased, and "Save & Test" then wrote the empty
 *    draft, which `ProviderKeys.set` treats as a clear.
 *  - the catalog lists — every model picker in Settings emptied.
 *  - `customBaseUrl` / `customApiKey` / `customIsConfigured` — a configured
 *    custom endpoint read as blank and refused to save.
 *
 * Each was found separately, months apart, by someone noticing the symptom.
 * The mechanism is identical every time, so it gets a gate rather than a sixth
 * discovery. A runtime test cannot express this: it would have to know which
 * fields matter, which is the same knowledge this asserts.
 *
 * Adding a field to [SettingsUiState] therefore forces a decision — restore it
 * in `reload()`, or name it below and say why it resets.
 */
class SettingsReloadCoversStateTest {

    /**
     * Fields `reload()` is *supposed* to blank, with the reason.
     *
     * Keep this short. Every entry is a field whose value is thrown away on
     * every reload, so a wrong entry here is the defect this test exists to
     * catch, wearing a permission slip.
     */
    private val deliberatelyReset = mapOf(
        "verifying" to "in-flight provider test; a reload means it is no longer in flight",
        "providerTests" to "the transient ✓/✗ result line, not the credential state behind it",
        "verifyResults" to "same, in its legacy string form",
        "customTesting" to "in-flight custom endpoint test",
        "customResult" to "the transient ✓/✗ line for the custom endpoint",
        "mcpDiscoveredTools" to "explicitly re-assigned to emptyMap() in reload; rediscovered on connect",
        "smtpTesting" to "in-flight SMTP test",
        "smtpResult" to "the transient ✓/✗ line for SMTP",
        "dreamRunning" to "in-flight manual dream cycle",
    )

    /**
     * Fields the assignment omits and the line *after* it puts back.
     *
     * `reload()` ends with `applyCatalog(modelCatalogRepository.catalog.value)`,
     * which is a `_state.update { it.copy(...) }` over the catalog-derived
     * fields. They are genuinely restored — just not in the constructor call —
     * so they are listed separately rather than filed under "deliberately
     * reset". The distinction is the point: one list means "this value comes
     * back", the other means "this value is thrown away", and merging them
     * would let a future omission hide behind the wrong label.
     */
    private val restoredByFollowUp = mapOf(
        "availableModels" to "applyCatalog",
        "imageModels" to "applyCatalog",
        "videoModels" to "applyCatalog",
        "voiceModels" to "applyCatalog",
        "embeddingModels" to "applyCatalog",
        "configuredProviders" to "applyCatalog (also assigned in the constructor call)",
        "modelsLoading" to "applyCatalog, from the live provider statuses",
        "modelsError" to "applyCatalog, from the live provider failures",
    )

    @Test
    fun `reload restores every settings field that is not deliberately reset`() {
        val source = sourceDir("src/main/kotlin/com/aura/ui/settings")
            .resolve("SettingsViewModel.kt")
        assertTrue(source.isFile, "SettingsViewModel.kt not found at ${source.absolutePath}")
        val text = source.readText()

        val declared = declaredStateFields(text).requireNonEmpty("SettingsUiState fields").toSet()
        val assigned = fieldsAssignedInReload(text).requireNonEmpty("fields assigned in reload()").toSet()

        val missing = declared - assigned - deliberatelyReset.keys - restoredByFollowUp.keys

        assertTrue(
            missing.isEmpty(),
            "reload() rebuilds SettingsUiState and does not restore: ${missing.sorted().joinToString(", ")}.\n" +
                "Each of these silently reverts to its default on every reload and never comes back.\n" +
                "Either assign it in reload(), or add it to `deliberatelyReset` with the reason.",
        )
    }

    @Test
    fun `every follow-up restoration actually happens in reload`() {
        // A field excused as "applyCatalog puts it back" is only excused while
        // reload() still calls applyCatalog. If that call is removed, these
        // five silently revert to their empty defaults and every model picker
        // in Settings empties — which is exactly what happened once already.
        val text = sourceDir("src/main/kotlin/com/aura/ui/settings")
            .resolve("SettingsViewModel.kt").readText()
        val reload = text.indexOf("fun reload()")
        // Four-space indent identifies the next top-level member; nothing
        // inside reload() is indented that shallowly.
        val nextFun = text.indexOf("    fun ", reload + 10)
        val body = text.substring(reload, if (nextFun > reload) nextFun else text.length)

        assertTrue(
            "applyCatalog(" in body,
            "applyCatalog(...) is no longer called inside reload(); the fields listed in " +
                "restoredByFollowUp are now silently blanked on every reload.",
        )
    }

    @Test
    fun `the allowlist names only fields that still exist`() {
        // A stale entry is a hole: the field it excused could be renamed and
        // its replacement would go unguarded while this list still looks full.
        val declared = declaredStateFields(
            sourceDir("src/main/kotlin/com/aura/ui/settings").resolve("SettingsViewModel.kt").readText(),
        ).requireNonEmpty("SettingsUiState fields").toSet()

        val stale = (deliberatelyReset.keys + restoredByFollowUp.keys) - declared
        assertTrue(
            stale.isEmpty(),
            "the allowlists name fields that no longer exist: ${stale.sorted().joinToString(", ")}",
        )
    }

    /** Property names declared on the `SettingsUiState` data class. */
    private fun declaredStateFields(text: String): List<String> {
        val start = text.indexOf("data class SettingsUiState(")
        check(start >= 0) { "SettingsUiState declaration not found — this scan would be vacuous" }
        val body = text.substring(start, text.indexOf("\n)", start).takeIf { it > start } ?: text.length)
        return Regex("""^\s*val\s+(\w+)\s*:""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    /** Named arguments passed to the `SettingsUiState(` constructed inside `reload()`. */
    private fun fieldsAssignedInReload(text: String): List<String> {
        val reload = text.indexOf("fun reload()")
        check(reload >= 0) { "reload() not found — this scan would be vacuous" }
        val ctor = text.indexOf("_state.value = SettingsUiState(", reload)
        check(ctor >= 0) { "reload() no longer assigns a whole SettingsUiState. If it now patches state, this test is obsolete — delete it and say so." }
        val end = text.indexOf("\n            )", ctor)
        check(end > ctor) { "could not find the end of the SettingsUiState( call in reload()" }
        return Regex("""^\s*(\w+)\s*=""", RegexOption.MULTILINE)
            .findAll(text.substring(ctor, end))
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }
}
