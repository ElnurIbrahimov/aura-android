package com.aura.ui.screens.hands

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.debug.HiltComposeTestActivity
import com.aura.ui.theme.AuraTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the record screen opens, and that starting a recording changes what it says.
 *
 * Narrower than it sounds, deliberately. Recording itself is driven by the accessibility
 * service, which a fresh emulator has switched off and which only the user can enable — so
 * an instrumented test cannot demonstrate a task, and pretending otherwise would mean a
 * green suite that proves nothing about the feature. What it *can* prove is the half that
 * has actually broken in this repo before: that the screen composes at all, with its
 * ViewModel built through Hilt against the real object graph.
 *
 * That is not a hypothetical failure here. MindScreen and DreamsScreen both shipped
 * defaulting a `@HiltViewModel` with Compose's plain factory and threw the moment they were
 * navigated to, for their entire existence, behind 3,000 green unit tests that cannot
 * compose a screen.
 *
 * The real proof of record mode is a person demonstrating a task in a real app and the
 * replay doing it again tomorrow. That is the device pass, and no emulator test substitutes
 * for it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordedHandReviewRenderTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltComposeTestActivity>()

    @Test
    fun theScreenOpensAndOffersToRecord() {
        composeRule.setContent {
            AuraTheme { RecordedHandReviewScreen() }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Show Aura once").assertExists()
        composeRule.onNodeWithText("Start recording").assertExists()
    }

    @Test
    fun startingARecordingWaitsForTheUserToOpenAnApp() {
        // startRecording binds lazily, because at this moment the foreground app is Aura.
        // The screen has to say so rather than claim to be recording something.
        composeRule.setContent {
            AuraTheme { RecordedHandReviewScreen() }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Start recording").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Waiting for you to open an app.").assertExists()
        composeRule.onNodeWithText("Stop recording").assertExists()
    }
}
