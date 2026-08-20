package com.aura.hands

/**
 * Extension to build a JSON argument string from a plain string map.
 * Only string values are supported by this simplified mapper; values
 * that look numeric/boolean are still emitted as JSON strings to
 * keep the hand editor simple.
 */
fun Map<String, String>.toJsonString(): String {
    if (isEmpty()) return "{}"
    val entries = entries.joinToString(",") { (k, v) ->
        val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
        "\"$k\":\"$escaped\""
    }
    return "{$entries}"
}

/**
 * True when any step drives the screen of another app.
 *
 * `screen_act` needs a [com.aura.a11y.ScreenControlSession], and a session is opened only by
 * a confirmation the user answers. A scheduled hand containing one therefore stops at that
 * step with NEEDS_APPROVAL and does nothing — at 09:00, with nobody there to read it, every
 * day, for as long as the schedule exists. Nothing is unsafe about it; the run loop stops
 * rather than skipping ahead. It simply cannot work, and a schedule that cannot work should
 * say so when it is set rather than fail quietly forever.
 *
 * Reads only the `tool` field, which is identical in both the current and the legacy step
 * shapes `HandRepository.decodeSteps` accepts. Unparseable steps answer false: such a hand
 * fails loudly at its first run with an invalid-configuration record, which is more visible
 * than being silently unscheduled.
 */
fun stepsDriveScreen(stepsJson: String): Boolean = runCatching {
    val array = kotlinx.serialization.json.Json.parseToJsonElement(stepsJson)
        as? kotlinx.serialization.json.JsonArray ?: return@runCatching false
    array.any { element ->
        val tool = (element as? kotlinx.serialization.json.JsonObject)?.get("tool")
        (tool as? kotlinx.serialization.json.JsonPrimitive)?.content == SCREEN_ACT_TOOL
    }
}.getOrDefault(false)

/** The one tool that cannot run without a person present. */
private const val SCREEN_ACT_TOOL = "screen_act"
