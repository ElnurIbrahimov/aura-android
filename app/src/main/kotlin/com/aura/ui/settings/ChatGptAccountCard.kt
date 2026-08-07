package com.aura.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * Sign-in card for the ChatGPT subscription provider.
 *
 * Replaces a single-line "API key" row, which was the wrong shape twice over.
 * There is no ChatGPT API key to paste — the credential is an OAuth grant —
 * and a one-field row could only capture the access token, leaving the refresh
 * token behind in the same file. Access tokens last about an hour, so every
 * sign-in quietly stopped working by lunchtime.
 *
 * So this asks for the whole of `~/.codex/auth.json`, and once signed in shows
 * the account rather than the token: nobody needs to look at a JWT, and a
 * credential on screen is a credential in a screenshot.
 */
@Composable
fun ChatGptAccountCard(
    connected: Boolean,
    account: String?,
    sessionExpired: Boolean,
    paste: String,
    error: String?,
    onPasteChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = AuraThemeTokens.colors.surface1.copy(alpha = 0.4f),
        shape = RoundedCornerShape(AuraSpacing.large),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AuraSpacing.small)
            .testTag("chatgpt-account-card"),
    ) {
        Column(modifier = Modifier.padding(AuraSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ChatGPT Subscription",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (connected) {
                    Text(
                        text = if (sessionExpired) "Session expired" else "Signed in",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sessionExpired) {
                            AuraThemeTokens.colors.error
                        } else {
                            AuraThemeTokens.colors.actionPrimary
                        },
                    )
                }
            }
            Spacer(Modifier.height(AuraSpacing.tiny))

            if (connected) {
                Text(
                    text = account ?: "Signed in",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraThemeTokens.colors.textPrimary,
                )
                if (sessionExpired) {
                    Spacer(Modifier.height(AuraSpacing.tiny))
                    Text(
                        // The one case Aura genuinely cannot recover from on
                        // its own: no refresh token was ever captured, so
                        // there is nothing to renew with.
                        text = "This sign-in can't be renewed — it was saved without a refresh token. " +
                            "Run `codex login` again and paste the whole auth.json below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textSecondary,
                    )
                }
            } else {
                Text(
                    text = "Uses your ChatGPT Plus/Pro plan instead of an API key. " +
                        "Run `codex login` on a computer, then paste the contents of ~/.codex/auth.json.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }

            if (!connected || sessionExpired) {
                Spacer(Modifier.height(AuraSpacing.xs))
                OutlinedTextField(
                    value = paste,
                    onValueChange = onPasteChange,
                    label = { Text("auth.json") },
                    placeholder = { Text("{\"tokens\":{\"access_token\":\"…\",\"refresh_token\":\"…\"}}") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chatgpt-auth-paste"),
                    // Deliberately not a password field. It is JSON, people
                    // need to see whether the paste actually landed, and
                    // masking it hid truncated pastes.
                    minLines = 2,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall,
                    isError = error != null,
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraThemeTokens.colors.error,
                        modifier = Modifier.padding(top = AuraSpacing.tiny),
                    )
                }
            }

            Spacer(Modifier.height(AuraSpacing.xs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
            ) {
                if (!connected || sessionExpired) {
                    TextButton(
                        onClick = onSignIn,
                        enabled = paste.isNotBlank(),
                        modifier = Modifier.testTag("chatgpt-sign-in"),
                    ) {
                        Text(if (connected) "Update sign-in" else "Sign in")
                    }
                }
                if (connected) {
                    TextButton(onClick = onDisconnect, modifier = Modifier.testTag("chatgpt-disconnect")) {
                        Text("Disconnect", color = AuraThemeTokens.colors.error)
                    }
                }
            }
        }
    }
}
