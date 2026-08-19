package com.aura.ui.screens

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.debug.HiltComposeTestActivity
import com.aura.ui.theme.AuraTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two screens that could not be opened.
 *
 * `MindScreen` defaulted `worldModelViewModel` and `tasteViewModel` with Compose's
 * plain `viewModel()` factory while the two parameters either side of them used
 * `hiltViewModel()`. `DreamsScreen` did the same for its single ViewModel. All
 * three are `@HiltViewModel` with `@Inject` constructors taking DAOs, so the plain
 * factory — which can only build `()`, `(SavedStateHandle)` and `(Application)`
 * constructors — threw `RuntimeException: Cannot create an instance of class …`
 * the moment either route was navigated to. Both sit two taps from a bottom-nav
 * tab.
 *
 * `HiltViewModelFactoryTest` now guards the source text, and the compiler rejects
 * the call once the import is gone. Neither of those watches a screen actually
 * open, which is the thing that was never true. This does: it composes both
 * screens inside a real `@AndroidEntryPoint` activity, so the ViewModels are
 * constructed through Hilt against the real object graph. If the factory is wrong
 * again, this fails where it failed for a user — at composition.
 *
 * Deliberately asserts only that each screen reaches first frame with its title
 * on it. The content depends on an empty database and is not the point; the point
 * is that the route opens at all.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CrashingScreensRenderTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltComposeTestActivity>()

    @Test
    fun mindScreenComposesWithHiltViewModels() {
        composeRule.setContent {
            AuraTheme { MindScreen() }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("What Aura thinks").assertExists()
    }

    @Test
    fun dreamsScreenComposesWithHiltViewModels() {
        composeRule.setContent {
            AuraTheme { DreamsScreen() }
        }
        composeRule.waitForIdle()

        // The subtitle is a literal; the title is a string resource, and this
        // asserts the screen reached first frame either way.
        composeRule.onNodeWithText("Memory consolidation summaries").assertExists()
    }
}
