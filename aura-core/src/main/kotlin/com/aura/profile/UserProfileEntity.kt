package com.aura.profile

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    /** Agent scope: "general" for shared, "agent:agent_<id>" for agent-private. */
    val agentScope: String = "general",
    val name: String? = null,
    val traitsJson: String = "[]",
    val preferencesJson: String = "{}",
    val factsJson: String = "[]",
    val lastUpdated: Long = 0L,
)
