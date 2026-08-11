package com.aura.creative.livingworld

import android.util.Log
import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import com.aura.providers.ChatOptions
import com.aura.providers.ModelRole
import com.aura.providers.ModelRoleRouter
import com.aura.providers.ProviderMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gives the world a voice — the only place in this feature a model is involved.
 *
 * Four caps, in the order of how much each saves:
 *
 * 1. **The engine never calls a model.** Ticking is arithmetic, so a world that
 *    is merely *running* costs nothing at all. This class is reached only when
 *    something has already been judged worth saying.
 * 2. **Notability gates entry.** Most ticks produce nothing above the floor and
 *    this returns without a request.
 * 3. **One batched request, not one per event.** Up to [MAX_PER_BATCH] events
 *    go out as a numbered list and come back as numbered paragraphs. Three
 *    separate calls would cost three times as much and produce three
 *    paragraphs that had never heard of each other.
 * 4. **A daily ceiling**, counted from the events table itself rather than from
 *    a counter row that could drift away from the thing it counts.
 *
 * On top of those: no thinking budget, a small output ceiling, and the cheap
 * background model rather than the conversation one, since nobody is waiting.
 */
@Singleton
class WorldNarrator @Inject constructor(
    private val brain: Brain,
    private val store: LivingWorldStore,
    private val modelRoleRouter: ModelRoleRouter,
) {

    /**
     * Narrate what is worth narrating for one world.
     *
     * @return how many events were given prose. Zero is the ordinary case.
     */
    suspend fun narratePending(
        world: LivingWorldEntity,
        now: Long,
        floor: Double = NotabilityScorer.DEFAULT_FLOOR,
    ): Int {
        val spentToday = runCatching { store.narratedSince(world.id, now - DAY_MS) }
            .onFailure { Log.w(TAG, "narration budget read failed: ${it.message}", it) }
            .getOrDefault(Int.MAX_VALUE)
        if (spentToday >= MAX_PER_DAY) return 0

        val candidates = runCatching { store.topUnnarrated(world.id, floor, MAX_PER_BATCH) }
            .onFailure { Log.w(TAG, "candidate read failed: ${it.message}", it) }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return 0

        val model = resolveModel() ?: run {
            // No configured model is not an error worth surfacing: the world
            // keeps its events and its summaries, and stays perfectly readable
            // without prose.
            Log.i(TAG, "no model configured; leaving ${candidates.size} events unnarrated")
            return 0
        }

        val state = store.decode(world.stateJson)
        val prose = runCatching { request(model, world, state, candidates) }
            .onFailure { Log.w(TAG, "narration call failed: ${it.message}", it) }
            .getOrNull()
            ?: return 0

        val paragraphs = splitNumbered(prose, candidates.size)
        var written = 0
        for ((index, event) in candidates.withIndex()) {
            val text = paragraphs.getOrNull(index)?.trim().orEmpty()
            if (text.isBlank()) continue
            runCatching { store.attachNarration(event.id, text, now) }
                .onFailure { Log.w(TAG, "attaching narration failed: ${it.message}", it) }
                .onSuccess { written++ }
        }
        return written
    }

    /** Narrate one specific event on demand, ignoring the daily ceiling. */
    suspend fun narrateOne(world: LivingWorldEntity, event: LivingEventEntity, now: Long): Boolean {
        val model = resolveModel() ?: return false
        val state = store.decode(world.stateJson)
        val prose = runCatching { request(model, world, state, listOf(event)) }
            .onFailure { Log.w(TAG, "on-demand narration failed: ${it.message}", it) }
            .getOrNull() ?: return false
        val text = splitNumbered(prose, 1).firstOrNull()?.trim().orEmpty()
        if (text.isBlank()) return false
        store.attachNarration(event.id, text, now)
        return true
    }

    private suspend fun resolveModel(): String? =
        modelRoleRouter.explicit(ModelRole.CREATIVE_DRAFT)?.takeIf { it.isNotBlank() }
            // BACKGROUND rather than CONVERSATION: this runs unattended on a
            // timer, and the conversation model is the expensive one.
            ?: modelRoleRouter.resolve(ModelRole.BACKGROUND)?.takeIf { it.isNotBlank() }

    private suspend fun request(
        model: String,
        world: LivingWorldEntity,
        state: WorldState,
        events: List<LivingEventEntity>,
    ): String {
        val messages = listOf(
            ProviderMessage(ProviderMessage.Role.system, systemPrompt(state)),
            ProviderMessage(ProviderMessage.Role.user, userPrompt(world, events)),
        )
        val options = ChatOptions(
            temperature = 0.9,
            maxTokens = MAX_OUTPUT_TOKENS,
            // Narration is prose, not deduction. The events already say what
            // happened; nothing here needs working out.
            thinkingBudget = 0,
        )
        val out = StringBuilder()
        brain.stream(model, messages, emptyList(), options).collect { chunk ->
            when (chunk) {
                is BrainChunk.Text -> out.append(chunk.text)
                is BrainChunk.Error -> throw IllegalStateException(chunk.message)
                else -> Unit
            }
        }
        return out.toString()
    }

    private fun systemPrompt(state: WorldState): String = buildString {
        appendLine("You are the chronicler of a living world. You are given events that have already happened,")
        appendLine("as flat mechanical facts, and you write them up as a chronicler would.")
        appendLine()
        appendLine("Rules:")
        appendLine("- These events are settled history. Do not change, soften, or contradict them.")
        appendLine("- Do not invent named people, places or factions that are not listed below.")
        appendLine("- Two or three sentences each. No headings, no preamble, no commentary.")
        appendLine("- Write it as something that occurred, not as a summary of a data point.")
        appendLine()
        val factions = state.entities.filter { it.kind == WorldSeeder.KIND_FACTION && it.diedAtTick == 0L }
        if (factions.isNotEmpty()) {
            appendLine("The powers of this world:")
            for (faction in factions) {
                val land = state.stocks.firstOrNull {
                    it.entityId == faction.id && it.key == WorldSeeder.STOCK_TERRITORY
                }?.amountMilli ?: 0L
                appendLine("- ${faction.name} (holds ${land / 1000} land)")
            }
        }
    }

    private fun userPrompt(world: LivingWorldEntity, events: List<LivingEventEntity>): String = buildString {
        appendLine("It is ${WorldClock.label(world.currentTick)}.")
        appendLine()
        appendLine("Write one short paragraph for each of the following, numbered to match:")
        events.forEachIndexed { index, event ->
            appendLine("${index + 1}. [${WorldClock.label(event.tickIndex)}] ${event.summary}")
        }
        appendLine()
        appendLine("Reply with exactly ${events.size} numbered paragraph(s) and nothing else.")
    }

    /**
     * Split a numbered reply back into per-event paragraphs.
     *
     * Falls back to handing the whole reply to the first event when the
     * numbering cannot be found, which is better than dropping prose that was
     * already paid for.
     */
    internal fun splitNumbered(reply: String, expected: Int): List<String> {
        if (expected <= 1) return listOf(reply.trim().removePrefix("1.").trim())
        val matches = Regex("(?m)^\\s*(\\d+)[.)]\\s*").findAll(reply).toList()
        if (matches.isEmpty()) return listOf(reply.trim())
        val out = MutableList(expected) { "" }
        for ((index, match) in matches.withIndex()) {
            val number = match.groupValues[1].toIntOrNull() ?: continue
            if (number !in 1..expected) continue
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: reply.length
            out[number - 1] = reply.substring(start, end).trim()
        }
        return out
    }

    companion object {
        private const val TAG = "WorldNarrator"

        /** Events per request. Also the batch the model sees, so they can refer to each other. */
        const val MAX_PER_BATCH = 3

        /** Ceiling per world per rolling day, counted from `narratedAt`. */
        const val MAX_PER_DAY = 12

        const val MAX_OUTPUT_TOKENS = 700
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
