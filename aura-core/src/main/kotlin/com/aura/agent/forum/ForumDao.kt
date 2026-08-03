package com.aura.agent.forum

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ForumPostDao {

    @Query("SELECT * FROM forum_posts WHERE threadId = :threadId ORDER BY createdAt ASC")
    suspend fun getThread(threadId: kotlin.String): List<ForumPostEntity>

    @Query("SELECT * FROM forum_posts WHERE threadId = :threadId ORDER BY createdAt ASC")
    fun watchThread(threadId: kotlin.String): Flow<List<ForumPostEntity>>

    @Query("SELECT * FROM forum_posts WHERE status = :status ORDER BY createdAt DESC")
    suspend fun byStatus(status: kotlin.String): List<ForumPostEntity>

    @Query("SELECT * FROM forum_posts WHERE type = :type AND status = 'open' ORDER BY createdAt DESC")
    suspend fun openByType(type: kotlin.String): List<ForumPostEntity>

    @Query("SELECT * FROM forum_posts ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<ForumPostEntity>

    @Query("SELECT * FROM forum_posts WHERE id = :id")
    suspend fun getById(id: Long): ForumPostEntity?

    @Query("SELECT DISTINCT threadId FROM forum_posts WHERE type = 'proposal' AND status = 'open' ORDER BY createdAt DESC")
    suspend fun openProposalThreads(): List<kotlin.String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(post: ForumPostEntity): Long

    @Query("UPDATE forum_posts SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: kotlin.String)

    @Query("UPDATE forum_posts SET status = :status WHERE threadId = :threadId")
    suspend fun updateThreadStatus(threadId: kotlin.String, status: kotlin.String)

    @Query("DELETE FROM forum_posts WHERE threadId = :threadId")
    suspend fun deleteThread(threadId: kotlin.String)

    @Query("DELETE FROM forum_posts")
    suspend fun deleteAll()
}

@Dao
interface ForumVoteDao {

    @Query("SELECT * FROM forum_votes WHERE postId = :postId")
    suspend fun forPost(postId: Long): List<ForumVoteEntity>

    @Query("SELECT * FROM forum_votes WHERE postId = :postId AND vote = :vote")
    suspend fun forPostByVote(postId: Long, vote: kotlin.String): List<ForumVoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vote: ForumVoteEntity): Long

    @Query("SELECT COUNT(*) FROM forum_votes WHERE postId = :postId AND vote = :vote")
    suspend fun count(postId: Long, vote: kotlin.String): Int

    @Query("DELETE FROM forum_votes WHERE postId = :postId")
    suspend fun deleteForPost(postId: Long)

    @Query("DELETE FROM forum_votes")
    suspend fun deleteAll()
}