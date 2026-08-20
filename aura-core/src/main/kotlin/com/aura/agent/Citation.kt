package com.aura.agent

import kotlinx.serialization.Serializable

/**
 * Moved here from `com.aura.tools`.
 *
 * It and [ToolCategories] were the only two symbols `agent` referenced in `tools`, against
 * 70 files going the other way — so those two names were the whole of the largest package
 * cycle in the codebase. A citation is part of a conversation turn, which is `agent`'s
 * concern; it was in `tools` because a web tool is what produces one.
 */
/**
 * A source citation returned by a research/web tool.
 */
@Serializable
data class Citation(
    val index: Int,
    val title: String,
    val url: String,
)
