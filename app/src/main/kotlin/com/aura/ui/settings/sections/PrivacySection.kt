package com.aura.ui.settings.sections

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.Icons

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PrivacySection(
    appLockEnabled: Boolean,
    morningBriefEnabled: Boolean,
    morningBriefHour: Int,
    calendarMonitorEnabled: Boolean,
    decayEnabled: Boolean,
    smarterMemoryEnabled: Boolean,
    screenControlEnabled: Boolean,
    onSetScreenControlEnabled: (Boolean) -> Unit,
    appAwarenessEnabled: Boolean = false,
    onSetAppAwarenessEnabled: (Boolean) -> Unit = {},
    placeLogEnabled: Boolean = false,
    onSetPlaceLogEnabled: (Boolean) -> Unit = {},
    onSetAppLock: (Boolean) -> Unit,
    onSetMorningBrief: (Boolean) -> Unit,
    onSetMorningBriefHour: (Int) -> Unit,
    onSetCalendarMonitor: (Boolean) -> Unit,
    onSetDecayEnabled: (Boolean) -> Unit,
    onSetSmarterMemory: (Boolean) -> Unit,
    onNavigateProfile: () -> Unit,
) {
    SettingsSection(
        icon = Icons.Filled.Lock,
        title = "Privacy",
        subtitle = "Biometric lock, proactive worker toggles",
        initialExpanded = false,
    ) {
        Text(
            text = stringResource(R.string.require_biometric_authentication_to_open_aura),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(AuraSpacing.xs))

        val notificationContext = LocalContext.current
        var notificationAccessEnabled by remember {
            mutableStateOf(
                NotificationManagerCompat.getEnabledListenerPackages(notificationContext).contains(notificationContext.packageName)
            )
        }
        val notificationAccessLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            notificationAccessEnabled = NotificationManagerCompat
                .getEnabledListenerPackages(notificationContext)
                .contains(notificationContext.packageName)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.notification_access), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (notificationAccessEnabled) "Enabled - Aura can read active device notifications"
                    else "Off - enable to summarize device notifications",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            OutlinedButton(onClick = {
                notificationAccessLauncher.launch(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text(if (notificationAccessEnabled) "Manage" else "Enable") }
        }

        Spacer(modifier = Modifier.height(AuraSpacing.xs))

        // App awareness. Same two-step shape as screen control below, and for
        // the same reason: usage access is a special permission buried in
        // system settings, and jumping there before the user knows what it is
        // for asks them to trust a screen Android words far more alarmingly
        // than what Aura actually does with it.
        val usageContext = LocalContext.current
        var usageAccessGranted by remember { mutableStateOf(isUsageAccessGranted(usageContext)) }
        val usageLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { usageAccessGranted = isUsageAccessGranted(usageContext) }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "App awareness", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (appAwarenessEnabled) {
                        "On - Aura can tell which app you're in, to judge whether now is a bad moment"
                    } else {
                        "Off - Aura doesn't know what you're doing right now"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = appAwarenessEnabled, onCheckedChange = onSetAppAwarenessEnabled)
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Place log", style = MaterialTheme.typography.bodyLarge)
                Text(
                    // States the coarseness and the retention, because those are
                    // the two facts that decide whether this is acceptable, and
                    // "location" on its own implies something far more precise
                    // than what is actually stored.
                    text = if (placeLogEnabled) {
                        "On - roughly where you've been, rounded to ~100m, kept 90 days"
                    } else {
                        "Off - Aura knows nothing about where you go. Almost everything " +
                            "it knows is something you typed at it"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = placeLogEnabled, onCheckedChange = onSetPlaceLogEnabled)
        }

        if (appAwarenessEnabled) {
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (usageAccessGranted) {
                            "Usage access granted. The current app is never saved."
                        } else {
                            "Android still needs to grant Usage access"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                    )
                }
                OutlinedButton(onClick = {
                    usageLauncher.launch(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }) { Text(if (usageAccessGranted) "Manage" else "Grant") }
            }
        }

        Spacer(modifier = Modifier.height(AuraSpacing.xs))

        // Screen control. TWO steps, deliberately: a switch that only arms the
        // feature, and then — once armed — a button to the system settings that
        // actually grant it. A single "Enable" button jumping straight to
        // Android's accessibility screen would ask the user to grant the most
        // invasive permission in the app before telling them what it is for.
        val a11yContext = LocalContext.current
        var a11yServiceEnabled by remember { mutableStateOf(isA11yServiceEnabled(a11yContext)) }
        val a11yLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { a11yServiceEnabled = isA11yServiceEnabled(a11yContext) }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.screen_control), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (screenControlEnabled) {
                        "On - Aura can read and operate the screen in other apps when you ask"
                    } else {
                        "Off - Aura cannot see or touch other apps"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = screenControlEnabled, onCheckedChange = onSetScreenControlEnabled)
        }

        if (screenControlEnabled) {
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (a11yServiceEnabled) {
                            "Accessibility access granted"
                        } else {
                            "Android still needs to grant Accessibility access"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                    )
                }
                OutlinedButton(onClick = {
                    a11yLauncher.launch(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(if (a11yServiceEnabled) "Manage" else "Grant") }
            }
        }

        Spacer(modifier = Modifier.height(AuraSpacing.xs))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.app_lock), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (appLockEnabled) "Enabled - biometric required to open Aura"
                    else "Off - Aura opens straight to chat",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = appLockEnabled, onCheckedChange = onSetAppLock)
        }

        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.profile), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.name_traits_and_facts_aura_has),
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = onNavigateProfile) { Text(stringResource(R.string.edit)) }
        }

        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.morning_brief), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (morningBriefEnabled) "On - %02d:00 daily summary".format(morningBriefHour)
                    else "Off - no morning notification",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = morningBriefEnabled, onCheckedChange = onSetMorningBrief)
        }
        if (morningBriefEnabled) {
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.md),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AuraSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.brief_at), style = MaterialTheme.typography.bodySmall)
                var showBriefTimePicker by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showBriefTimePicker = true }) {
                    Text(stringResource(R.string.s_02d_00).format(morningBriefHour))
                }
                if (showBriefTimePicker) {
                    val tpState = rememberTimePickerState(initialHour = morningBriefHour, initialMinute = 0)
                    AlertDialog(
                        onDismissRequest = { showBriefTimePicker = false },
                        title = { Text(stringResource(R.string.morning_brief_time)) },
                        text = { TimePicker(state = tpState) },
                        confirmButton = {
                            TextButton(onClick = {
                                onSetMorningBriefHour(tpState.hour)
                                showBriefTimePicker = false
                            }) { Text(stringResource(R.string.set)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBriefTimePicker = false }) { Text(stringResource(R.string.cancel)) }
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.calendar_monitor), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (calendarMonitorEnabled) "On - checks every 15 min for events starting soon"
                    else "Off - no upcoming-event alerts",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = calendarMonitorEnabled, onCheckedChange = onSetCalendarMonitor)
        }
        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.memory_decay), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (decayEnabled) "On - fades memories over 14 days"
                    else "Off - preserves all memories at full importance",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = decayEnabled, onCheckedChange = onSetDecayEnabled)
        }
        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.smarter_memory), style = MaterialTheme.typography.bodyLarge)
                // The download size is in the label rather than a dialog. It is
                // the entire cost of the feature and the only surprising thing
                // about it, so it belongs where the switch is.
                Text(
                    text = if (smarterMemoryEnabled) {
                        "On - finds memories by meaning, not just matching words"
                    } else {
                        "Off - matches words only. On downloads 137 MB over wifi"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = smarterMemoryEnabled, onCheckedChange = onSetSmarterMemory)
        }
    }
}

