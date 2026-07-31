package com.aura.tools

import android.content.Context
import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.core.url.SsrfGuard
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open a URL in an in-app browser. Returns a [BROWSER:url] marker that
 * the chat UI detects and opens as an InAppBrowserSheet — the user
 * stays in the app.
 *
 * The [context] parameter is retained for Hilt injection but is not
 * used directly — the tool no longer launches a Chrome Custom Tab.
 *
 * Risk: WRITE_LOCAL (opens a browser surface).
 */
@Singleton
class OpenBrowserTabTool @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun definition() = ToolDefinition(
        name = "open_browser_tab",
        description = "Open a URL in an in-app browser tab.",
        parameters = ToolParameters(
            properties = mapOf(
                "url" to ToolProperty(type = "string", description = "URL to open"),
            ),
            required = listOf("url"),
        ),
    )

    val tool = Tool(
        name = "open_browser_tab",
        description = definition().description,
        risk = ToolRisk.WRITE_LOCAL,
        parameters = definition().parameters,
        execute = { call, _ ->
            val url = call.arguments["url"] as? String
                ?: return@Tool ToolResult.Error("missing 'url'", "bad_args")
            val ssrfError = SsrfGuard.validate(url)
            if (ssrfError != null) {
                return@Tool ToolResult.Error(ssrfError, "ssrf_guard")
            }
            // Return a structured marker so the chat UI can open an in-app
            // WebView instead of leaving the app. The ChatSendController
            // detects [BROWSER:url] and opens InAppBrowserSheet.
            ToolResult.Ok("[BROWSER:$url]")
        },
        category = "web",
    )
}
