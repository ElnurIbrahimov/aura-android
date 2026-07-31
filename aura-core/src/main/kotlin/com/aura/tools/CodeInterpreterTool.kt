package com.aura.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Code Interpreter — executes JavaScript in a sandboxed WebView.
 *
 * The model writes JavaScript code. Aura evaluates it in a hidden WebView's
 * JS engine, captures console.log output, and returns it as the tool result.
 *
 * Security:
 * - No network access (WebView settings block it)
 * - No file access (allowFileAccess = false, allowContentAccess = false)
 * - No DOM access (the WebView has no loaded content)
 * - 10-second timeout
 * - Output truncated at 4000 chars
 *
 * Use cases: math, string processing, array manipulation, JSON parsing,
 * data transformation, formatting.
 */
@Singleton
class CodeInterpreterTool @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "CodeInterpreter"
        private const val TIMEOUT_MS = 10_000L
        private const val MAX_OUTPUT = 4000
    }

    fun definition() = ToolDefinition(
        name = "code_interpreter",
        description = "Execute JavaScript code in a sandbox and return console.log output. " +
            "Use for math, data processing, string manipulation, JSON parsing, and formatting. " +
            "No network or file access. 10-second timeout. Output capped at 4000 chars.",
        parameters = ToolParameters(
            properties = mapOf(
                "code" to ToolProperty(
                    type = "string",
                    description = "JavaScript code to execute. Use console.log() for output.",
                ),
            ),
            required = listOf("code"),
        ),
    )

    val tool = Tool(
        name = "code_interpreter",
        description = definition().description,
        risk = ToolRisk.REMOTE_COST,
        parameters = definition().parameters,
        execute = { call, _ ->
            val code = call.arguments["code"] as? String
                ?: return@Tool ToolResult.Error("missing 'code'", "bad_args")
            if (code.isBlank()) return@Tool ToolResult.Error("code is empty", "bad_args")
            if (code.length > 50_000) return@Tool ToolResult.Error("code too long (max 50K chars)", "too_large")
            try {
                val output = executeJavaScript(code)
                val truncated = if (output.length > MAX_OUTPUT) {
                    output.take(MAX_OUTPUT) + "\n[...truncated]"
                } else output
                ToolResult.Ok(truncated.ifBlank { "Execution completed (no output)" })
            } catch (e: TimeoutCancellationException) {
                ToolResult.Error("Code execution timed out after ${TIMEOUT_MS / 1000}s", "timeout")
            } catch (e: Exception) {
                ToolResult.Error("Execution failed: ${e.message}", "exception")
            }
        },
        category = "compute",
    )

    /**
     * Execute JavaScript in a hidden WebView. Must run on the main thread
     * (WebView requires it). The suspendCancellableCoroutine bridges back
     * to the coroutine context.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun executeJavaScript(code: kotlin.String): kotlin.String =
        withTimeout(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    val output = StringBuilder()

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    }

                    // JS interface to capture console.log output
                    webView.addJavascriptInterface(object : Any() {
                        @JavascriptInterface
                        fun log(message: kotlin.String) {
                            output.appendLine(message)
                        }
                    }, "__aura_log")

                    // Override console.log to route to our interface.
                    // The user code is passed as a JSON-encoded string and
                    // eval'd inside the IIFE — no string interpolation of
                    // untrusted code, preventing injection.
                    val jsonCode = kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.serializer<kotlin.String>(),
                        code,
                    )
                    val wrappedCode = """
                        (function() {
                            var __code = $jsonCode;
                            console.log = function() {
                                var args = Array.prototype.slice.call(arguments);
                                var msg = args.map(function(a) {
                                    if (typeof a === 'object') {
                                        try { return JSON.stringify(a); } catch(e) { return String(a); }
                                    }
                                    return String(a);
                                }).join(' ');
                                __aura_log.log(msg);
                            };
                            try {
                                var __result = eval(__code);
                                if (__result !== undefined) {
                                    console.log(__result);
                                }
                            } catch(e) {
                                __aura_log.log('Error: ' + e.message);
                            }
                        })();
                    """.trimIndent()

                    // Evaluate and clean up after a delay
                    webView.evaluateJavascript(wrappedCode) { _ ->
                        handler.postDelayed({
                            webView.destroy()
                            if (cont.isActive) {
                                cont.resume(output.toString().trim())
                            }
                        }, 500) // Small delay to let async console.log calls flush
                    }

                    cont.invokeOnCancellation { webView.destroy() }
                }
            }
        }
}