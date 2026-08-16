package com.aura.ui.screens
import com.aura.ui.components.AuraCard
import com.aura.R
import androidx.compose.ui.res.stringResource
import com.aura.ui.theme.AuraThemeTokens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import java.io.File

data class CrashEntry(
    val fileName: String,
    val timestamp: Long,
    val content: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val crashes = remember { mutableStateListOf<CrashEntry>() }
    var expandedFile by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val crashDir = File(context.cacheDir, "crash_logs")
        if (crashDir.exists()) {
            crashDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    crashes.add(
                        CrashEntry(
                            fileName = file.name,
                            timestamp = file.lastModified(),
                            content = file.readText().take(5000),
                        ),
                    )
                }
        }
    }

    AuraScreenShell(
        title = "Crash logs",
        subtitle = "Local crash and error history",
        action = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            if (crashes.isNotEmpty()) {
                TextButton(onClick = {
                    val crashDir = File(context.cacheDir, "crash_logs")
                    crashDir.listFiles { f -> f.isFile }?.forEach { it.delete() }
                    crashes.clear()
                }) { Text(stringResource(R.string.clear)) }
            }
        },
    ) { padding ->
        if (crashes.isEmpty()) {
            Text(
                "No crash logs. The app has been stable.",
                style = MaterialTheme.typography.bodyMedium,
                color = AuraThemeTokens.colors.textSecondary,
                modifier = Modifier.padding(padding).padding(AuraSpacing.md),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(AuraSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
            ) {
                items(crashes, key = { it.fileName }) { entry ->
                    AuraCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedFile = if (expandedFile == entry.fileName) null else entry.fileName
                            },
                    ) {
                        Column(modifier = Modifier.padding(AuraSpacing.sm)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    entry.fileName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = AuraThemeTokens.colors.actionPrimary,
                                )
                                Text(
                                    java.text.DateFormat.getDateTimeInstance()
                                        .format(java.util.Date(entry.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AuraThemeTokens.colors.textSecondary,
                                )
                            }
                            if (expandedFile == entry.fileName) {
                                Text(
                                    entry.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = AuraSpacing.xs),
                                )
                                TextButton(onClick = {
                                    val file = File(context.cacheDir, "crash_logs/${entry.fileName}")
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share crash log"))
                                }) { Text(stringResource(R.string.share)) }
                            }
                        }
                    }
                }
            }
        }
    }
}