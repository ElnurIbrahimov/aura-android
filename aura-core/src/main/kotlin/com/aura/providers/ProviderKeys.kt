package com.aura.providers

import com.aura.security.SecureDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source of truth for cloud-provider API keys and embedding model settings.
 * Each key is the value the user pasted into the Settings UI (Ollama Cloud,
 * Anthropic, OpenAI, DeepSeek).
 *
 * Previously, [ProviderModule] read these from `System.getenv(...)` at Hilt
 * graph creation time, which meant the Settings UI was write-only — the user
 * typed a key, the DataStore stored it, but the provider instances already
 * constructed with the env var never saw the change. Even restarting the app
 * did not help because the env var didn't change.
 *
 * This singleton reads the keys from DataStore on demand. The Hilt graph still
 * constructs one ProviderKeys instance (singleton), and providers call
 * [keyFor] at every [Provider.chat] / [Provider.isConfigured] call. The
 * StateFlow cache is invalidated per-provider on every DataStore write
 * (no full loadAllKeys after each set).
 *
 * The initial load runs asynchronously to avoid ANR during Hilt graph
 * construction. [keyFor] returns null until that load completes. To avoid a
 * concurrent Settings write being overwritten by the late init load, all
 * writes and reads that need a consistent snapshot go through [stateMutex].
 *
 * Mirrors the role aura_python/api_keys.py played in the Python codebase.
 */
