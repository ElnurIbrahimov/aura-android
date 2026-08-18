package com.aura.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.aura.agent.AgentEntity
import com.aura.ui.theme.AuraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class ChatHeaderTest {
    @get:Rule val composeRule = createComposeRule()

    /**
     * Every install has agents. `ProactiveBootstrap` seeds seven builtins on
     * startup, so the header always draws its agent picker — a fourth
     * fixed-width control beside the three the width budget used to count.
     *
     * This fixture exists because its absence is why the last fix to this
     * header shipped broken: `availableAgents` defaults to empty, no test set
     * it, and the suite therefore certified the one configuration production
     * never has. A green test is not evidence when the fixture is wrong.
     */
    private fun agents() = listOf(
        AgentEntity(
            id = "researcher",
            name = "Researcher",
            icon = "search",
            description = "",
            identity = "",
            toolsAllowed = "",
            isBuiltin = true,
        ),
    )

    @Test
    fun compact_width_keeps_model_new_chat_and_overflow_visible() {
        composeRule.setContent {
            AuraTheme {
                Box(Modifier.width(320.dp)) {
                    ChatHeader(
                        activeModel = "provider:an-extremely-long-model-name-that-must-ellipsis",
                        conversationModel = null,
                        onShowProjectPicker = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("chat-header").assertHeightIsEqualTo(56.dp)
        val pillWidth = composeRule.onNodeWithTag("chat-model-pill").fetchSemanticsNode().boundsInRoot.width
        val maxPillWidth = with(composeRule.density) { 176.dp.toPx() }
        assertTrue("model pill width $pillWidth exceeds $maxPillWidth", pillWidth <= maxPillWidth + 1f)
        composeRule.onNodeWithContentDescription("New conversation").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("More chat actions").assertIsDisplayed()
    }

    @Test
    fun compact_width_with_agents_keeps_every_control_thumb_sized() {
        // The production configuration. `assertIsDisplayed` alone is too weak
        // here — a control squeezed to a sliver is still "displayed" while
        // being impossible to hit, so the assertion is on width against the
        // app's own minimum touch target.
        composeRule.setContent {
            AuraTheme {
                Box(Modifier.width(320.dp)) {
                    ChatHeader(
                        activeModel = "provider:an-extremely-long-model-name-that-must-ellipsis",
                        conversationModel = null,
                        activeProject = "A Project With A Long Name",
                        availableAgents = agents(),
                        onShowProjectPicker = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Select agent").assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Conversation history").assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("New conversation").assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("More chat actions").assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun narrow_width_with_agents_still_reaches_the_overflow_menu() {
        // 320dp is the narrowest common phone. This is narrower still, because
        // the failure mode is a budget that runs out — and a budget that only
        // just fits at the narrowest device it was tested on has no margin.
        composeRule.setContent {
            AuraTheme {
                Box(Modifier.width(280.dp)) {
                    ChatHeader(
                        activeModel = "provider:an-extremely-long-model-name-that-must-ellipsis",
                        conversationModel = null,
                        activeProject = "Another Long Project Name",
                        availableAgents = agents(),
                        onShowProjectPicker = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("More chat actions").assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun missing_model_is_an_explicit_picker_state() {
        composeRule.setContent { AuraTheme { ChatHeader(activeModel = "", conversationModel = null, onShowProjectPicker = {}) } }

        composeRule.onNodeWithText("Choose model").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-model-missing", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun session_override_is_visibly_scoped() {
        composeRule.setContent {
            AuraTheme {
                ChatHeader(
                    activeModel = "ollama:default-model",
                    conversationModel = "anthropic:session-model",
                    onShowProjectPicker = {},
                )
            }
        }

        composeRule.onNodeWithTag("chat-session-override", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun streaming_keeps_essential_actions_visible() {
        composeRule.setContent {
            AuraTheme {
                Box(Modifier.width(320.dp)) {
                    ChatHeader(activeModel = "ollama:model", streaming = true, onShowProjectPicker = {})
                }
            }
        }

        composeRule.onNodeWithContentDescription("New conversation").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("More chat actions").assertIsDisplayed()
    }
}
