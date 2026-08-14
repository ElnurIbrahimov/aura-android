package com.aura.ui.screens.home

import androidx.compose.ui.test.assertIsDisplayed

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.aura.ui.theme.AuraTheme
import com.aura.ui.viewmodel.HomeLoadState
import com.aura.ui.viewmodel.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loading_never_flashes_empty_content() {
        composeRule.setContent {
            AuraTheme(themeMode = "light") {
                HomeContent(
                    state = HomeUiState(loadState = HomeLoadState.Loading),
                    greeting = "Good morning",
                    dateLabel = "Tuesday, July 14",
                )
            }
        }

        composeRule.onNodeWithTag("home-loading").assertIsDisplayed()
        composeRule.onNodeWithTag("home-empty").assertDoesNotExist()
    }

    @Test
    fun empty_home_keeps_primary_composer_and_turns_zero_counts_into_actions() {
        composeRule.setContent {
            AuraTheme(themeMode = "light") {
                HomeContent(
                    state = HomeUiState(loadState = HomeLoadState.Empty),
                    greeting = "Good morning",
                    dateLabel = "Tuesday, July 14",
                )
            }
        }

        // "Create task" was asserted here and exists nowhere in the app. These
        // are instrumented tests, so they only run on a device and this one
        // never had — written, committed, counted in the "64 instrumented test
        // methods" figure, and incapable of passing. Asserting the strings the
        // screen actually renders is the honest repair; changing shipped copy to
        // match a test that has never run would be the wrong way round.
        //
        // "Ask Aura" is the exception and was a real defect: HomePrimaryAction
        // declared a `label` parameter, HomeContent passed it deliberately
        // ("Continue" with history, "Ask Aura" without), and the body never
        // rendered it. That is fixed in the component, not here.
        composeRule.onNodeWithTag("home-empty").assertIsDisplayed()
        composeRule.onNodeWithText("Ask Aura").assertIsDisplayed()
        composeRule.onNodeWithTag("home-ask-input").assertIsDisplayed()
        composeRule.onNodeWithText("Plan my day").assertIsDisplayed()
        composeRule.onNodeWithText("What's next?").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("0").fetchSemanticsNodes().size)
    }

    @Test
    fun populated_home_renders_one_current_priority_and_count_metadata() {
        composeRule.setContent {
            AuraTheme(themeMode = "dark") {
                HomeContent(
                    state = HomeUiState(
                        loadState = HomeLoadState.Content,
                        pendingTasks = listOf("Ship the build", "Review release notes"),
                        toolsCount = 38,
                    ),
                    greeting = "Good evening",
                    dateLabel = "Tuesday, July 14",
                )
            }
        }

        // "2 open" and "38 available" were asserted here and exist nowhere in
        // the app: the destinations row says "All 38 tools", and no surface
        // renders an "N open" count at all. Same provenance as the empty-state
        // assertions above — never run, never able to pass.
        //
        // What the screen does promise, and what is asserted instead: the
        // highest-priority pending task is surfaced by name, under the priority
        // heading, with the subtitle that says what kind of thing it is.
        composeRule.onNodeWithText("Current priority").assertIsDisplayed()
        composeRule.onNodeWithText("Ship the build").assertIsDisplayed()
        composeRule.onNodeWithText("Your next open task").assertIsDisplayed()

        // The tools count still has to reach the user, just in the words the UI
        // uses. Scrolled to, because the destinations row is below the fold —
        // which the original already knew, since it scrolled before the last
        // assertion and not before the others.
        // performScrollToIndex here needed [ScrollToIndex] semantics, which only
        // a lazy list publishes — `home-destinations` is a Column, so the call
        // could never have worked. Scrolling the outer LazyColumn to the target
        // node is what actually reaches it.
        composeRule.onNodeWithText("All 38 tools").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun partial_error_keeps_priority_visible_with_retryable_status() {
        composeRule.setContent {
            AuraTheme(themeMode = "light") {
                HomeContent(
                    state = HomeUiState(
                        loadState = HomeLoadState.Error(
                            message = "Calendar is unavailable",
                            hasPartialContent = true,
                        ),
                        pendingTasks = listOf("Keep working"),
                    ),
                    greeting = "Good morning",
                    dateLabel = "Tuesday, July 14",
                )
            }
        }

        composeRule.onNodeWithText("Calendar is unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Keep working").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun ask_aura_composer_sends_typed_draft() {
        var sent = ""
        composeRule.setContent {
            AuraTheme(themeMode = "light") {
                HomeContent(
                    state = HomeUiState(loadState = HomeLoadState.Empty),
                    greeting = "Good morning",
                    dateLabel = "Tuesday, July 14",
                    onAskAura = { sent = it },
                )
            }
        }

        composeRule.onNodeWithTag("home-ask-input").performTextInput("Plan my day")
        composeRule.onNodeWithTag("home-ask-send").performClick()

        composeRule.runOnIdle { assertEquals("Plan my day", sent) }
    }
}
