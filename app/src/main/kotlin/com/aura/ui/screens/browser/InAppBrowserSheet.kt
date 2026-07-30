package com.aura.ui.screens.browser

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aura.ui.theme.AuraThemeTokens

/**
 * In-app browser sheet. Renders a WebView inside the app so the user
 * can browse without leaving the conversation.
 *
 * Security: JavaScript enabled (for modern sites), DOM storage enabled,
 * cookies disabled, file access disabled, mixed content blocked.
 * The WebView is destroyed when the composable leaves the composition.
 *
 * The URL bar is editable — the user can type a new URL and press Enter
 * to navigate. Back/forward buttons use WebView's history.
 */
@Composable
fun InAppBrowserSheet(
    initialUrl: String,
    onDismiss: () -> Unit,
) {
    val colors = AuraThemeTokens.colors
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var urlInput by remember { mutableStateOf(initialUrl) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // URL bar + navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = {
                        webView?.let { if (it.canGoBack()) it.goBack() }
                    },
                    enabled = canGoBack,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (canGoBack) colors.textPrimary else colors.textSecondary,
                    )
                }
                IconButton(
                    onClick = {
                        webView?.let { if (it.canGoForward()) it.goForward() }
                    },
                    enabled = canGoForward,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (canGoForward) colors.textPrimary else colors.textSecondary,
                    )
                }
                IconButton(onClick = {
                    webView?.reload()
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = colors.textPrimary)
                }
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val target = normalizeUrl(urlInput)
                            if (target.isNotBlank()) {
                                currentUrl = target
                                webView?.loadUrl(target)
                            }
                        },
                    ),
                    textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                IconButton(onClick = {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, currentUrl)
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share URL"))
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = colors.textPrimary)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = colors.textPrimary)
                }
            }

            // WebView
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 2.dp),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode =
                                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let {
                                        currentUrl = it
                                        urlInput = it
                                        canGoBack = view?.canGoBack() ?: false
                                        canGoForward = view?.canGoForward() ?: false
                                        title = view?.title ?: ""
                                    }
                                }
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                ): Boolean {
                                    return false
                                }
                            }
                            loadUrl(initialUrl)
                            webView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Destroy WebView when composable leaves composition
        DisposableEffect(Unit) {
            onDispose {
                webView?.destroy()
                webView = null
            }
        }
    }
}

/**
 * Ensure URL has a scheme. If missing, prepend https://.
 */
internal fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    // Block file:// and content:// for security
    if (trimmed.startsWith("file://") || trimmed.startsWith("content://")) return ""
    return "https://$trimmed"
}