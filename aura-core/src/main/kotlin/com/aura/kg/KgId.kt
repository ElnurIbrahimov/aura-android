package com.aura.kg

import java.security.MessageDigest

object KgId {
    /**
     * Canonical id of the node representing the app's user.
     *
     * The KG extractor is instructed to label the speaker "user" with type
     * `person`, so every edge whose `sourceId` equals this is a statement
     * about the user. [com.aura.world.BeliefPromoter] uses it as the
     * subject filter — without a stable id there is no way to tell a belief
     * about the user from a belief about anyone else they mentioned.
     */
    val USER_NODE_ID: String by lazy { node(NodeType.PERSON, "user") }

    fun node(type: NodeType, label: String): String =
        sha256("kg|node|${type.name.lowercase()}|${label.lowercase().trim()}")

    fun edge(type: EdgeType, sourceId: String, targetId: String): String =
        sha256("kg|edge|${type.name.lowercase()}|$sourceId|$targetId")

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
