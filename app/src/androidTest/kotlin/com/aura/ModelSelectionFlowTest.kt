package com.aura

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.aura.data.UserPreferences
import com.aura.providers.InMemoryModelCatalogCache
import com.aura.providers.ModelCatalogCache
import com.aura.providers.ModelCatalogRepository
import com.aura.providers.Provider
import com.aura.providers.ProviderChunk
import com.aura.providers.ProviderKeys
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderModule
import com.aura.testing.FakeProviderController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith

/**
 * The fake provider is installed **here only**.
 *
 * It used to be a `@TestInstallIn` module, which is APK-global: every
 * instrumented test in `:app` got a provider that answered any chat with the
 * literal string "ok" and bound only `ollama`. That is correct for this test —
 * it asserts the key→verify→pick flow and must not touch a network — and it
 * made a real-model smoke suite impossible to write, because there was no way
 * to opt out. `@UninstallModules` plus a nested module scopes it to this class.
 */
@HiltAndroidTest
@UninstallModules(ProviderModule::class)
@RunWith(AndroidJUnit4::class)
class ModelSelectionFlowTest {

    /**
     * A complete stand-in for [ProviderModule], not a partial one — uninstalling
     * the real module takes the HTTP client and the catalog with it.
     */
    @Module
    @InstallIn(SingletonComponent::class)
    object FakeProviders {

        @Provides
        @Singleton
        fun httpClient(): OkHttpClient = OkHttpClient()

        @Provides
        @Singleton
        fun catalogCache(): ModelCatalogCache = InMemoryModelCatalogCache()

        @Provides
        @Singleton
        fun catalogRepository(
            registry: com.aura.providers.ProviderRegistry,
            cache: ModelCatalogCache,
        ): ModelCatalogRepository = ModelCatalogRepository(registry, cache)

        @Provides
        @IntoMap
        @StringKey("ollama")
        fun fakeProvider(keys: ProviderKeys): Provider = object : Provider {
            override val prefix = "ollama"
            override val displayName = "Ollama Cloud"
            override fun isConfigured(): Boolean = !keys.keyFor(prefix).isNullOrBlank()

            override fun chat(
                model: String,
                messages: List<ProviderMessage>,
                options: com.aura.providers.ChatOptions,
                tools: List<com.aura.providers.ToolDefinition>,
            ): Flow<ProviderChunk> = flowOf(ProviderChunk(text = "ok"))

            override suspend fun listModels(): List<String> {
                FakeProviderController.failure?.let { throw it }
                if (keys.keyFor(prefix) != FakeProviderController.validKey) {
                    throw IllegalStateException("invalid test key")
                }
                return FakeProviderController.models
            }

            override suspend fun cancel() = Unit
        }
    }

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Inject lateinit var preferences: UserPreferences
    @Inject lateinit var providerKeys: ProviderKeys

    @Before
    fun resetState() {
        hiltRule.inject()
        runCatching {
            WorkManager.initialize(
                ApplicationProvider.getApplicationContext(),
                Configuration.Builder().build(),
            )
        }
        FakeProviderController.reset()
        runBlocking {
            providerKeys.awaitLoaded()
            providerKeys.set("ollama", "")
            preferences.setDefaultModel(null)
            preferences.setFirstRunComplete(true)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    @After
    fun closeActivity() = scenario.close()

    @Test
    fun credentialToChatSelectionSurvivesRecreationAndChatOverrideStaysLocal() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithTag("provider-key-ollama-cloud")
            .performScrollTo()
            .performTextReplacement("test-key-valid")
        composeRule.onNodeWithTag("provider-test-ollama-cloud").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Verified").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Chat default").performScrollTo()
        composeRule.onAllNodesWithText("Choose")[0].performClick()
        composeRule.onNodeWithTag("model-row-ollama:model-a").performClick()
        composeRule.waitForIdle()

        scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Chat").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat-model-pill").assertTextContains("Model A", substring = true)
        composeRule.onNodeWithTag("chat-composer-input").performTextReplacement("hello")
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled()

        composeRule.onNodeWithTag("chat-model-pill").performClick()
        composeRule.onNodeWithTag("model-row-ollama:model-b").performClick()
        composeRule.onNodeWithTag("chat-model-pill").assertTextContains("Model B", substring = true)

        assertEquals("ollama:model-a", runBlocking { preferences.defaultModel.first() })
    }

}
