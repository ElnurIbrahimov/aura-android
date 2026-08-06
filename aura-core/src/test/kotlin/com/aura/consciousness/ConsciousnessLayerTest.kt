package com.aura.consciousness

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsciousnessLayerTest {

    // ── NarrativeSelf ──────────────────────────────────────────────

    @Test
    fun `NarrativeSelf toPrompt returns empty when no state`() {
        val ns = NarrativeSelf(mockk(relaxed = true))
        assertEquals("", ns.toPrompt())
    }

    @Test
    fun `NarrativeSelf toPrompt includes growth and concerns`() {
        val ns = NarrativeSelf(mockk(relaxed = true))
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
    fun `NarrativeSelf updateRelationshipState sets note`() {
        val ns = NarrativeSelf(mockk(relaxed = true))
        ns.updateRelationshipState("Strong, collaborative")
        assertTrue(ns.toPrompt().contains("Relationship"))
    }

    @Test
    fun `NarrativeSelf reset preserves identity anchors`() {
        val ns = NarrativeSelf(mockk(relaxed = true))
        ns.updateFromDream("growth", listOf("c1"), listOf("q1"))
        ns.reset()
        assertEquals("", ns.toPrompt()) // blank after reset
    }

    // ── IntrinsicMotivation ────────────────────────────────────────

    @Test
    fun `IntrinsicMotivation starts with default drives`() {
        val im = IntrinsicMotivation()
        assertEquals(4, im.drives.value.size)
    }

    @Test
    fun `IntrinsicMotivation assess increases curiosity with KG gaps`() {
        val im = IntrinsicMotivation()
        im.assess(kgGapCount = 15, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 0)
        val curiosity = im.drives.value[IntrinsicMotivation.DriveType.CURIOSITY]!!
        assertTrue(curiosity.intensity > 0.5f)
    }

    @Test
    fun `IntrinsicMotivation assess increases social with absence`() {
        val im = IntrinsicMotivation()
        im.assess(kgGapCount = 0, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 8f, contradictionCount = 0)
        val social = im.drives.value[IntrinsicMotivation.DriveType.SOCIAL]!!
        assertTrue(social.intensity > 0.5f)
    }

    @Test
    fun `IntrinsicMotivation assess increases coherence with contradictions`() {
        val im = IntrinsicMotivation()
        im.assess(kgGapCount = 0, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 3)
        val coherence = im.drives.value[IntrinsicMotivation.DriveType.COHERENCE]!!
        assertTrue(coherence.intensity > 0.5f)
    }

    @Test
    fun `IntrinsicMotivation satisfy reduces intensity`() {
        val im = IntrinsicMotivation()
        im.assess(kgGapCount = 20, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 0)
        im.satisfy(IntrinsicMotivation.DriveType.CURIOSITY)
        val curiosity = im.drives.value[IntrinsicMotivation.DriveType.CURIOSITY]!!
        assertTrue(curiosity.intensity < 0.2f)
    }

    @Test
    fun `IntrinsicMotivation mostUrgent returns highest urgency drive`() {
        val im = IntrinsicMotivation()
        im.assess(kgGapCount = 20, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 0)
        val urgent = im.mostUrgent()
        assertNotNull(urgent)
        assertEquals(IntrinsicMotivation.DriveType.CURIOSITY, urgent!!.drive)
    }

    @Test
    fun `IntrinsicMotivation mostUrgent returns null when all satisfied`() {
        val im = IntrinsicMotivation()
        // Default drives have low intensity + recent lastSatisfiedAt
        val urgent = im.mostUrgent()
        // With default 0.3 intensity and 0 time pressure, urgency = 0.18 which is < 0.3
        assertEquals(null, urgent)
    }

    @Test
    fun `IntrinsicMotivation toPrompt includes drive name when urgent`() {
        val im = IntrinsicMotivation()
        im.assess(kgGapCount = 20, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 0)
        val prompt = im.toPrompt()
        assertTrue(prompt.contains("[Intrinsic motivation]"))
        assertTrue(prompt.contains("curiosity"))
    }

    @Test
    fun `IntrinsicMotivation toPrompt returns empty when no urgent drive`() {
        val im = IntrinsicMotivation()
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
        val tom = TheoryOfMind()
        assertEquals(0, tom.model.value.commStyle.sampleCount)
        assertEquals("", tom.toPrompt())
    }

    @Test
    fun `TheoryOfMind toPrompt returns empty before 3 samples`() {
        val tom = TheoryOfMind()
        tom.updateFromMessage("Hello there")
        tom.updateFromMessage("How are you?")
        assertEquals("", tom.toPrompt()) // only 2 samples
    }

    @Test
    fun `TheoryOfMind toPrompt includes style after 3 samples`() {
        val tom = TheoryOfMind()
        tom.updateFromMessage("Hey, can you help me with this API?")
        tom.updateFromMessage("Thanks, that's great!")
        tom.updateFromMessage("Perfect, let's deploy the migration via CI/CD")
        val prompt = tom.toPrompt()
        assertTrue(prompt.contains("[User Model]"))
    }

    @Test
    fun `TheoryOfMind detects technical depth`() {
        val tom = TheoryOfMind()
        tom.updateFromMessage("Can you refactor the async architecture to use a concurrent protocol with proper serialization?")
        assertTrue(tom.model.value.commStyle.technicalDepth > 0.5f)
    }

    @Test
    fun `TheoryOfMind detects frustration`() {
        val tom = TheoryOfMind()
        tom.updateFromMessage("This is not working AGAIN! wtf")
        assertTrue(tom.model.value.emotionalState.frustration > 0.3f)
    }

    @Test
    fun `TheoryOfMind detects positive valence`() {
        val tom = TheoryOfMind()
        tom.updateFromMessage("This is great, I love it, thanks!")
        assertTrue(tom.model.value.emotionalState.valence > 0f)
    }

    @Test
    fun `TheoryOfMind detects negative valence`() {
        val tom = TheoryOfMind()
        tom.updateFromMessage("This is broken and stupid, I hate it")
        assertTrue(tom.model.value.emotionalState.valence < 0f)
    }

    @Test
    fun `TheoryOfMind updateTopic adds new topic`() {
        val tom = TheoryOfMind()
        tom.updateTopic("Kotlin", 0.3f, "user wrote Kotlin code")
        assertTrue(tom.model.value.topics.containsKey("Kotlin"))
        assertEquals(1, tom.model.value.topics["Kotlin"]!!.interactions)
    }

    @Test
    fun `TheoryOfMind updateTopic increases existing topic level`() {
        val tom = TheoryOfMind()
        tom.updateTopic("Python", 0.2f, "first mention")
        tom.updateTopic("Python", 0.3f, "second mention")
        val topic = tom.model.value.topics["Python"]!!
        assertEquals(2, topic.interactions)
        assertTrue(topic.level > 0.5f)
    }

    @Test
    fun `TheoryOfMind decayTopics reduces confidence`() {
        val tom = TheoryOfMind()
        tom.updateTopic("Rust", 0.5f, "user asked about Rust")
        val before = tom.model.value.topics["Rust"]!!.confidence
        tom.decayTopics(168f) // 1 week
        val after = tom.model.value.topics["Rust"]!!.confidence
        assertTrue(after < before)
    }
}