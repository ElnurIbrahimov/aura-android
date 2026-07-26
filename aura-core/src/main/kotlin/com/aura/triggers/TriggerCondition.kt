package com.aura.triggers

import kotlinx.serialization.Serializable

/** Condition that must become true for a trigger to fire. */
@Serializable
sealed class TriggerCondition {
    @Serializable
    data class Schedule(val cron: String, val timezone: String = "UTC") : TriggerCondition()

    @Serializable
    data class WebChanged(val url: String, val selector: String? = null) : TriggerCondition()

    @Serializable
    data class LocationEntered(
        val lat: Double,
        val lon: Double,
        val radiusMeters: Double,
    ) : TriggerCondition()

    @Serializable
    data class IntentReceived(val action: String) : TriggerCondition()
}
