package com.aura.curiosity

import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading the model's reply back.
 *
 * This is the fragile joint in the feature: the author is the only model call,
 * and a question attached to the wrong subject is worse than no question. It
 * would be unanswerable — the answer would close a gap that was never the one
 * asked about — and it would be asked again every cycle, forever. So every
 * ambiguous line is dropped rather than guessed at.
 */
class QuestionAuthorParseTest {

    private val author = QuestionAuthor(mockk(relaxed = true), mockk(relaxed = true))

    private fun subject(id: String, context: String = "A project called \"$id\" came up.") =
        QuestionScanner.Subject(
            kind = OpenQuestionEntity.KIND_GAP,
            subjectKind = OpenQuestionEntity.SUBJECT_KG_NODE,
            subjectId = id,
            context = context,
            priority = 0.5f,
        )

    @Test
    fun `well-formed lines map to their subjects`() {
        val subjects = listOf(subject("n1"), subject("n2"))
        val parsed = author.parse(
            """
            1|user|What is Causeway to you?
            2|world|What is a Kalman filter?
            """.trimIndent(),
            subjects,
        )
        assertEquals(listOf("n1", "n2"), parsed.map { it.subject.subjectId })
        assertEquals(OpenQuestionEntity.ANSWERABLE_USER, parsed[0].answerable)
        assertEquals(OpenQuestionEntity.ANSWERABLE_WORLD, parsed[1].answerable)
    }

    @Test
    fun `an index with no subject is dropped, not clamped`() {
        // Clamping would attach the question to a real subject it is not about,
        // which is the one failure that cannot be recovered from later.
        assertTrue(author.parse("7|user|What is X?", listOf(subject("n1"))).isEmpty())
    }

    @Test
    fun `prose around the lines is ignored`() {
        val parsed = author.parse(
            """
            Sure! Here are the questions:
            1|user|What is Causeway?
            Let me know if you want more.
            """.trimIndent(),
            listOf(subject("n1")),
        )
        assertEquals(1, parsed.size)
        assertEquals("What is Causeway?", parsed.single().question)
    }

    @Test
    fun `a repeated index is used once`() {
        val parsed = author.parse(
            "1|user|What is Causeway?\n1|user|And what does it do?",
            listOf(subject("n1")),
        )
        assertEquals(1, parsed.size)
    }

    @Test
    fun `something that is not a question is not asked`() {
        val parsed = author.parse(
            "1|user|Causeway is a causal inference project.",
            listOf(subject("n1")),
        )
        assertTrue(parsed.isEmpty(), "a statement is not a question")
    }

    @Test
    fun `a question about a person is never sent to a search engine`() {
        // Whatever the model says. Looking up a name on the internet is not
        // curiosity about someone.
        val parsed = author.parse(
            "1|world|Who is Leyla?",
            listOf(subject("n9", context = "A person called \"Leyla\" came up, and nothing else is recorded.")),
        )
        assertEquals(OpenQuestionEntity.ANSWERABLE_USER, parsed.single().answerable)
    }

    @Test
    fun `an empty or malformed reply produces nothing`() {
        assertTrue(author.parse("", listOf(subject("n1"))).isEmpty())
        assertTrue(author.parse("I could not think of any.", listOf(subject("n1"))).isEmpty())
        assertTrue(author.parse("1|user", listOf(subject("n1"))).isEmpty())
    }

    // ---------------------------------------------------------------- the reason

    @Test
    fun `a fourth field carries why the subject matters`() {
        // The consequence judgement rides this call rather than a second one. The scanner's
        // arithmetic knows how much of the model touches a subject; only the model can say
        // whether knowing would change anything.
        val parsed = author.parse(
            "1|user|What is Causeway to you?|Three suggestions assume it is your main project.",
            listOf(subject("n1")),
        )

        assertEquals(1, parsed.size)
        assertEquals("Three suggestions assume it is your main project.", parsed.single().reason)
    }

    @Test
    fun `a line without a reason still yields its question`() {
        // The degradation pin. A model that answers in the old three-field shape, or drops
        // the field on one line, must not cost us the question — the reason is an
        // explanation, and losing an explanation is not worth losing the thing explained.
        val parsed = author.parse("1|user|What is Causeway to you?", listOf(subject("n1")))

        assertEquals(1, parsed.size)
        assertEquals(null, parsed.single().reason)
    }

    @Test
    fun `a blank or absurd reason is dropped, the question is kept`() {
        val long = "x".repeat(400)
        val parsed = author.parse(
            listOf(
                "1|user|What is Causeway to you?|   ",
                "2|user|And this one too?|" + long,
            ).joinToString("\n"),
            listOf(subject("n1"), subject("n2")),
        )

        assertTrue(parsed.isNotEmpty(), "questions must survive a bad reason")
        parsed.forEach { authored ->
            assertTrue(
                authored.reason == null || authored.reason!!.length <= QuestionAuthor.MAX_REASON_CHARS,
                "a reason must be absent or bounded, got ${authored.reason?.length}",
            )
        }
    }
}
