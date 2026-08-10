package com.aura.realtime

import com.aura.providers.ProviderKeys
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RealtimeModule {

    @Provides
    @Singleton
    fun provideOpenAiRealtimeProvider(
        providerKeys: ProviderKeys,
        httpClient: OkHttpClient,
    ): OpenAiRealtimeProvider = OpenAiRealtimeProvider(providerKeys, httpClient)

    @Provides
    @Singleton
    fun provideRealtimeProvider(impl: OpenAiRealtimeProvider): RealtimeProvider = impl

    @Provides
    @Singleton
    fun provideAudioCapture(impl: AndroidAudioCapture): AudioCapture = impl

    @Provides
    @Singleton
    fun provideAudioSink(impl: AndroidAudioSink): AudioSink = impl
}

/**
 * Which models can hold a live call, and what happens when the chosen one
 * cannot.
 *
 * Two of seventeen providers support realtime. The affordance is shown
 * **disabled with a reason** rather than hidden: a disabled row teaches that the
 * capability exists and what it needs, where a hidden one teaches nothing and
 * the user never discovers the feature.
 */
@Singleton
class RealtimeAvailability @Inject constructor(
    private val provider: OpenAiRealtimeProvider,
    private val providerKeys: ProviderKeys,
) {

    sealed class Availability {
        /** Live calling is possible with [model]. */
        data class Ready(val model: String) : Availability()

        /**
         * Possible, but only by switching models.
         *
         * The wart, surfaced rather than hidden: a user chatting with Claude or
         * Ollama who starts a call is silently moved to a different model with
         * a different personality and different memory. If that is not visible
         * it is a trust bug, so the UI must say so before the call starts.
         */
        data class WouldSwitchModel(val from: String, val to: String) : Availability()

        /** Not possible: no key for a provider that supports it. */
        data class Unavailable(val reason: String) : Availability()
    }

    suspend fun forChatModel(chatModel: String): Availability {
        val hasKey = providerKeys.keyForAwaiting(provider.prefix) != null
        if (!hasKey) {
            return Availability.Unavailable(
                "Live calling needs an OpenAI key. Add one in Settings → AI & Models.",
            )
        }
        val bare = chatModel.substringAfter(':', chatModel)
        return if (provider.supportsRealtime(bare)) {
            Availability.Ready(chatModel)
        } else {
            Availability.WouldSwitchModel(from = chatModel, to = "${provider.prefix}:$DEFAULT_REALTIME_MODEL")
        }
    }

    companion object {
        const val DEFAULT_REALTIME_MODEL = "gpt-realtime"
    }
}
