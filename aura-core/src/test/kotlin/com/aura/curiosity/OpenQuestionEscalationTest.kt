package com.aura.curiosity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import com.aura.memory.MemoryFtsSchema
import com.aura.proactive.ProactiveAwarenessEngine
import com.aura.proactive.ProactiveFindingType
import com.aura.proactive.ProactiveOutcomeEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When a question is allowed to leave the app.
 *
 * This is the one proactive category whose suggestion serves Aura rather than
 * the user, so the bar has to be higher than "it has been a while". It is
 * gated on the question never having been *seen* — not on its age — because a
 * card the user has looked at and left alone is a decision, and notifying about
 * a decision they already made is nagging.
 *
 * Everything downstream is the existing pipeline: salience, the motivation
 * accumulator, and the interruption ledger, which starts this category silent
 * and in-app like every other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenQuestionEscalationTest {

    private lateinit var db: MemoryDatabase
    private lateinit var engine: ProactiveAwarenessEngine

    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        val conversationStore = mockk<com.aura.agent.ConversationStore>(relaxed = true)
        coEvery { conversationStore.recent(any()) } returns emptyList()
        engine = ProactiveAwarenessEngine(
            db.memoryDao(),
            mockk(relaxed = true),
            conversationStore,
            openQuestionDao = db.openQuestionDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun question(
        createdAt: Long,
        timesAsked: Int = 0,
        status: String = OpenQuestionEntity.STATUS_OPEN,
    ) = db.openQuestionDao().insert(
        OpenQuestionEntity(
            id = "q1",
            kind = OpenQuestionEntity.KIND_GAP,
            subjectKind = OpenQuestionEntity.SUBJECT_KG_NODE,
            subjectId = "n1",
            question = "What is Causeway?",
            status = status,
            timesAsked = timesAsked,
            createdAt = createdAt,
        ),
    )

    private suspend fun openQuestionFindings() =
        engine.runAll().filter { it.type == ProactiveFindingType.OPEN_QUESTION.wire }

    @Test
    fun `a question the user has never seen escalates`(): Unit = runBlocking {
        question(createdAt = System.currentTimeMillis() - 4 * day)

        val findings = openQuestionFindings()
        assertEquals(1, findings.size)
        assertEquals("What is Causeway?", findings.single().message)
        // Carries its subject, so the outcome pass can ask afterwards whether
        // the nudge actually got it answered.
        assertEquals(ProactiveOutcomeEntity.SUBJECT_QUESTION, findings.single().subjectKind)
        assertEquals(listOf("q1"), findings.single().subjectIds)
    }

    @Test
    fun `a question the user has already seen and left alone does not escalate`(): Unit = runBlocking {
        // The card has been in front of them and is still open. That is a
        // decision, and a notification about it is nagging.
        question(createdAt = System.currentTimeMillis() - 30 * day, timesAsked = 4)

        assertTrue(openQuestionFindings().isEmpty())
    }

    @Test
    fun `a question raised today waits`(): Unit = runBlocking {
        question(createdAt = System.currentTimeMillis() - 60_000)

        assertTrue(openQuestionFindings().isEmpty())
    }

    @Test
    fun `an answered question does not escalate`(): Unit = runBlocking {
        question(createdAt = System.currentTimeMillis() - 10 * day, status = OpenQuestionEntity.STATUS_ANSWERED)

        assertTrue(openQuestionFindings().isEmpty())
    }

    @Test
    fun `no question, nothing to escalate`(): Unit = runBlocking {
        assertTrue(openQuestionFindings().isEmpty())
    }
}
