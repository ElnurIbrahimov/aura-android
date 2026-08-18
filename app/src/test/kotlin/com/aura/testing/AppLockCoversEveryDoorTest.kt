package com.aura.testing

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Every surface that renders the user's content checks the lock.
 *
 * The lock covered `NavGraph` and nothing else, because "unlocked" was
 * `remember { mutableStateOf(false) }` inside `MainActivity`'s composition.
 * Four other doors opened straight onto the same data:
 *
 *  - `QuickAskActivity` — the full `ChatViewModel`, with memory recall, tools
 *    and persistence, rendering its answers inline
 *  - `AskAuraWidget` — the most recent memory, verbatim, on the home screen,
 *    refreshed every thirty minutes
 *  - `ReminderWidgetProvider` — three user-authored reminder bodies
 *  - `QuickAskActivity.updateWidgetWithResponse` — the last question and answer,
 *    painted onto the widget where they outlived the unlock entirely
 *
 * None of it was hidden or subtle. It was simply never asked, because the lock
 * had no representation outside the one screen that owned it.
 *
 * Source-scanned because the property is "this file consults the lock", which
 * has no runtime handle: a widget that renders memories and one that renders a
 * placeholder produce the same `RemoteViews` type. Comments are stripped, so a
 * commented-out check cannot satisfy it.
 */
class AppLockCoversEveryDoorTest {

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { it.substringBefore("//") }

    private fun app(relative: String): String {
        val file = sourceDir("src/main/kotlin/com/aura").resolve(relative)
        check(file.isFile) { "expected ${file.absolutePath} to exist" }
        return stripComments(file.readText())
    }

    @Test
    fun `activities that render content are wrapped in the lock gate`() {
        val quickAsk = app("widget/QuickAskActivity.kt")
        assertTrue(
            quickAsk.contains("AppLockGate {"),
            "QuickAskActivity renders ChatViewModel output — memory recall, tools, the lot — " +
                "without AppLockGate. It is one tap from the home screen and was the widest of " +
                "the four holes.",
        )
        assertTrue(
            quickAsk.contains("FragmentActivity"),
            "The biometric prompt needs a FragmentActivity. As a ComponentActivity the gate " +
                "renders and can never be satisfied, which locks the user out rather than in.",
        )
        assertTrue(
            quickAsk.contains("biometricHolder.activity = this"),
            "BiometricActivityHolder is how the prompt finds an activity. Without this the gate " +
                "reports 'Activity not ready — try again', forever.",
        )

        val main = app("MainActivity.kt")
        assertTrue(
            main.contains("AppLockGate {"),
            "MainActivity must use the shared gate rather than a private copy — the private copy " +
                "is what made the lock a property of one composition.",
        )
    }

    @Test
    fun `widgets consult the lock before reading anything`() {
        val ask = app("widget/AskAuraWidget.kt")
        assertTrue(
            ask.contains("appLockEnabled"),
            "AskAuraWidget paints the most recent memory onto the home screen and never asked " +
                "whether the app was locked. Its entry point already exposed userPreferences.",
        )

        val reminders = app("widget/ReminderWidgetProvider.kt")
        assertTrue(
            reminders.contains("appLockEnabled"),
            "ReminderWidgetProvider renders user-authored reminder text on the home screen.",
        )
    }

    @Test
    fun `the quick-ask answer is not painted onto the widget while the app can lock`() {
        // The subtlest of the four: gating the activity does not help, because
        // by then the text is already in the launcher's process and survives
        // every subsequent lock.
        val quickAsk = app("widget/QuickAskActivity.kt")
        val echo = quickAsk.substringAfter("private fun updateWidgetWithResponse")
        assertTrue(
            echo.contains("appLockEnabled"),
            "updateWidgetWithResponse writes 'Q: … A: …' into the widget. With app lock on it " +
                "must not, because what it writes outlives the unlock.",
        )
    }

    @Test
    fun `the lock is process-scoped rather than owned by a composition`() {
        val main = app("MainActivity.kt")
        assertTrue(
            !main.contains("var unlocked by remember"),
            "`unlocked` is back inside a composition. That is the original defect: every other " +
                "entry point then has no way to see it, and the lock silently covers one screen.",
        )
        val appClass = app("AuraApp.kt")
        assertTrue(
            appClass.contains("onActivityStarted") && appClass.contains("onActivityStopped"),
            "AppLockState relocks on a started-activity count reaching zero, and AuraApp is what " +
                "feeds it. Without the callbacks the app never relocks at all.",
        )
    }
}
