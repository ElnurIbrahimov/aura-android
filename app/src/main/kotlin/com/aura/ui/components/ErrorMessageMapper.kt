package com.aura.ui.components

/**
 * Maps raw provider error strings to user-friendly messages.
 * Pure function — no dependencies, testable.
 */
fun friendlyErrorMessage(raw: String): String = when {
    raw.contains("429", ignoreCase = true) ->
        "Rate limited. Try again in a minute."
    raw.contains("401", ignoreCase = true) ->
        "Your API key is invalid. Check Settings → AI & Models."
    raw.contains("403", ignoreCase = true) ->
        "Access denied. Your API key may be expired."
    raw.contains("500", ignoreCase = true) ||
    raw.contains("502", ignoreCase = true) ||
    raw.contains("503", ignoreCase = true) ->
        "The AI provider is having issues. Try again."
    raw.contains("missing_api_key", ignoreCase = true) ->
        "No API key configured. Go to Settings → AI & Models."
    raw.contains("empty_response", ignoreCase = true) ->
        "The model returned an empty response. Try again or switch models."
    raw.contains("not_configured", ignoreCase = true) ->
        "This provider isn't set up yet. Go to Settings."
    raw.contains("tool_timeout", ignoreCase = true) ->
        "A tool took too long to respond. Try again."
    raw.contains("timeout", ignoreCase = true) ->
        "The request timed out. Try again."
    raw.contains("network", ignoreCase = true) ||
    raw.contains("connection", ignoreCase = true) ->
        "Network error. Check your internet connection."
    else -> raw
}