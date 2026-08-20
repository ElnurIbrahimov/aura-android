package com.aura

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aura.core.error.CrashHandler
import com.aura.core.error.CrashLogger
import com.aura.proactive.ProactiveBootstrap
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class AuraApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var proactiveBootstrap: Provider<ProactiveBootstrap>
    @Inject lateinit var crashLogger: CrashLogger
    @Inject lateinit var appLockState: com.aura.security.AppLockState

    /**
     * Application-lifetime scope for start-up work that must not touch the main thread.
     *
     * SupervisorJob so one failed start-up task cannot take the others down, and never
     * cancelled — it lives exactly as long as the process.
     */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(crashLogger)
        registerAppLockLifecycle()
        // Do NOT block the main thread on the DataStore load. The
        // initial key load runs asynchronously in ProviderKeys.init
        // on Dispatchers.IO. UI consumers that need the keys gate on
        // providerKeys.loaded (e.g. ChatViewModel.refreshModels()
        // calls providerKeys.loaded.first { it }). ProactiveBootstrap
        // also reads gates asynchronously in its own IO scope.
        //
        // The previous runBlocking { providerKeys.awaitLoaded() } could
        // ANR on slow storage, corrupt DataStore, or large key files.
        // Scheduling and monitoring are maintenance work, not first-frame work.
        // Delayed *and* off the main thread.
        //
        // `start()` itself is cheap — it launches coroutines into its own scope and
        // returns. The expensive half is `get()`, which builds ProactiveBootstrap and,
        // through it, the ToolRegistry: 81 tools and their whole transitive graph, in one
        // 79-parameter @Provides. That ran on the main thread 750ms in, which is while the
        // user is looking at the first screen.
        //
        // Safe to construct off Main because nothing in that graph needs a Looper to be
        // built: SpeechToText is not in it at all, and TextToSpeech creates its platform
        // engine in initialize() rather than in its constructor. Both were checked before
        // this moved; a tool added later that creates a SpeechRecognizer in its constructor
        // would break here rather than obviously.
        appScope.launch {
            delay(BOOTSTRAP_DELAY_MS)
            proactiveBootstrap.get().start()
        }
    }

    /**
     * Relock when the whole app goes to the background.
     *
     * Counting started activities rather than watching one lifecycle, because
     * `MainActivity` launching `QuickAskActivity` stops `MainActivity` — a
     * per-activity relock would fire in the middle of the user's own
     * navigation and demand a fingerprint to continue what they just tapped.
     * The count only reaches zero when none of Aura's screens is showing.
     */
    private fun registerAppLockLifecycle() {
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) =
                    appLockState.onActivityStarted()

                override fun onActivityStopped(activity: android.app.Activity) =
                    appLockState.onActivityStopped()

                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
                override fun onActivityResumed(activity: android.app.Activity) = Unit
                override fun onActivityPaused(activity: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            },
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private companion object {
        /** Long enough to be after first frame; scheduling is not first-frame work. */
        const val BOOTSTRAP_DELAY_MS = 750L
    }
}