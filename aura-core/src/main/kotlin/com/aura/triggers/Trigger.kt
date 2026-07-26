package com.aura.triggers

import kotlinx.serialization.Serializable

/** User-defined trigger: condition + action. */
@Serializable
data class Trigger(
    val id: String,
    val label: String,
    val condition: TriggerCondition,
    val action: TriggerAction,
    val enabled: Boolean = true,
)
