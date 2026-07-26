package com.aura.triggers

import kotlinx.serialization.Serializable

/** Action performed when a trigger fires. */
@Serializable
sealed class TriggerAction {
    @Serializable
    data class RunHand(val handId: String) : TriggerAction()

    @Serializable
    data class Notify(val title: String, val body: String) : TriggerAction()

    @Serializable
    data class StartChat(val prompt: String) : TriggerAction()
}
