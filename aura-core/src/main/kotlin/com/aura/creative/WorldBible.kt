package com.aura.creative

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class WorldBible(
    val overview: String = "",
    val characters: List<WorldCharacter> = emptyList(),
    val locations: List<WorldLocation> = emptyList(),
    val factions: List<WorldFaction> = emptyList(),
    val rules: List<WorldRule> = emptyList(),
    val timeline: List<WorldEvent> = emptyList(),
    val outline: List<StoryBeat> = emptyList(),
    val simulations: List<SimulationRecord> = emptyList(),
    val continuityNotes: List<ContinuityIssue> = emptyList(),
    val notes: String = "",
)

@Serializable
data class WorldCharacter(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: String = "",
    val traits: List<String> = emptyList(),
    val backstory: String = "",
    val motivation: String = "",
    val arc: String = "",
)

@Serializable
data class WorldLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val type: String = "",
    val significance: String = "",
)

@Serializable
data class WorldFaction(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ideology: String = "",
    val members: List<String> = emptyList(),
    val rivals: List<String> = emptyList(),
)

@Serializable
data class WorldRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val category: String = "world",
    val impact: String = "",
)

@Serializable
data class WorldEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String = "",
    val description: String = "",
    val participants: List<String> = emptyList(),
)

/**
 * One planned beat of the story — and, once long-form drafting runs, one unit of
 * work.
 *
 * The extra fields are all defaulted on purpose. `WorldBible` is serialised into
 * `CreativeProjectEntity.worldJson`, decoded with `ignoreUnknownKeys = true`,
 * and carried through backup opaquely as a string, so extending it is a
 * serialisation change rather than a Room migration. Existing projects decode
 * with these empty, and a project whose owner hand-added beats in the World tab
 * can be drafted without touching anything.
 *
 * [status] was already free-text defaulting to "planned", so the drafting
 * lifecycle — planned → drafting → drafted → revised — needs no type change.
 */
@Serializable
data class StoryBeat(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val summary: String = "",
    val status: String = "planned",
    /** Whose eyes this beat is seen through. Blank when unspecified. */
    val pov: String = "",
    /** Where it happens; matched against the world bible's locations. */
    val setting: String = "",
    /** Rough length to aim for. 0 means no target was given. */
    val targetWords: Int = 0,
    /** The scene artifact produced for this beat, once one exists. */
    val artifactId: String = "",
    /** The revision of that artifact holding this beat's text. */
    val revisionId: String = "",
    /**
     * Two sentences on what this scene changed, written once by [SceneLedger]
     * when the scene was fresh and never re-summarised afterwards.
     *
     * Lives here rather than in a table because it is a fact about this beat and
     * travels with it — through `worldJson`, through backup, through a branch
     * fork — with no migration, no mapper and no doc count to keep in step.
     *
     * Blank means "not extracted yet", which is a state the back-fill in
     * [com.aura.creative.longform.LongformRunner] exists to clear. It is not the
     * same as "this scene changed nothing".
     */
    val synopsis: String = "",
)

@Serializable
data class SimulationRecord(
    val id: String = UUID.randomUUID().toString(),
    val premise: String,
    val outcome: String,
    val perspective: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val canonized: Boolean = false,
)

@Serializable
data class ContinuityIssue(
    val id: String = UUID.randomUUID().toString(),
    val severity: String = "warning",
    val category: String = "continuity",
    val description: String,
    val affectedEntity: String = "",
)

@Serializable
data class WritingTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val prompt: String,
)

object WritingTemplates {
    val all = listOf(
        WritingTemplate(
            id = "novel",
            name = "Novel",
            description = "Long-form fiction with acts, character arcs, and scene continuity",
            icon = "📖",
            prompt = "Develop this as a novel. Track character arcs, setup/payoff, pacing, and scene-level continuity.",
        ),
        WritingTemplate(
            id = "short-story",
            name = "Short story",
            description = "A focused narrative built around one transformation",
            icon = "✒️",
            prompt = "Shape this as a short story with a sharp premise, economical scenes, and a resonant ending.",
        ),
        WritingTemplate(
            id = "screenplay",
            name = "Screenplay",
            description = "Visual scenes, dialogue, action lines, and dramatic beats",
            icon = "🎬",
            prompt = "Write in screenplay-minded scenes: visual action, playable dialogue, subtext, and clean dramatic turns.",
        ),
        WritingTemplate(
            id = "rpg-world",
            name = "RPG world",
            description = "Lore, factions, locations, conflicts, quests, and simulation-ready rules",
            icon = "🗺️",
            prompt = "Build this as a simulation-ready RPG world with factions, locations, rules, conflicts, and consequence chains.",
        ),
        WritingTemplate(
            id = "character-study",
            name = "Character study",
            description = "Voice, psychology, relationships, contradictions, and internal change",
            icon = "🎭",
            prompt = "Center this work on psychology, voice, contradictory motives, relationships, and earned internal change.",
        ),
    )

    fun byId(id: String): WritingTemplate? = all.find { it.id == id }
}