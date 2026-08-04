package com.aura.agent.forum

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain layer over [ForumPostDao] and [ForumVoteDao].
 * Provides the API agents use to post messages, debate, and vote
 * in the agent-to-agent Forum.
 */
@Singleton
class ForumEngine @Inject constructor(
    private val postDao: ForumPostDao,
    private val voteDao: ForumVoteDao,
) {
    private val mutex = Mutex()

    /** Post a new message to the forum. Returns the post ID. */
    suspend fun post(
        threadId: kotlin.String,
        agentId: kotlin.String,
        type: kotlin.String,
        title: kotlin.String,
        body: kotlin.String,
        sentiment: Float = 0f,
        replyToId: Long? = null,
    ): Long = mutex.withLock {
        postDao.insert(ForumPostEntity(
            threadId = threadId,
            agentId = agentId,
            replyToId = replyToId,
            type = type,
            title = title,
            body = body,
            sentiment = sentiment,
        ))
    }

    /** Get all posts in a thread, ordered chronologically. */
    suspend fun getThread(threadId: kotlin.String): List<ForumPostEntity> =
        postDao.getThread(threadId)

    /** Watch a thread as a Flow for reactive UI. */
    fun watchThread(threadId: kotlin.String): Flow<List<ForumPostEntity>> =
        postDao.watchThread(threadId)

    /** Cast a vote on a post. One vote per agent per post (unique index). */
    suspend fun vote(
        postId: Long,
        agentId: kotlin.String,
        vote: kotlin.String,
        reason: kotlin.String = "",
    ): Long = mutex.withLock {
        voteDao.insert(ForumVoteEntity(
            postId = postId,
            agentId = agentId,
            vote = vote,
            reason = reason,
        ))
    }

    /** Get all votes for a post. */
    suspend fun votesFor(postId: Long): List<ForumVoteEntity> =
        voteDao.forPost(postId)

    /** Tally votes: returns (for, against, abstain) counts. */
    suspend fun tally(postId: Long): VoteTally {
        val forVotes = voteDao.count(postId, "for")
        val against = voteDao.count(postId, "against")
        val abstain = voteDao.count(postId, "abstain")
        return VoteTally(forVotes, against, abstain)
    }

    /** Check if a proposal has reached quorum (≥60% for, minimum 3 voters). */
    suspend fun hasQuorum(postId: Long): Boolean {
        val tally = tally(postId)
        val total = tally.forVotes + tally.against + tally.abstain
        if (total < 3) return false
        val forRatio = tally.forVotes.toFloat() / total
        return forRatio >= 0.6f
    }

    /** Get all open proposals (interventions pending user approval). */
    suspend fun openProposals(): List<ForumPostEntity> =
        postDao.openByType("proposal")

    /** Get all open interventions (approved by council, pending user action). */
    suspend fun openInterventions(): List<ForumPostEntity> =
        postDao.openByType("intervention")

    /** Mark a post as resolved/rejected/approved. */
    suspend fun setStatus(postId: Long, status: kotlin.String) {
        postDao.updateStatus(postId, status)
    }

    /** Mark an entire thread as resolved. */
    suspend fun closeThread(threadId: kotlin.String) {
        postDao.updateThreadStatus(threadId, "closed")
    }

    /** Recent posts for dream-log / activity feed. */
    suspend fun recent(limit: Int = 50): List<ForumPostEntity> =
        postDao.recent(limit)

    /** Delete all forum data. */
    suspend fun deleteAll() {
        voteDao.deleteAll()
        postDao.deleteAll()
    }

    data class VoteTally(
        val forVotes: Int,
        val against: Int,
        val abstain: Int,
    ) {
        val total: Int get() = forVotes + against + abstain
    }
}