@Singleton
class ProviderKeys @Inject constructor(
    private val secureDataStore: SecureDataStore,
) {
    /**
     * The raw key-value map, published for backward compatibility.
     * Only contains entries for providers with non-blank keys.
     * Use [credentialStates] for richer lifecycle information.
     */
    private val _state = MutableStateFlow<Map<String, String>>(emptyMap())
    val state: StateFlow<Map<String, String>> = _state.asStateFlow()

    /**
     * Per-provider credential lifecycle state. Each provider in [PREFIXES]
     * appears as a key in this map.
     *
     * Transitions:
     * - [ProviderCredentialState.Loading] → [ProviderCredentialState.NotConfigured]
     *   (no saved key) or [ProviderCredentialState.Saved] (key loaded) or
     *   [ProviderCredentialState.StorageError] (decryption failure)
     * - [ProviderCredentialState.NotConfigured] → [ProviderCredentialState.Saved]
     *   (via [set])
     * - [ProviderCredentialState.Saved] → [ProviderCredentialState.NotConfigured]
     *   (via [set] with blank key)
     * - Any → [ProviderCredentialState.StorageError] on decryption failure
     */
    private val _credentialStates = MutableStateFlow(
        PREFIXES.associateWith { ProviderCredentialState.Loading }
    )
    val credentialStates: StateFlow<Map<String, ProviderCredentialState>> =
        _credentialStates.asStateFlow()

    /**
     * True once the initial DataStore load completes. The Settings
     * and Onboarding screens wait on this before showing the
     * "configured providers" list so the user never sees a
     * momentary "0 configured" flicker. Set in [init] after
     * per-provider state is populated.
     *
     * Always becomes true even when individual providers encounter
     * decryption errors — consumers are never stuck in a
     * perpetual loading state.
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Explicit embedding model id; blank means use the local embedder. */
    private val _embeddingModel = MutableStateFlow("")
    val embeddingModel: String get() = _embeddingModel.value

    // Process-scoped: the @Singleton lives for the lifetime of the app, so we
    // don't need to cancel the scope explicitly.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    /**
     * Guards all state mutations. The initial async load in [init] and the
     * explicit write in [set] both go through this mutex to prevent the init
     * load from overwriting a user's freshly-written key.
     */
    private val stateMutex = Mutex()

    /**
     * Asynchronous initial load. We do NOT block here — Hilt graph construction
     * is on the main thread, and a blocking DataStore read on it can ANR.
     * Instead, [keyFor] returns null until the load completes; the user sees
     * "no provider configured" for a few hundred ms after app start, then
     * their saved key takes effect. This is the same behavior as before the
     * fix (the env-var approach also had no notion of "saved in DataStore").
     *
     * Individual decryption failures are handled per-provider (resulting in
     * [ProviderCredentialState.StorageError]) so a corrupted entry for one
     * provider does not block all other providers or leave [loaded] stuck at
     * false.
     */
    init {
        scope.launch { loadOnce() }
    }

    /**
     * The initial load, extracted from [init] so it can be cancelled by a test.
     *
     * [scope] is process-scoped and never cancelled — correct for a `@Singleton`
     * that lives as long as the app, and also the reason the cancellation path
     * here is unreachable from a test that can only construct this class. That
     * path is where `loaded` used to be announced over state nobody published,
     * so leaving it untestable is how the bug survived. Running this directly
     * inside a job the test owns is the only seam that makes it provable.
     */
    internal suspend fun loadOnce() {
        val values = mutableMapOf<String, String>()
        val states = mutableMapOf<String, ProviderCredentialState>()

        try {
            for (prefix in PREFIXES) {
                try {
                    val key = secureDataStore.getString("${prefix}_api_key")?.takeIf { it.isNotBlank() }
                    if (key == null) {
                        states[prefix] = ProviderCredentialState.NotConfigured
                    } else {
                        values[prefix] = key
                        states[prefix] = ProviderCredentialState.Saved
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    states[prefix] = ProviderCredentialState.StorageError
                }
            }

            // Guarded separately, and not folded into the block below.
            //
            // This read sits after the provider loop but before the publish,
            // so letting it throw past here discards every provider state
            // just gathered: `_credentialStates` stays at its initial
            // `Loading` for all of them, permanently. With `_loaded` now
            // flipped in a `finally`, that combination is worse than the
            // original hang was honest about — consumers would be told
            // loading had finished while every provider still read
            // "Loading". A blank model is the documented meaning of "use
            // the local embedder", so falling back to it costs nothing.
            val model = try {
                loadEmbeddingModel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ProviderKeys", "embedding model unreadable; using local", e)
                ""
            }
            publish(values, states, model)
        } catch (e: CancellationException) {
            // Deliberately falls through without marking loaded.
            //
            // This used to be a `finally`, which also runs while a
            // CancellationException is on its way out — so a cancelled load
            // announced `loaded = true` over a `_credentialStates` that was
            // still `Loading` for every provider, and over `_values` that had
            // never been written. `awaitLoaded()` returned to a caller that
            // then read state nobody had published. In production the scope is
            // process-scoped and never cancelled, so this was latent; under a
            // test JVM it is reachable, and "latent" is not the same as "not a
            // bug".
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception, and that distinction is the whole point.
            //
            // The per-provider catch inside the loop is also Exception, so an Error passes
            // through both and reaches nothing that marks the load finished. `_loaded` stays
            // false with nothing left that could flip it, and every caller of awaitLoaded()
            // suspends for the life of the process. That is the forty-minute CI hang the
            // original `finally` existed to prevent, and narrowing this catch reintroduced it
            // within the hour — as a TestTimedOutException plus an UncaughtExceptionsBeforeTest
            // landing on whichever test happened to run next.
            //
            // Cancellation is the one exit that may leave `_loaded` false, and it is handled
            // above. Everything else must mark it.
            //
            // Logged, not thrown. This body runs on a process-scoped
            // SupervisorJob with no parent to receive a failure, so an
            // escaping exception becomes an uncaught one — which is how a
            // fault here surfaced as `UncaughtExceptionsBeforeTest` inside
            // whichever test happened to run next, naming a test that was
            // perfectly fine.
            android.util.Log.w(
                "ProviderKeys",
                "initial key load failed; some provider states may be incomplete: ${e.message}",
                e,
            )
            // Terminal, and complete. The KDoc on `loaded` promises consumers
            // are "never stuck in a perpetual loading state", and this is where
            // that promise is kept for every failure that is not a cancellation:
            // whatever the per-provider loop gathered still goes out, so no
            // provider is left reading `Loading` behind a `loaded` that says the
            // load finished. Leaving it unpublished was the 40-minute CI hang.
            //
            // NonCancellable because this is the last write of a load that has
            // already failed; being cancelled here would strand the very state
            // this branch exists to publish.
            //
            // And if even that throws, mark loaded anyway, without the lock. The ordering
            // guarantee is worth having and is not worth a permanent hang: a caller that
            // reads half-written state recovers on the next read, a caller suspended on
            // `_loaded.first { it }` never does.
            runCatching { withContext(NonCancellable) { publish(values, states, "") } }
                .onFailure {
                    _state.value = values.toMap()
                    _credentialStates.value = states.toMap()
                    _loaded.value = true
                }
        }
    }

    /**
     * Write the loaded state and mark the load finished, atomically.
     *
     * `_loaded` is assigned **inside** the same lock as the state it announces,
     * and last. Consumers wake on `loaded` and immediately read
     * `credentialStates`; publishing the flag anywhere else lets them observe a
     * true flag over state that was never written.
     */
    private suspend fun publish(
        values: Map<String, String>,
        states: Map<String, ProviderCredentialState>,
        model: String,
    ) {
        stateMutex.withLock {
            _state.value = values.toMap()
            _values.clear()
            _values.putAll(values)
            _credentialStates.value = states.toMap()
            _embeddingModel.value = model
            _loaded.value = true
        }
    }

    /**
     * Internal decrypted values cache. Mirrors [state] for fast [keyFor]
     * access without map reconstruction. Modified directly in [set] and
     * [init] under [stateMutex].
     */
    private val _values = mutableMapOf<String, String>()

    /**
     * Returns the current API key for the given provider prefix, or null if
     * the user hasn't set one. Called on every chat request so the most
     * recent value wins.
     */
    fun keyFor(prefix: String): String? = _state.value[prefix]?.takeIf { it.isNotBlank() }

    /** Suspend version of [keyFor] that waits for the initial DataStore load. */
    suspend fun keyForAwaiting(prefix: String): String? {
        awaitLoaded()
        return keyFor(prefix)
    }

    /** True if the user has set a non-blank key for the given prefix. */
    fun isConfigured(prefix: String): Boolean = !keyFor(prefix).isNullOrBlank()

    /**
     * Block until the initial DataStore load completes. Use only on
     * app start (Application.onCreate) where blocking the main thread
     * is acceptable — DataStore reads on a warm start complete in
     * 5-20ms and on a cold start in ~50-100ms. Calling this from
     * a UI or background thread is wrong; the caller is on a hot
     * path.
     */
    suspend fun awaitLoaded() {
        if (_loaded.value) return
        _loaded.first { it }
    }

    /**
     * Write a new key and refresh the cached state. Persisted via DataStore
     * so the value survives process death.
     *
     * Unlike the previous implementation, this updates only the affected
     * provider in memory (no full loadAllKeys after each write). This ensures
     * writes are O(1) per provider and a concurrent older write cannot
     * overwrite a newer one because the targeted provider state is always
     * derived from the most recent write that acquired [stateMutex].
     *
     * Blank or whitespace-only keys are normalized to clear, removing the
     * stored entry from both DataStore and in-memory state.
     *
     * Never logs the key value.
     */
    suspend fun set(prefix: String, key: String) {
        val trimmed = key.trim()
        val datastoreKey = "${prefix}_api_key"
        stateMutex.withLock {
            if (trimmed.isBlank()) {
                secureDataStore.removeString(datastoreKey)
                _values.remove(prefix)
                _state.value = _state.value - prefix
                _credentialStates.value = _credentialStates.value + (prefix to ProviderCredentialState.NotConfigured)
            } else {
                secureDataStore.putString(datastoreKey, trimmed)
                _values[prefix] = trimmed
                _state.value = _state.value + (prefix to trimmed)
                _credentialStates.value = _credentialStates.value + (prefix to ProviderCredentialState.Saved)
            }
        }
    }

    suspend fun markValidation(prefix: String, valid: Boolean) {
        require(prefix in PREFIXES) { "Unknown provider prefix: $prefix" }
        stateMutex.withLock {
            val current = _credentialStates.value[prefix]
            if (current == ProviderCredentialState.Saved ||
                current == ProviderCredentialState.Valid ||
                current == ProviderCredentialState.Invalid
            ) {
                _credentialStates.value = _credentialStates.value + (
                    prefix to if (valid) ProviderCredentialState.Valid
                    else ProviderCredentialState.Invalid
                )
            }
        }
    }

    /** Load the explicit embedding model from DataStore. */
    private suspend fun loadEmbeddingModel(): String {
        val saved = secureDataStore.getString("embedding_model")
        return saved?.trim().orEmpty()
    }

    /** Persist and immediately expose a new embedding model. */
    suspend fun setEmbeddingModel(model: String) {
        val value = model.trim()
        if (value.isBlank()) {
            secureDataStore.removeString("embedding_model")
        } else {
            secureDataStore.putString("embedding_model", value)
        }
        stateMutex.withLock {
            _embeddingModel.value = value
        }
    }

    companion object {
        /**
         * Every provider prefix Aura knows about. Adding a new provider means
         * (1) adding its prefix here so the Settings UI persists its key,
         * (2) wiring it into [com.aura.providers.ProviderModule] or
         * [com.aura.capabilities.di.CapabilityModule] for the chat/capability
         * multibindings, (3) surfacing it in the Settings screen.
         */
        val PREFIXES = listOf(
            // Chat providers
            "ollama", "anthropic", "openai", "deepseek", "gemini", "groq", "openrouter",
            "mistral", "xai", "together", "cerebras", "nvidia", "llama", "chatgpt",
            "agnes", "custom", "moa",
            // Search & content
            "brave", "tavily", "firecrawl", "exa", "jina",
            // Capabilities (TTS, image, video, 3D)
            "elevenlabs", "stability", "kling", "worldlabs",
        )
    }
}
