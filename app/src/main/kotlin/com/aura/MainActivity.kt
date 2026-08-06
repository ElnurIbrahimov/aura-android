package com.aura

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.aura.data.UserPreferences
import com.aura.security.BiometricActivityHolder
import com.aura.security.ScreenCaptureHolder
import com.aura.tools.BiometricAuthHandler
import com.aura.ui.components.AuraAppLockContent
import com.aura.ui.components.AuraStartupState
import com.aura.ui.nav.NavGraph
import com.aura.ui.screens.onboarding.OnboardingRoute
import com.aura.ui.theme.AuraTheme
import com.aura.ui.theme.resolvesDarkTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

import com.aura.ui.theme.AuraThemeTokens
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FirstRunGateEntryPoint {
    fun firstRunGate(): FirstRunGate
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MainActivityEntryPoint {
    fun userPreferences(): UserPreferences
    fun biometricActivityHolder(): BiometricActivityHolder
    fun oauthFlow(): com.aura.integrations.OAuthFlow
    fun integrationTokenStore(): com.aura.integrations.IntegrationTokenStore
}

data class AuraLaunchRequest(
    val sequence: Int = 0,
    val openChat: Boolean = false,
    val openMemory: Boolean = false,
    /**
     * Persisted proactive-event id of a morning brief. The chat screen
     * loads the body from Room by id — the brief text itself never
     * travels through intent extras or nav-route arguments.
     */
    val morningBriefEventId: Long? = null,
    val chatPrefillDraft: String? = null,
)

internal fun resolveAuraLaunchRequest(
    openChat: Boolean,
    openMemory: Boolean,
    morningBriefEventId: Long?,
    chatPrefillDraft: String? = null,
    previousSequence: Int,
): AuraLaunchRequest? {
    val briefId = morningBriefEventId?.takeIf { it > 0L }
    val draft = chatPrefillDraft?.trim()?.takeIf { it.isNotEmpty() }
    if (!openChat && !openMemory && briefId == null && draft == null) return null
    return AuraLaunchRequest(
        sequence = previousSequence + 1,
        openChat = openChat || briefId != null || draft != null,
        openMemory = openMemory && briefId == null && draft == null,
        morningBriefEventId = briefId,
        chatPrefillDraft = draft,
    )
}

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var incomingShareStore: IncomingShareStore
    @Inject lateinit var biometricHolder: BiometricActivityHolder
    @Inject lateinit var screenCaptureHolder: ScreenCaptureHolder

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        screenCaptureHolder.onPermissionResult(result.resultCode, result.data)
    }

    var launchRequest by mutableStateOf(AuraLaunchRequest())
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
        )
        super.onCreate(savedInstanceState)
        biometricHolder.activity = this
        screenCaptureHolder.attach(this, screenCaptureLauncher)
        handleSharedText(intent)
        handleDeepLink(intent)
        setContent {
            AuraRoot()
        }
    }

    override fun onDestroy() {
        biometricHolder.activity = null
        screenCaptureHolder.detach()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText(intent)
        handleDeepLink(intent)
    }

    private fun handleSharedText(intent: Intent?) {
        val sharedText = intent?.getStringExtra(ShareReceiverActivity.EXTRA_SHARED_TEXT)
        if (!sharedText.isNullOrBlank()) {
            incomingShareStore.set(sharedText)
        }
        val sharedImageUri = intent?.let { IntentCompat.getParcelableExtra(it, ShareReceiverActivity.EXTRA_SHARED_IMAGE_URI, Uri::class.java) }
        if (sharedImageUri != null) {
            incomingShareStore.setImageUri(sharedImageUri)
        }
    }

    private fun handleDeepLink(intent: Intent) {
        val data = intent.data
        if (data != null && data.scheme == "aura" && data.host == "oauth") {
            // OAuth redirect — handled asynchronously by OAuthFlow.
            // lifecycleScope (not a bare CoroutineScope) so the work is
            // cancelled with the activity instead of leaking past onDestroy.
            lifecycleScope.launch {
                val entry = EntryPointAccessors.fromApplication(
                    applicationContext, MainActivityEntryPoint::class.java
                )
                val oauthFlow = entry.oauthFlow()
                val userPrefs = entry.userPreferences()
                val googleClientId = userPrefs.googleClientId.first()
                val microsoftClientId = userPrefs.microsoftClientId.first()
                oauthFlow.handleRedirect(data, googleClientId.takeIf { it.isNotBlank() }, microsoftClientId.takeIf { it.isNotBlank() })
            }
            return
        }
        resolveAuraLaunchRequest(
            openChat = intent.getBooleanExtra("openChat", false),
            openMemory = intent.getBooleanExtra("openMemory", false),
            morningBriefEventId = intent.getLongExtra(
                com.aura.proactive.MorningBriefWorker.EXTRA_MORNING_BRIEF_ID, 0L,
            ),
            chatPrefillDraft = intent.getStringExtra("chatPrefillDraft"),
            previousSequence = launchRequest.sequence,
        )?.let { launchRequest = it }
    }
}

