package com.aura.creative

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart Codex injection. Instead of dumping the entire world bible into
 * every generation, this analyzes the user's prompt/scene and injects
 * ONLY the relevant world bible entries.
 *
 * Detection: scans the prompt for character names, location names,
 * faction names, and rule keywords. Returns a filtered world bible
 * containing only the entries that are mentioned or implied.
 *
 * This reduces context waste dramatically. A scene set in Location A
 * with Characters X and Y doesn't need Locations B-Z or the other 20
 * characters. Smart injection = less context = better model output
 * because the model focuses on what matters.
 */
@Singleton
class SmartCodexInjector @Inject constructor() {

    /**
     * Filter the world bible to only entries relevant to the given prompt.
     *
     * @param world The full world bible.
     * @param prompt The user's prompt/scene description.
     * @return A filtered world bible containing only relevant entries.
     */
    fun filterRelevant(world: WorldBible, prompt: String): WorldBible {
        val promptLower = prompt.lowercase()

        // Always include the overview — it's the foundation
        // Characters: include if their name appears in the prompt
        val relevantCharacters = world.characters.filter { c ->
            promptLower.contains(c.name.lowercase()) ||
            c.traits.any { promptLower.contains(it.lowercase()) } ||
            c.role.lowercase().let { role -> promptLower.contains(role) && role.length > 3 } ||
            // Include characters whose backstory mentions keywords from the prompt
            promptLower.split(Regex("\\s+")).any { word ->
                word.length > 4 && c.backstory.lowercase().contains(word)
            }
        }

        // Locations: include if their name appears in the prompt
        val relevantLocations = world.locations.filter { l ->
            promptLower.contains(l.name.lowercase()) ||
            l.type.lowercase().let { type -> promptLower.contains(type) && type.length > 4 } ||
            promptLower.split(Regex("\\s+")).any { word ->
                word.length > 4 && l.description.lowercase().contains(word)
            }
        }

        // Factions: include if their name appears or any member is in the relevant characters
        val relevantCharNames = relevantCharacters.map { it.name.lowercase() }.toSet()
        val relevantFactions = world.factions.filter { f ->
            promptLower.contains(f.name.lowercase()) ||
            f.members.any { member -> relevantCharNames.contains(member.lowercase()) } ||
            f.rivals.any { rival -> relevantCharNames.contains(rival.lowercase()) }
        }

        // Rules: include if the rule's category or keywords appear in the prompt
        val relevantRules = world.rules.filter { r ->
            promptLower.contains(r.name.lowercase()) ||
            promptLower.contains(r.category.lowercase()) ||
            promptLower.split(Regex("\\s+")).any { word ->
                word.length > 4 && r.description.lowercase().contains(word)
            }
        }

        // Timeline: include if event titles or dates are mentioned
        val relevantTimeline = world.timeline.filter { e ->
            promptLower.contains(e.title.lowercase()) ||
            (e.date.isNotBlank() && promptLower.contains(e.date.lowercase()))
        }

        // Outline: always include all — it's the story structure
        // and the model needs to know where this scene fits
        // (but only the first 20 beats to stay within context budget)
        val relevantOutline = world.outline.take(20)

        // Simulations: include if the prompt references them
        val relevantSimulations = world.simulations.filter { s ->
            promptLower.contains(s.premise.lowercase().take(50))
        }.take(2)

        return world.copy(
            characters = relevantCharacters,
            locations = relevantLocations,
            factions = relevantFactions,
            rules = relevantRules,
            timeline = relevantTimeline,
            outline = relevantOutline,
            simulations = relevantSimulations,
            // Keep continuity notes and notes — they're small and important
            continuityNotes = world.continuityNotes,
            notes = world.notes,
        )
    }

    /**
     * Check if the filtered bible has enough content to be useful.
     * If the prompt didn't match anything, the caller should fall
     * back to the full bible.
     */
    fun hasContent(filtered: WorldBible): Boolean =
        filtered.characters.isNotEmpty() ||
        filtered.locations.isNotEmpty() ||
        filtered.factions.isNotEmpty() ||
        filtered.rules.isNotEmpty()
}