package com.aura.ui.screens.canvas

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.ui.components.MarkdownColumn
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing

/**
 * Canvas content type. The model emits fenced code blocks with
 * these languages to trigger the canvas.
 */
enum class CanvasType(val language: String, val label: String) {
    MARKDOWN("canvas-markdown", "Document"),
    CODE("canvas-code", "Code"),
    HTML("canvas-html", "HTML Preview"),
    DATA("canvas-data", "Data");

    companion object {
        fun fromLanguage(lang: String): CanvasType? =
            entries.firstOrNull { it.language == lang }
    }
}

data class CanvasContent(
    val type: CanvasType,
    val title: String,
    val content: String,
)

/**
 * Canvas bottom sheet — a side panel for rich content editing.
 *
 * When the model produces a fenced block with language `canvas-markdown`,
 * `canvas-code`, `canvas-html`, or `canvas-data`, the chat UI opens this
 * sheet so the user can view, edit, copy, share, or save the content.
 *
 * The sheet takes 70% of screen height as a ModalBottomSheet.
 * Content is editable in a BasicTextField (markdown/code) or rendered
 * read-only (html/data preview).
 */
@Composable
fun CanvasSheet(
    canvas: CanvasContent,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AuraThemeTokens.colors
    var editedContent by remember(canvas) { mutableStateOf(canvas.content) }
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f),
        color = colors.surface0,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = canvas.title.ifBlank { canvas.type.label },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(AuraSpacing.xs))
                Text(
                    text = canvas.type.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
                IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(editedContent)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = colors.textPrimary)
                }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                IconButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, editedContent)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(intent, "Share"))
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = colors.textPrimary)
                }
                IconButton(onClick = { onSave(editedContent) }) {
                    Icon(Icons.Filled.Save, contentDescription = "Save to memory", tint = colors.textPrimary)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = colors.textPrimary)
                }
            }

            // Content area
            when (canvas.type) {
                CanvasType.MARKDOWN -> {
                    // Editable markdown with live preview
                    OutlinedTextField(
                        value = editedContent,
                        onValueChange = { editedContent = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = AuraSpacing.md)
                            .verticalScroll(scrollState),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = colors.textPrimary,
                        ),
                        placeholder = { Text(stringResource(R.string.edit_markdown), color = colors.textSecondary) },
                    )
                    // Preview
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs),
                        color = colors.surface1,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier
                            .padding(AuraSpacing.sm)
                            .verticalScroll(rememberScrollState())) {
                            MarkdownColumn(text = editedContent)
                        }
                    }
                }
                CanvasType.CODE -> {
                    OutlinedTextField(
                        value = editedContent,
                        onValueChange = { editedContent = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = AuraSpacing.md)
                            .verticalScroll(scrollState),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = colors.textPrimary,
                        ),
                        placeholder = { Text(stringResource(R.string.edit_code), color = colors.textSecondary) },
                    )
                }
                CanvasType.HTML, CanvasType.DATA -> {
                    // Read-only preview for now (Phase 3+ will add chart/WebView rendering)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs),
                        color = colors.surface1,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier
                            .padding(AuraSpacing.sm)
                            .verticalScroll(rememberScrollState())) {
                            Text(
                                text = editedContent,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}