/**
 * Whether Aura's accessibility service is enabled in system settings.
 *
 * Read from Settings.Secure rather than AccessibilityManager's enabled-service
 * list: the manager reports services that are enabled AND currently bound, so a
 * service the system has temporarily killed — which some OEMs do aggressively —
 * would read as "not granted" and prompt the user to re-grant something they
 * already granted.
 */
/**
 * Whether Android's usage-access grant is in place.
 *
 * `AppOpsManager` rather than a permission check: `PACKAGE_USAGE_STATS` is an
 * appop, so `checkSelfPermission` returns DENIED even when the user has granted
 * it on the system screen.
 */
private fun isUsageAccessGranted(context: android.content.Context): Boolean = runCatching {
    val ops = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val op = android.app.AppOpsManager.OPSTR_GET_USAGE_STATS
    val uid = android.os.Process.myUid()
    // unsafeCheckOpNoThrow is API 29; checkOpNoThrow is the pre-Q spelling of
    // the same question and minSdk here is 26.
    val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        ops.unsafeCheckOpNoThrow(op, uid, context.packageName)
    } else {
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(op, uid, context.packageName)
    }
    mode == android.app.AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

private fun isA11yServiceEnabled(context: android.content.Context): Boolean = runCatching {
    val expected = "${context.packageName}/com.aura.a11y.AuraAccessibilityService"
    android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
}.getOrDefault(false)
