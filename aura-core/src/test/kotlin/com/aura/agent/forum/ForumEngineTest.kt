package com.aura.agent.forum

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.aura.agent.AgentDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ForumEngineTest {

    private lateinit var db: AgentDatabase
    private lateinit var forum: ForumEngine

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.agentDao().insertAll(listOf(
                com.aura.agent.AgentEntity(
                    id = "agent_general", name = "general", icon = "i",
                    description = "d", identity = "id", toolsAllowed = "",
                    isBuiltin = true, isDefault = true,
                ),
                com.aura.agent.AgentEntity(
                    id = "agent_researcher", name = "researcher", icon = "i",
                    description = "d", identity = "id", toolsAllowed = "",
                    isBuiltin = true,
                ),
                com.aura.agent.AgentEntity(
                    id = "agent_executive", name = "executive", icon = "i",
                    description = "d", identity = "id", toolsAllowed = "",
                    isBuiltin = true,
                ),
                com.aura.agent.AgentEntity(
                    id = "agent_writer", name = "writer", icon = "i",
                    description = "d", identity = "id", toolsAllowed = "",
                    isBuiltin = true,
                ),
            ))
        }
        forum = ForumEngine(db.forumPostDao(), db.forumVoteDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun post_createsAndRetrieves() = runBlocking {
        val id = forum.post("thread_1", "agent_general", "debate", "Take a break", "The user seems stressed")
        val thread = forum.getThread("thread_1")
        assertEquals(1, thread.size)
        assertEquals("Take a break", thread[0].title)
        assertEquals(id, thread[0].id)
    }

    @Test
    fun getThread_returnsChronologicalOrder() = runBlocking {
        forum.post("thread_1", "agent_general", "debate", "First", "First post")
        forum.post("thread_1", "agent_researcher", "debate", "Second", "Second post")
        forum.post("thread_1", "agent_executive", "debate", "Third", "Third post")
        val thread = forum.getThread("thread_1")
        assertEquals(3, thread.size)
        assertEquals("First", thread[0].title)
        assertEquals("Third", thread[2].title)
    }

    @Test
    fun vote_castsAndRetrieves() = runBlocking {
        val postId = forum.post("thread_1", "agent_general", "proposal", "Suggest walk", "User needs fresh air")
        forum.vote(postId, "agent_researcher", "for", "I agree")
        forum.vote(postId, "agent_executive", "against", "Too busy today")
        val votes = forum.votesFor(postId)
        assertEquals(2, votes.size)
    }

    @Test
    fun tally_countsCorrectly() = runBlocking {
        val postId = forum.post("thread_1", "agent_general", "proposal", "Test", "Test")
        forum.vote(postId, "agent_researcher", "for")
        forum.vote(postId, "agent_executive", "for")
        forum.vote(postId, "agent_writer", "against")
        val tally = forum.tally(postId)
        assertEquals(2, tally.forVotes)
        assertEquals(1, tally.against)
        assertEquals(0, tally.abstain)
        assertEquals(3, tally.total)
    }

    @Test
    fun hasQuorum_falseWithFewerThan3Voters() = runBlocking {
        val postId = forum.post("thread_1", "agent_general", "proposal", "Test", "Test")
        forum.vote(postId, "agent_researcher", "for")
        forum.vote(postId, "agent_executive", "for")
        assertFalse(forum.hasQuorum(postId))
    }

    @Test
    fun hasQuorum_trueWith60PercentAnd3Voters() = runBlocking {
        val postId = forum.post("thread_1", "agent_general", "proposal", "Test", "Test")
        forum.vote(postId, "agent_researcher", "for")
        forum.vote(postId, "agent_executive", "for")
        forum.vote(postId, "agent_writer", "against")
        assertTrue(forum.hasQuorum(postId))
    }

    @Test
    fun hasQuorum_falseWithLessThan60Percent() = runBlocking {
        val postId = forum.post("thread_1", "agent_general", "proposal", "Test", "Test")
        forum.vote(postId, "agent_researcher", "for")
        forum.vote(postId, "agent_executive", "against")
        forum.vote(postId, "agent_writer", "against")
        assertFalse(forum.hasQuorum(postId))
    }

    @Test
    fun openProposals_returnsOnlyOpenProposals() = runBlocking {
        forum.post("thread_1", "agent_general", "proposal", "Open one", "Test")
        forum.post("thread_2", "agent_general", "proposal", "Closed one", "Test")
        val open = forum.openProposals()
        assertEquals(2, open.size)
        forum.closeThread("thread_2")
        val openAfter = forum.openProposals()
        assertEquals(1, openAfter.size)
        assertEquals("Open one", openAfter[0].title)
    }

    @Test
    fun setStatus_updatesSinglePost() = runBlocking {
        val postId = forum.post("thread_1", "agent_general", "proposal", "Test", "Test")
        forum.setStatus(postId, "approved")
        val thread = forum.getThread("thread_1")
        assertEquals("approved", thread[0].status)
    }

    @Test
    fun deleteAll_clearsEverything() = runBlocking {
        forum.post("thread_1", "agent_general", "debate", "Test", "Test")
        val postId = forum.post("thread_1", "agent_general", "proposal", "Test2", "Test2")
        forum.vote(postId, "agent_researcher", "for")
        forum.deleteAll()
        assertTrue(forum.recent().isEmpty())
        assertTrue(forum.votesFor(postId).isEmpty())
    }
}