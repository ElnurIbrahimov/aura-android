package com.aura.ui.settings.sections

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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PrivacySection(
    appLockEnabled: Boolean,
    morningBriefEnabled: Boolean,
    morningBriefHour: Int,
    calendarMonitorEnabled: Boolean,
    onSetAppLock: (Boolean) -> Unit,
    onSetMorningBrief: (Boolean) -> Unit,
    onSetMorningBriefHour: (Int) -> Unit,
    onSetCalendarMonitor: (Boolean) -> Unit,
    onNavigateProfile: () -> Unit,
) {
    SettingsSection(
        emoji = "\uD83D\uDD12",
        title = "Privacy",
        subtitle = "Biometric lock, proactive worker toggles",
        initialExpanded = false,
    ) {
        Text(
            text = "Require biometric authentication to open Aura. Toggle the proactive workers off if you don't want the 7am brief or the calendar monitor.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(8.dp))

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
                Text(text = "Notification access", style = MaterialTheme.typography.bodyLarge)
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

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "App lock", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (appLockEnabled) "Enabled - biometric required to open Aura"
                    else "Off - Aura opens straight to chat",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = appLockEnabled, onCheckedChange = onSetAppLock)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Profile", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Name, traits, and facts Aura has learned",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = onNavigateProfile) { Text("Edit") }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Morning brief", style = MaterialTheme.typography.bodyLarge)
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
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Brief at:", style = MaterialTheme.typography.bodySmall)
                var showBriefTimePicker by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showBriefTimePicker = true }) {
                    Text("%02d:00".format(morningBriefHour))
                }
                if (showBriefTimePicker) {
                    val tpState = rememberTimePickerState(initialHour = morningBriefHour, initialMinute = 0)
                    AlertDialog(
                        onDismissRequest = { showBriefTimePicker = false },
                        title = { Text("Morning brief time") },
                        text = { TimePicker(state = tpState) },
                        confirmButton = {
                            TextButton(onClick = {
                                onSetMorningBriefHour(tpState.hour)
                                showBriefTimePicker = false
                            }) { Text("Set") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBriefTimePicker = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Calendar monitor", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (calendarMonitorEnabled) "On - runs in background, shows notification"
                    else "Off - stops the persistent foreground service",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Switch(checked = calendarMonitorEnabled, onCheckedChange = onSetCalendarMonitor)
        }
    }
}