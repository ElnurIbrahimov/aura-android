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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aura.mcp.McpServerConfig
import com.aura.mcp.McpToolInfo
import com.aura.ui.settings.McpServerDraft
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing

@Composable
fun McpServersSection(
    mcpServers: List<McpServerConfig>,
    mcpDiscoveredTools: Map<String, List<McpToolInfo>>,
    onTestConnection: (McpServerDraft) -> Unit,
    onDisconnect: (kotlin.String) -> Unit,
) {
    var showMcpAddDialog by remember { mutableStateOf(false) }
    var mcpDraft by remember { mutableStateOf(McpServerDraft()) }

    SettingsSection(
        emoji = "\uD83D\uDD17",
        title = "MCP Servers",
        subtitle = "External Model Context Protocol tool servers",
        initialExpanded = false,
    ) {
        Text(
            text = stringResource(R.string.connect_to_local_or_remote_mcp),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        if (mcpServers.isEmpty()) {
            Text(
                text = stringResource(R.string.no_mcp_servers_configured),
                style = MaterialTheme.typography.bodyMedium,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = AuraSpacing.xs),
            )
        }
        for (server in mcpServers) {
            val tools = mcpDiscoveredTools[server.id] ?: emptyList()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = AuraSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = server.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "${server.url} - ${tools.size} tools",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                    )
                }
                TextButton(onClick = { onDisconnect(server.id) }) { Text(stringResource(R.string.disconnect)) }
            }
        }
        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        OutlinedButton(onClick = { showMcpAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.add_mcp_server))
        }

        if (showMcpAddDialog) {
            AlertDialog(
                onDismissRequest = { showMcpAddDialog = false },
                title = { Text(stringResource(R.string.add_mcp_server)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = mcpDraft.name,
                            onValueChange = { mcpDraft = mcpDraft.copy(name = it) },
                            label = { Text(stringResource(R.string.name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(AuraSpacing.xs))
                        OutlinedTextField(
                            value = mcpDraft.url,
                            onValueChange = { mcpDraft = mcpDraft.copy(url = it) },
                            label = { Text(stringResource(R.string.url_https_or_trusted_local_http)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(AuraSpacing.xs))
                        OutlinedTextField(
                            value = mcpDraft.authToken,
                            onValueChange = { mcpDraft = mcpDraft.copy(authToken = it) },
                            label = { Text(stringResource(R.string.auth_token_optional_sent_as_bearer)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Spacer(modifier = Modifier.height(AuraSpacing.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = mcpDraft.trustedLocal,
                                onCheckedChange = { mcpDraft = mcpDraft.copy(trustedLocal = it) },
                            )
                            Text(stringResource(R.string.trusted_local_allows_http))
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        onTestConnection(mcpDraft)
                        mcpDraft = McpServerDraft()
                        showMcpAddDialog = false
                    }) { Text(stringResource(R.string.connect)) }
                },
                dismissButton = {
                    TextButton(onClick = { showMcpAddDialog = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}