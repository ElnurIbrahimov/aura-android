package com.aura.ui.util

/**
 * An agent's name, as a person should see it.
 *
 * `AgentEntity.name` is an identifier as much as a label: `delegate_to_agent`
 * looks agents up by it, and its "Available agents: ..." error lists the same
 * strings back, so the stored value has to stay `phone_native`. Prettying it
 * belongs here rather than in the database.
 *
 * Custom agents are named by the user, who capitalises their own way — so a
 * name that already has capitals or spaces is left exactly as typed. Only the
 * snake_case builtins get rewritten.
 */
fun agentDisplayName(name: String): String {
    if (name.isBlank()) return name
    if (name.any { it.isUpperCase() || it.isWhitespace() }) return name
    return name.split('_', '-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}
