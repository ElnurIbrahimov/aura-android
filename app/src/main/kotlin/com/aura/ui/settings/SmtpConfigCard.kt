package com.aura.ui.settings

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aura.ui.theme.AuraThemeTokens

@Composable
fun SmtpConfigCard(
    host: String,
    port: Int,
    username: String,
    password: kotlin.String,
    from: String,
    testing: Boolean,
    result: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (kotlin.String) -> Unit,
    onFromChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    SettingsSection(
        emoji = "\u2709",
        title = "Background email (SMTP)",
        subtitle = "Configure SMTP to enable send_email_background tool.",
        initialExpanded = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text(stringResource(R.string.smtp_host)) },
                placeholder = { Text("smtp.gmail.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = port.toString(),
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.port)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.username)) },
                placeholder = { Text(stringResource(R.string.you_gmail_com)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.password_app_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = from,
                onValueChange = onFromChange,
                label = { Text(stringResource(R.string.from_address)) },
                placeholder = { Text(stringResource(R.string.defaults_to_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = onSave,
                enabled = !testing && host.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (testing) "Saving..." else "Save SMTP config")
            }
            result?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("\u2713")) AuraThemeTokens.colors.success else AuraThemeTokens.colors.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}