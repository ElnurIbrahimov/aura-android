package com.aura.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A user-authored Aura skill.
 *
 * Skills are lightweight instruction modules the agent can invoke on demand
 * (parallels: Claude Code skills, Hermes skills, MCP prompts). A skill has:
 *   - a [name] used as the unique id (slug-friendly)
 *   - a one-line [description] surfaced in tool listings
 *   - a [body] of free-form Markdown that becomes part of the agent context
 *     when the skill is invoked.
 *
 * Skills live in DataStore (one JSON-encoded list per install). They are
 * intentionally small: 1 skill == 1 markdown file's worth of content.
 */
@Serializable
data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val body: String,
    /**
     * Shipped with the app rather than written by the user.
     *
     * A builtin is seeded on first run, is editable, and can be reset to what
     * shipped — but it cannot be deleted. The craft guidance the creative
     * engine depends on lives here, and a user who removed one would silently
     * degrade every draft afterwards with nothing saying why.
     *
     * Defaulted, so an install written before this field existed decodes as
     * user-authored — which is what those skills are.
     */
    val builtin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    init {
        require(name.isNotBlank()) { "Skill name cannot be blank" }
        require(name.length <= 80) { "Skill name cannot exceed 80 characters" }
        require(description.length <= 240) { "Skill description cannot exceed 240 characters" }
    }

    fun preview(): String = body.lineSequence()
        .map(String::trim)
        .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
        ?.take(140)
        ?: "(empty)"

    fun renamed(newName: String): Skill {
        require(newName.isNotBlank()) { "Skill name cannot be blank" }
        require(newName.length <= 80) { "Skill name cannot exceed 80 characters" }
        return copy(name = newName, updatedAt = System.currentTimeMillis())
    }

    fun withBody(newBody: String): Skill = copy(body = newBody, updatedAt = System.currentTimeMillis())

    fun withDescription(newDescription: String): Skill {
        require(newDescription.length <= 240) { "Skill description cannot exceed 240 characters" }
        return copy(description = newDescription, updatedAt = System.currentTimeMillis())
    }
}

@Serializable
internal data class SkillsEnvelope(val skills: List<Skill> = emptyList(), val version: Int = 1)

internal val SkillsJson: Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

internal fun List<Skill>.encodeToJsonString(): String =
    SkillsJson.encodeToString(SkillsEnvelope.serializer(), SkillsEnvelope(this))

internal fun String.decodeAsSkillList(): List<Skill> {
    if (isBlank()) return emptyList()
    return try {
        SkillsJson.decodeFromString(SkillsEnvelope.serializer(), this).skills
    } catch (e: Exception) {
        emptyList()
    }
}
