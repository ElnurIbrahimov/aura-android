package com.aura.ui.components

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aura.data.UserPreferences
import com.aura.security.AppLockState
import com.aura.security.BiometricActivityHolder
import com.aura.tools.BiometricAuthHandler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppLockEntryPoint {
    fun userPreferences(): UserPreferences
    fun appLockState(): AppLockState
    fun biometricActivityHolder(): BiometricActivityHolder
}

/**
 * Show [content] only when the app is unlocked.
 *
 * Extracted from `MainActivity`, where both the biometric prompt and the
 * `unlocked` flag were `private` and composition-scoped. That is why the lock
 * covered exactly one screen: `QuickAskActivity` runs the same `ChatViewModel`
 * with memory recall and rendered its answers with no check at all, and the
 * README's "biometric gate for app lock" described one of five entry points.
 *
 * Two things every caller must have done first, or the prompt cannot be raised:
 * the activity must be a `FragmentActivity` (androidx `BiometricPrompt`
 * requires one), and it must have published itself to [BiometricActivityHolder]
 * — which only `MainActivity` used to do, so a prompt raised anywhere else
 * found a dead `WeakReference` and reported "Activity not ready".
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val entry = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppLockEntryPoint::class.java,
        )
    }

    val enabled by entry.userPreferences().appLockEnabled
        .collectAsStateWithLifecycle<Boolean?>(initialValue = null)
    val unlocked by entry.appLockState().unlocked.collectAsStateWithLifecycle()
    var statusMessage by remember { mutableStateOf<String?>(null) }

    when {
        // Render nothing but the startup surface until the flag is known. A
        // locked app must never flash its content on a cold start, and `null`
        // here means "not read yet", not "off".
        enabled == null -> AuraStartupState()

        enabled == true && !unlocked -> AuraAppLockContent(
            statusMessage = statusMessage,
            onUnlock = {
                promptForUnlock(
                    entry = entry,
                    lifecycleOwner = lifecycleOwner,
                    onUnlocked = { entry.appLockState().unlock() },
                    onStatus = { statusMessage = it },
                )
            },
        )

        else -> content()
    }
}

/**
 * Raise the platform biometric / device-credential prompt.
 *
 * Moved here verbatim from `MainActivity` rather than reimplemented — the
 * device-credential path launches a separate system activity, and the ordering
 * around that is the part previous versions got wrong.
 */
private fun promptForUnlock(
    entry: AppLockEntryPoint,
    lifecycleOwner: LifecycleOwner,
    onUnlocked: () -> Unit,
    onStatus: (String) -> Unit,
) {
    val activity = entry.biometricActivityHolder().activity ?: run {
        onStatus("Activity not ready — try again")
        return
    }

    val mgr = BiometricManager.from(activity)
    val canAuth = mgr.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
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
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(promptInfo)
    }

    lifecycleOwner.lifecycleScope.launch {
        val result = handler.result.await()
        // Shut the executor down on every path, not just success. A cancelled
        // prompt used to leave the thread alive until the coroutine resumed.
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
