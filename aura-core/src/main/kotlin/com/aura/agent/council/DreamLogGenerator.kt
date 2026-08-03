package com.aura.agent.council

import com.aura.agent.forum.ForumEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes overnight council activity into a readable "Dream Log"
 * the user reads in the morning. Format: timestamped debate summary
 * + final proposals + dissent notes.
 *
 * Output is plain text suitable for display in a DreamLogScreen.
 */
@Singleton
class DreamLogGenerator @Inject constructor(
    private val forumEngine: ForumEngine,
) {

    /**
     * Generate a dream log from recent forum posts.
     *
     * @param since Timestamp — only include posts after this time
     * @return human-readable dream log text
     */
    suspend fun generate(since: Long): kotlin.String {
        val recent = forumEngine.recent(100).filter { it.createdAt >= since }
        if (recent.isEmpty()) return ""

        val threads = recent.groupBy { it.threadId }
        val log = buildString {
            appendLine("Council Dream Log")
            appendLine("${java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(since))}")
            appendLine()

            for ((threadId, posts) in threads) {
                val debates = posts.filter { it.type == "debate" }
                val proposals = posts.filter { it.type == "proposal" }
                val topic = proposals.firstOrNull()?.title ?: debates.firstOrNull()?.title ?: threadId

                appendLine("── $topic ──")
                appendLine()

                for (post in debates.sortedBy { it.createdAt }) {
                    val agentName = post.agentId.removePrefix("agent_")
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(post.createdAt))
                    val mood = when {
                        post.sentiment > 0.3f -> "(supportive)"
                        post.sentiment < -0.3f -> "(concerned)"
                        else -> ""
                    }
                    appendLine("[$time] $agentName $mood:")
                    appendLine("  ${post.body.take(300)}")
                    appendLine()
                }

                if (proposals.isNotEmpty()) {
                    val proposal = proposals.first()
                    val tally = forumEngine.tally(proposal.id)
                    val status = proposal.status

                    appendLine("Proposal: ${proposal.title}")
                    appendLine("  Votes: ${tally.forVotes} for, ${tally.against} against, ${tally.abstain} abstain")
                    appendLine("  Status: $status")
                    appendLine()
                }

                appendLine()
            }
        }

        return log.trimEnd()
    }

    /**
     * Generate a short summary for notification or card display.
     */
    suspend fun summary(since: Long): kotlin.String {
        val recent = forumEngine.recent(50).filter { it.createdAt >= since }
        if (recent.isEmpty()) return "No council activity."

        val threads = recent.groupBy { it.threadId }
        val proposals = recent.filter { it.type == "proposal" }
        val debates = recent.filter { it.type == "debate" }
        val approved = proposals.count { it.status == "approved" }
        val rejected = proposals.count { it.status == "rejected" }

        return buildString {
            append("${threads.size} council session${if (threads.size != 1) "s" else ""}")
            append(", ${debates.size} debate${if (debates.size != 1) "s" else ""}")
            if (proposals.isNotEmpty()) {
                append(", $approved proposal${if (approved != 1) "s" else ""} approved")
                if (rejected > 0) append(", $rejected rejected")
            }
        }
    }
}