@Composable
fun AuraRoot() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainActivity = ctx as? MainActivity
    var firstRunComplete by remember { mutableStateOf<Boolean?>(null) }
    var unlocked by remember { mutableStateOf(false) }

    val mainEntry = remember {
        EntryPointAccessors.fromApplication(
            ctx.applicationContext,
            MainActivityEntryPoint::class.java,
        )
    }

    // Collect appLockEnabled as a reactive Flow so enabling it in
    // Settings takes effect immediately in the active session —
    // the user doesn't need to restart the app for the gate to engage.
    //
    // Tri-state: null = DataStore hasn't emitted yet. A cold start of a
    // locked app must NOT render NavGraph while the value is unknown —
    // the old `initialValue = false` flashed the full UI for a frame or
    // two before the lock engaged.
    val appLockEnabled by mainEntry.userPreferences().appLockEnabled
        .collectAsStateWithLifecycle<Boolean?>(initialValue = null)

    // Collect themeMode the same way so Settings theme changes
    // apply live without a restart.
    val themeMode by mainEntry.userPreferences().themeMode
        .collectAsStateWithLifecycle(initialValue = "system")
    val darkTheme = resolvesDarkTheme(themeMode, isSystemInDarkTheme())

    SideEffect {
        mainActivity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    LaunchedEffect(Unit) {
        // Request notification permission on Android 13+ so morning briefs,
        // reminders, and daemon insights can be posted. Without this, the
        // notifications are silently dropped on API 33+.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(ctx, permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                (ctx as? android.app.Activity)?.requestPermissions(arrayOf(permission), 1001)
            }
        }
        val entry = EntryPointAccessors.fromApplication(
            ctx.applicationContext,
            FirstRunGateEntryPoint::class.java,
        )
        firstRunComplete = entry.firstRunGate().isFirstRunComplete()
    }

    // When appLockEnabled transitions (unknown → known, or the user
    // flips it in Settings), reset to locked. When the value is false
    // the render branch below ignores `unlocked`, so this only bites
    // when the lock is actually on — including re-enabling it after a
    // disable, which must start locked again.
    LaunchedEffect(appLockEnabled) {
        if (appLockEnabled != null) unlocked = false
    }

    DisposableEffect(lifecycleOwner) {
        // Relock when the app goes to BACKGROUND (ON_STOP), not on
        // ON_RESUME. The device-credential unlock launches a separate
        // system activity; when it finishes, MainActivity resumes and
        // an ON_RESUME relock raced the asynchronous success callback
        // (observer could fire after the callback set unlocked = true,
        // re-locking a just-unlocked app). With ON_STOP the order is
        // deterministic: the credential activity covering us locks
        // (no-op — we're already locked), and the success callback is
        // always the last writer.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && appLockEnabled == true) {
                unlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AuraTheme(themeMode = themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize().background(AuraThemeTokens.colors.background),
            color = AuraThemeTokens.colors.background,
        ) {
            when {
                // Render nothing but the startup surface until BOTH the
                // first-run flag and the app-lock flag are known — a
                // locked app must never flash NavGraph on cold start.
                firstRunComplete == null || appLockEnabled == null -> AuraStartupState()

                firstRunComplete == false -> OnboardingRoute(
                    onComplete = { firstRunComplete = true },
                )

                appLockEnabled == true && !unlocked -> AppLockScreen(
                    onUnlocked = { unlocked = true },
                )

                else -> NavGraph(
                    launchRequest = mainActivity?.launchRequest ?: AuraLaunchRequest(),
                )
            }
        }
    }
}

@Composable
private fun AppLockScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainEntry = remember {
        EntryPointAccessors.fromApplication(
            ctx.applicationContext,
            MainActivityEntryPoint::class.java,
        )
    }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    AuraAppLockContent(
        statusMessage = statusMessage,
        onUnlock = {
            promptForUnlock(ctx, mainEntry, lifecycleOwner, onUnlocked) { message ->
                statusMessage = message
            }
        },
    )
}

private fun promptForUnlock(
    ctx: android.content.Context,
    entry: MainActivityEntryPoint,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onUnlocked: () -> Unit,
    onStatus: (String) -> Unit,
) {
    val activity = entry.biometricActivityHolder().activity ?: run {
        onStatus("Activity not ready — try again")
        return
    }

    val mgr = BiometricManager.from(ctx)
    val canAuth = mgr.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        onStatus("No biometric or device PIN set up — open Settings to add one")
        return
    }

    val handler = BiometricAuthHandler()
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    activity.runOnUiThread {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                handler.onAuthenticated()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                handler.onError(errorCode, errString.toString())
            }
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Aura")
            .setSubtitle("Authenticate to open your conversations")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(promptInfo)
    }

    lifecycleOwner.lifecycleScope.launch {
        val result = handler.result.await()
        executor.shutdownNow()
        if (result.success) {
            onUnlocked()
        } else if (result.errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
            result.errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
        ) {
            onStatus("Cancelled — tap Unlock to try again")
        } else {
            onStatus(result.errorMessage ?: "Authentication failed")
        }
    }
}
