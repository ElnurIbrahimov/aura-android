package com.aura.proactive

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What tapping a proactive suggestion does.
 *
 * Two systems needed this and neither had it. `ProactiveFinding.actionRoute` was
 * a bare route string read only as a relevance weight and never navigated to.
 * `OpportunityEntity.suggestedActionJson` was written on all six opportunity
 * kinds and parsed by nothing, so "Approve" flipped a status column and
 * performed no action. One type for both means one dispatcher and one place a
 * route can be wrong.
 */
sealed interface ProactiveAction {

    /** Navigate to a declared in-app route. Must exist in `Route`/`TopLevelRoute`. */
    data class Navigate(val route: String) : ProactiveAction

    /** Open chat, optionally with a prefilled draft. */
    data class OpenChat(val draft: String = "") : ProactiveAction

    /**
     * Open the system calendar app.
     *
     * Not a route: there is no calendar screen in Aura. The old
     * `actionRoute = "calendar"` matched nothing and would have thrown the
     * moment anything tried to navigate to it.
     */
    data object OpenCalendarApp : ProactiveAction

    /** Nothing to do. A card that states a fact rather than proposing a move. */
    data object None : ProactiveAction
}

object ProactiveActions {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read the JSON shape `OpportunityEngine` already writes.
     *
     * Deliberately matches the existing producer exactly — `{"action":"navigate",
     * "target":"reminders"}` — so the opportunity side gains a reader without a
     * single producer change. Unparseable or unknown input is [ProactiveAction.None]
     * rather than a throw: a malformed suggestion should make a button inert,
     * not crash the screen showing it.
     */
    fun parse(raw: String): ProactiveAction {
        if (raw.isBlank()) return ProactiveAction.None
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            val action = obj["action"]?.jsonPrimitive?.content.orEmpty()
            val target = obj["target"]?.jsonPrimitive?.content.orEmpty()
            when (action) {
                "navigate" -> if (target.isBlank()) ProactiveAction.None else ProactiveAction.Navigate(target)
                "chat" -> ProactiveAction.OpenChat(target)
                "calendar" -> ProactiveAction.OpenCalendarApp
                else -> ProactiveAction.None
            }
        }.onFailure {
            Log.w("ProactiveActions", "could not parse suggested action: ${it.message}", it)
        }.getOrDefault(ProactiveAction.None)
    }

    /** The inverse of [parse]. Exists so the two directions cannot drift apart. */
    fun encode(action: ProactiveAction): String = when (action) {
        is ProactiveAction.Navigate -> """{"action":"navigate","target":"${action.route}"}"""
        is ProactiveAction.OpenChat -> """{"action":"chat","target":"${action.draft}"}"""
        ProactiveAction.OpenCalendarApp -> """{"action":"calendar","target":""}"""
        ProactiveAction.None -> ""
    }

    /** A short label for the affordance, or blank when there is nothing to offer. */
    fun label(action: ProactiveAction): String = when (action) {
        is ProactiveAction.Navigate -> when (action.route) {
            "tasks" -> "Open tasks"
            "memory" -> "Open memory"
            "knowledge_graph" -> "Open the graph"
            "reminders" -> "Open reminders"
            "dreams" -> "Open dreams"
            "mind" -> "Open what Aura thinks"
            "diagnostics" -> "Open diagnostics"
            else -> "Open"
        }
        is ProactiveAction.OpenChat -> "Talk about it"
        ProactiveAction.OpenCalendarApp -> "Open calendar"
        ProactiveAction.None -> ""
    }
}
