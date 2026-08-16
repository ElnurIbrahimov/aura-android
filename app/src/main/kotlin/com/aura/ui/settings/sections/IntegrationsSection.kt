package com.aura.ui.settings.sections
import com.aura.R
import androidx.compose.ui.res.stringResource
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
import com.aura.ui.theme.AuraSpacing

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
        modifier = Modifier.padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs),
    )
    Text(
        "Connect Google and Microsoft accounts to let Aura read/send email, manage calendar, and browse files via official APIs.",
        style = MaterialTheme.typography.bodySmall,
        color = AuraThemeTokens.colors.textSecondary,
        modifier = Modifier.padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xxs),
    )

    // Google
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.google_workspace), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (googleConnected) "Connected — Gmail, Calendar, Drive" else "Not connected",
                style = MaterialTheme.typography.bodySmall,
                color = if (googleConnected) AuraThemeTokens.colors.actionPrimary else AuraThemeTokens.colors.textSecondary,
            )
        }
        if (googleConnected) {
            TextButton(onClick = { viewModel.disconnectGoogle() }) { Text(stringResource(R.string.disconnect)) }
        } else {
            TextButton(onClick = { viewModel.connectGoogle() }) { Text(stringResource(R.string.connect)) }
        }
    }

    if (!googleConnected) {
        OutlinedTextField(
            value = googleClientId,
            onValueChange = { viewModel.setGoogleClientId(it) },
            label = { Text(stringResource(R.string.google_oauth_client_id)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xxs),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Create an OAuth client at console.cloud.google.com → APIs & Services → Credentials. Set the redirect URI to aura://oauth/google",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
            modifier = Modifier.padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.tiny),
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = AuraSpacing.xxs))

    // Microsoft
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.microsoft_365), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (microsoftConnected) "Connected — Outlook, Calendar, OneDrive" else "Not connected",
                style = MaterialTheme.typography.bodySmall,
                color = if (microsoftConnected) AuraThemeTokens.colors.actionPrimary else AuraThemeTokens.colors.textSecondary,
            )
        }
        if (microsoftConnected) {
            TextButton(onClick = { viewModel.disconnectMicrosoft() }) { Text(stringResource(R.string.disconnect)) }
        } else {
            TextButton(onClick = { viewModel.connectMicrosoft() }) { Text(stringResource(R.string.connect)) }
        }
    }

    if (!microsoftConnected) {
        OutlinedTextField(
            value = microsoftClientId,
            onValueChange = { viewModel.setMicrosoftClientId(it) },
            label = { Text(stringResource(R.string.microsoft_app_client_id)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xxs),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Register an app at portal.azure.com → App registrations. Set redirect URI to aura://oauth/microsoft",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
            modifier = Modifier.padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.tiny),
        )
    }

    Spacer(Modifier.height(AuraSpacing.md))
}