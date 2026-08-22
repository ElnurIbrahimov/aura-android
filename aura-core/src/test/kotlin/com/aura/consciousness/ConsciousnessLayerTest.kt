package com.aura.consciousness

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsciousnessLayerTest {

    /**
     * A Context whose `filesDir` is a real temp directory.
     *
     * NarrativeSelf, IntrinsicMotivation and TheoryOfMind all persist to
     * `filesDir`. A bare `mockk(relaxed = true)` returns a mock File whose
     * getPath() is "", which resolves to a RELATIVE path — so save() would drop
     * JSON into the module directory during the test run. A temp dir keeps the
     * suite hermetic while letting load()/save() do real work.
     */
    private fun ctx(): Context {
        val dir = kotlin.io.path.createTempDirectory("aura-consciousness-test").toFile().also { it.deleteOnExit() }
        return mockk<Context>(relaxed = true).also { every { it.filesDir } returns dir }
    }

    // ── NarrativeSelf ──────────────────────────────────────────────

    @Test
    fun `NarrativeSelf toPrompt returns empty when no state`() {
        val ns = NarrativeSelf(ctx())
        assertEquals("", ns.toPrompt())
    }

    @Test
    fun `NarrativeSelf toPrompt includes growth and concerns`() {
        val ns = NarrativeSelf(ctx())
        ns.updateFromDream(
            growthSummary = "Learned about user's Kotlin expertise",
            concerns = listOf("User's project deadline", "Knowledge graph gaps in ML"),
            questions = listOf("Should we add more search tools?"),
        )
        val prompt = ns.toPrompt()
        assertTrue(prompt.contains("[Self-Model]"))
        assertTrue(prompt.contains("Recent growth"))
        assertTrue(prompt.contains("Active concerns"))
        assertTrue(prompt.contains("Open questions"))
    }

    @Test
    fun `NarrativeSelf reset preserves identity anchors`() {
        val ns = NarrativeSelf(ctx())
        ns.updateFromDream("growth", listOf("c1"), listOf("q1"))
        ns.reset()
        assertEquals("", ns.toPrompt()) // blank after reset
    }

    // ── IntrinsicMotivation ────────────────────────────────────────

    @Test
    fun `IntrinsicMotivation starts with default drives`() {
        val im = IntrinsicMotivation(ctx())
        assertEquals(4, im.drives.value.size)
    }

    @Test
    fun `IntrinsicMotivation assess increases curiosity with KG gaps`() {
        val im = IntrinsicMotivation(ctx())
        im.assess(kgGapCount = 15, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 0)
        val curiosity = im.drives.value[IntrinsicMotivation.DriveType.CURIOSITY]!!
        assertTrue(curiosity.intensity > 0.5f)
    }

    @Test
    fun `IntrinsicMotivation assess increases social with absence`() {
        val im = IntrinsicMotivation(ctx())
        im.assess(kgGapCount = 0, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 8f, contradictionCount = 0)
        val social = im.drives.value[IntrinsicMotivation.DriveType.SOCIAL]!!
        assertTrue(social.intensity > 0.5f)
    }

    @Test
    fun `IntrinsicMotivation assess increases coherence with contradictions`() {
        val im = IntrinsicMotivation(ctx())
        im.assess(kgGapCount = 0, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 3)
        val coherence = im.drives.value[IntrinsicMotivation.DriveType.COHERENCE]!!
        assertTrue(coherence.intensity > 0.5f)
    }

    @Test
    fun `IntrinsicMotivation satisfy reduces intensity`() {
        val im = IntrinsicMotivation(ctx())
        im.assess(kgGapCount = 20, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 0)
        im.satisfy(IntrinsicMotivation.DriveType.CURIOSITY)
        val curiosity = im.drives.value[IntrinsicMotivation.DriveType.CURIOSITY]!!
        assertTrue(curiosity.intensity < 0.2f)
    }

    @Test
    fun `IntrinsicMotivation mostUrgent returns highest urgency drive`() {
        val im = IntrinsicMotivation(ctx())
        im.assess(kgGapCount = 20, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 0)
        val urgent = im.mostUrgent()
        assertNotNull(urgent)
        assertEquals(IntrinsicMotivation.DriveType.CURIOSITY, urgent!!.drive)
    }

    @Test
    fun `IntrinsicMotivation mostUrgent returns null when all satisfied`() {
        val im = IntrinsicMotivation(ctx())
        // Default drives have low intensity + recent lastSatisfiedAt
        val urgent = im.mostUrgent()
        // With default 0.3 intensity and 0 time pressure, urgency = 0.18 which is < 0.3
        assertEquals(null, urgent)
    }

    @Test
    fun `IntrinsicMotivation toPrompt includes drive name when urgent`() {
        val im = IntrinsicMotivation(ctx())
        im.assess(kgGapCount = 20, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 0)
        val prompt = im.toPrompt()
        assertTrue(prompt.contains("[Intrinsic motivation]"))
        assertTrue(prompt.contains("curiosity"))
    }

    @Test
    fun `IntrinsicMotivation toPrompt returns empty when no urgent drive`() {
        val im = IntrinsicMotivation(ctx())
        assertEquals("", im.toPrompt())
    }

    // ── AffinityLevel.fromScore ────────────────────────────────────

    @Test
    fun `fromScore boundaries have no gaps`() {
        // Regression: the old (min, max) ranges left holes (10-11, 25-26,
        // ...) where fractional scores like 10.5 matched nothing and
        // silently fell back to ACQUAINTANCE.
        assertEquals(AffinityTracker.AffinityLevel.ACQUAINTANCE, AffinityTracker.AffinityLevel.fromScore(10f))
        assertEquals(AffinityTracker.AffinityLevel.ACQUAINTANCE, AffinityTracker.AffinityLevel.fromScore(10.5f))
        assertEquals(AffinityTracker.AffinityLevel.ACQUAINTANCE, AffinityTracker.AffinityLevel.fromScore(10.9f))
        assertEquals(AffinityTracker.AffinityLevel.FAMILIAR, AffinityTracker.AffinityLevel.fromScore(11f))
        assertEquals(AffinityTracker.AffinityLevel.FAMILIAR, AffinityTracker.AffinityLevel.fromScore(25.5f))
        assertEquals(AffinityTracker.AffinityLevel.CONNECTED, AffinityTracker.AffinityLevel.fromScore(26f))
        assertEquals(AffinityTracker.AffinityLevel.TRUSTED, AffinityTracker.AffinityLevel.fromScore(51f))
        assertEquals(AffinityTracker.AffinityLevel.CLOSE, AffinityTracker.AffinityLevel.fromScore(76f))
        assertEquals(AffinityTracker.AffinityLevel.CLOSE, AffinityTracker.AffinityLevel.fromScore(100f))
    }

    @Test
    fun `fromScore clamps out-of-range scores`() {
        assertEquals(AffinityTracker.AffinityLevel.ACQUAINTANCE, AffinityTracker.AffinityLevel.fromScore(-1f))
        assertEquals(AffinityTracker.AffinityLevel.CLOSE, AffinityTracker.AffinityLevel.fromScore(101f))
    }

    // ── TheoryOfMind ───────────────────────────────────────────────

    @Test
    fun `TheoryOfMind starts with empty model`() {
        val tom = TheoryOfMind(ctx())
        assertEquals(0, tom.model.value.commStyle.sampleCount)
        assertEquals("", tom.toPrompt())
    }

    @Test
    fun `TheoryOfMind toPrompt returns empty before 3 samples`() {
        val tom = TheoryOfMind(ctx())
        tom.updateFromMessage("Hello there")
        tom.updateFromMessage("How are you?")
        assertEquals("", tom.toPrompt()) // only 2 samples
    }

    @Test
    fun `TheoryOfMind toPrompt includes style after 3 samples`() {
        val tom = TheoryOfMind(ctx())
        tom.updateFromMessage("Hey, can you help me with this API?")
        tom.updateFromMessage("Thanks, that's great!")
        tom.updateFromMessage("Perfect, let's deploy the migration via CI/CD")
        val prompt = tom.toPrompt()
        assertTrue(prompt.contains("[User Model]"))
    }

    @Test
    fun `TheoryOfMind detects technical depth`() {
        val tom = TheoryOfMind(ctx())
        tom.updateFromMessage("Can you refactor the async architecture to use a concurrent protocol with proper serialization?")
        assertTrue(tom.model.value.commStyle.technicalDepth > 0.5f)
    }

    @Test
    fun `TheoryOfMind detects frustration`() {
        val tom = TheoryOfMind(ctx())
        tom.updateFromMessage("This is not working AGAIN! wtf")
        assertTrue(tom.model.value.emotionalState.frustration > 0.3f)
    }

    @Test
    fun `TheoryOfMind detects positive valence`() {
        val tom = TheoryOfMind(ctx())
        tom.updateFromMessage("This is great, I love it, thanks!")
        assertTrue(tom.model.value.emotionalState.valence > 0f)
    }

    @Test
    fun `TheoryOfMind detects negative valence`() {
        val tom = TheoryOfMind(ctx())
        tom.updateFromMessage("This is broken and stupid, I hate it")
        assertTrue(tom.model.value.emotionalState.valence < 0f)
    }

    // ── TheoryOfMind: topic knowledge ──────────────────────────────
    //
    // `UserModel.topics` was persisted, carried through backup, and written by
    // nothing: the only writers were a public `updateTopic`/`decayTopics` pair
    // with no production caller. Both of toPrompt's topic branches were
    // therefore unreachable. These pin the writer that replaced them.

    @Test
    fun `using a technical term is recorded as a topic`() {
        val tom = TheoryOfMind(ctx())

        tom.updateFromMessage("I refactored the database migration this morning")

        val topics = tom.model.value.topics
        assertTrue("database should be recorded", "database" in topics)
        assertTrue("migration should be recorded", "migration" in topics)
        assertEquals(1, topics["database"]!!.interactions)
        assertEquals(listOf("used"), topics["database"]!!.signals)
    }

    @Test
    fun `a word outside the vocabulary is not a topic`() {
        // The list is closed on purpose: the map stays bounded, the model stays
        // deterministic, and a proper noun cannot become a claim about what the
        // user knows.
        val tom = TheoryOfMind(ctx())

        tom.updateFromMessage("Elnur went to Baku on Tuesday")

        assertTrue(tom.model.value.topics.isEmpty())
    }

    @Test
    fun `using a term raises its level and asking about it lowers it`() {
        val used = TheoryOfMind(ctx())
        repeat(4) { used.updateFromMessage("I rewrote the compiler pass again") }

        val asked = TheoryOfMind(ctx())
        repeat(4) { asked.updateFromMessage("how does the compiler pass work?") }

        val fluent = used.model.value.topics["compiler"]!!.level
        val learning = asked.model.value.topics["compiler"]!!.level
        assertTrue("using a term should read as familiarity, was $fluent", fluent > 0.5f)
        assertTrue("asking about it should read as learning, was $learning", learning < 0.5f)
    }

    @Test
    fun `asking moves further than using, because it is the stronger signal`() {
        val tom = TheoryOfMind(ctx())
        tom.updateFromMessage("the compiler is fine")     // +0.08 -> 0.58
        val afterUse = tom.model.value.topics["compiler"]!!.level
        tom.updateFromMessage("why is the compiler slow") // -0.12 -> 0.46
        val afterAsk = tom.model.value.topics["compiler"]!!.level

        assertTrue(
            "one question should more than undo one mention",
            (afterUse - 0.5f) < (afterUse - afterAsk),
        )
    }

    @Test
    fun `a question without a question mark still reads as asking`() {
        val tom = TheoryOfMind(ctx())

        tom.updateFromMessage("how do I run the migration")

        assertTrue(tom.model.value.topics["migration"]!!.level < 0.5f)
    }

    @Test
    fun `toPrompt names expertise once a topic is established`() {
        // The branch that could never render. Eight mentions clear the 0.7 bar,
        // and three samples clear toPrompt's own floor.
        val tom = TheoryOfMind(ctx())
        repeat(8) { tom.updateFromMessage("shipped the schema migration and the deploy") }

        val prompt = tom.toPrompt()
        assertTrue("expected an expertise line, got: $prompt", "User expertise:" in prompt)
    }

    @Test
    fun `toPrompt names what the user is learning`() {
        val tom = TheoryOfMind(ctx())
        repeat(4) { tom.updateFromMessage("what is a gradient?") }

        val prompt = tom.toPrompt()
        assertTrue("expected a learning line, got: $prompt", "User learning:" in prompt)
    }

    @Test
    fun `confidence decays on elapsed time and stale topics are dropped`() = runBlocking {
        val tom = TheoryOfMind(ctx())
        tom.updateFromMessage("the kernel scheduler")
        assertTrue("kernel" in tom.model.value.topics)

        // Rewind the clock by a year via a restore, which is the only way to
        // set lastInteractionAt without a fake clock. One week is one halving;
        // a year takes 0.45 well under the 0.05 floor.
        val stale = tom.model.value.copy(
            lastInteractionAt = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000,
        )
        tom.restore(stale)
        tom.updateFromMessage("nothing technical in this one")

        assertTrue(
            "a topic nobody is confident about should stop being claimed",
            "kernel" !in tom.model.value.topics,
        )
    }

    @Test
    fun `a recent topic survives the decay pass`() {
        val tom = TheoryOfMind(ctx())
        tom.updateFromMessage("the kernel scheduler")
        tom.updateFromMessage("unrelated chatter")

        assertTrue("kernel" in tom.model.value.topics)
    }

    @Test
    fun `signals stay bounded however long the conversation runs`() {
        // Written to disk on every turn, so an unbounded list would grow the
        // file one entry per message per topic, forever.
        val tom = TheoryOfMind(ctx())
        repeat(30) { tom.updateFromMessage("the api again") }

        assertEquals(5, tom.model.value.topics["api"]!!.signals.size)
        assertEquals(30, tom.model.value.topics["api"]!!.interactions)
    }

    @Test
    fun `a term inside a longer word is not that term`() {
        // "therapist", "capital" and "rapidly" all contain "api". As a bare
        // substring match this only nudged a technicalDepth float nobody could
        // trace; with a topic map behind it, it becomes the prompt telling the
        // model "User expertise: api" because the user mentioned their
        // therapist. Confident, specific, and wrong about a person.
        val tom = TheoryOfMind(ctx())

        tom.updateFromMessage("I saw my therapist, then rapidly drove to the capital")

        assertTrue(
            "no topic should be claimed from any of those words",
            tom.model.value.topics.isEmpty(),
        )
        assertEquals(
            "technical depth reads the same vocabulary, so it must agree",
            0.3f,
            tom.model.value.commStyle.technicalDepth,
            0.001f,
        )
    }

    @Test
    fun `a plural still names its topic`() {
        val tom = TheoryOfMind(ctx())

        tom.updateFromMessage("we ran the migrations against both databases")

        assertTrue("migration" in tom.model.value.topics)
        assertTrue("database" in tom.model.value.topics)
    }

    @Test
    fun `a term with punctuation in it is matched whole`() {
        // "ci/cd" contains a non-word character, so  would not do what it
        // looks like it does.
        val tom = TheoryOfMind(ctx())

        tom.updateFromMessage("the ci/cd pipeline is green")

        assertTrue("ci/cd" in tom.model.value.topics)
    }

    @Test
    fun `technical depth and the topic map read the same vocabulary`() {
        // They used to be two lists that could drift; depth counted the hits
        // and discarded which ones they were.
        val tom = TheoryOfMind(ctx())

        tom.updateFromMessage("api database kernel compiler algorithm")

        assertEquals(5, tom.model.value.topics.size)
        assertEquals(1f, tom.model.value.commStyle.technicalDepth, 0.001f)
    }

}