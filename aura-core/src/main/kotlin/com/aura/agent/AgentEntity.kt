package com.aura.agent

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A persistent, named agent with its own identity, tools, model,
 * memory scope, and personality. Replaces [Specialist] as the
 * canonical agent definition.
 *
 * Built-in agents (isBuiltin=true) are seeded from [Specialist.ALL]
 * on first run. Custom agents are user-creatable via Settings.
 */
@Serializable
@Entity(
    tableName = "agents",
    indices = [Index("name", unique = true)],
)
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val identity: String,
    @ColumnInfo(name = "toolsAllowed") val toolsAllowed: String,
    val preferredModel: String? = null,
    val memoryScope: String = "shared",
    val personalityJson: String = "{}",
    val isBuiltin: Boolean = false,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val color: Int = 0,
) {
    /** Parse the comma-separated tools into a Set. */
    fun toolSet(): Set<String> =
        if (toolsAllowed.isBlank()) emptySet()
        else toolsAllowed.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Parse the personality JSON. Returns default profile on failure. */
    fun personality(): PersonalityProfile {
        if (personalityJson.isBlank() || personalityJson == "{}") return PersonalityProfile()
        return try {
            kotlinx.serialization.json.Json.decodeFromString(personalityJson)
        } catch (_: Exception) {
            PersonalityProfile()
        }
    }
}