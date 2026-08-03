package com.aura.ui.settings.sections
import com.aura.ui.theme.AuraThemeTokens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.settings.SettingsViewModel

@Composable
fun IntegrationsSection(
    viewModel: SettingsViewModel,
) {
    val googleConnected by viewModel.googleConnected.collectAsStateWithLifecycle()
    val microsoftConnected by viewModel.microsoftConnected.collectAsStateWithLifecycle()
    val googleClientId by viewModel.googleClientId.collectAsStateWithLifecycle()
    val microsoftClientId by viewModel.microsoftClientId.collectAsStateWithLifecycle()

    Text(
        "Integrations",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text(
        "Connect Google and Microsoft accounts to let Aura read/send email, manage calendar, and browse files via official APIs.",
        style = MaterialTheme.typography.bodySmall,
        color = AuraThemeTokens.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    // Google
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Google Workspace", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (googleConnected) "Connected — Gmail, Calendar, Drive" else "Not connected",
                style = MaterialTheme.typography.bodySmall,
                color = if (googleConnected) AuraThemeTokens.colors.actionPrimary else AuraThemeTokens.colors.textSecondary,
            )
        }
        if (googleConnected) {
            TextButton(onClick = { viewModel.disconnectGoogle() }) { Text("Disconnect") }
        } else {
            TextButton(onClick = { viewModel.connectGoogle() }) { Text("Connect") }
        }
    }

    if (!googleConnected) {
        OutlinedTextField(
            value = googleClientId,
            onValueChange = { viewModel.setGoogleClientId(it) },
            label = { Text("Google OAuth Client ID") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Create an OAuth client at console.cloud.google.com → APIs & Services → Credentials. Set the redirect URI to aura://oauth/google",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    // Microsoft
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Microsoft 365", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (microsoftConnected) "Connected — Outlook, Calendar, OneDrive" else "Not connected",
                style = MaterialTheme.typography.bodySmall,
                color = if (microsoftConnected) AuraThemeTokens.colors.actionPrimary else AuraThemeTokens.colors.textSecondary,
            )
        }
        if (microsoftConnected) {
            TextButton(onClick = { viewModel.disconnectMicrosoft() }) { Text("Disconnect") }
        } else {
            TextButton(onClick = { viewModel.connectMicrosoft() }) { Text("Connect") }
        }
    }

    if (!microsoftConnected) {
        OutlinedTextField(
            value = microsoftClientId,
            onValueChange = { viewModel.setMicrosoftClientId(it) },
            label = { Text("Microsoft App Client ID") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Register an app at portal.azure.com → App registrations. Set redirect URI to aura://oauth/microsoft",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
}