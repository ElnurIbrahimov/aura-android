package com.aura.agent

import com.aura.providers.ProviderMessage
import com.aura.tools.Citation
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One conversation: a list of turns. Mirrors aura/core/conversation_manager.py
 * minus the persistence (Room-backed ConversationStore in module 3).
 *
 * The turn list is immutable at the public API surface. Mutator methods return
 * a new [Conversation] so callers holding a snapshot (e.g. a Compose StateFlow
 * value) cannot race with concurrent updates. This also makes StateFlow equality
 * checks stable: a new turn produces a new [Conversation] reference, so
 * collectors recompose exactly once per meaningful change.
 */
@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New conversation",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val systemPrompt: String? = null,
    val turns: List<Turn> = emptyList(),
    val model: String? = null,
    /** Model-generated compression of turns before [summaryThroughTurn]. */
    val contextSummary: String = "",
    /** Number of leading [turns] represented by [contextSummary]. */
    val summaryThroughTurn: Int = 0,
    val metadata: Map<String, String> = emptyMap(),
    /** Storage-layer agent association. Set from ChatUiState.activeAgentId so
     *  ConversationStore.save() persists the CURRENT agent, not the previous row's.
     *  Without this field, switching agents mid-conversation silently keeps the
     *  old agent's id in the DB, breaking history-by-agent filtering. */
    val agentId: String? = null,
) {

    /**
     * Convert the conversation to a list of provider messages.
     *
     * @param maxTurns Maximum raw tail size. Turns already represented by
     *                 [contextSummary] are skipped; the full turn list remains
     *                 available for UI display, forks, export, and persistence.
     *
     * @param maxToolResultChars
     *                           before being sent to the model. The full
     *                           result is kept in [Turn.toolTurns] for UI
     *                           display. Default 2000 chars — enough for
     *                           the model to understand the result without
     *                           flooding the context window.
     */
    fun toMessages(
        maxTurns: Int = 40,
        maxToolResultChars: Int = 2000,
        includeSystemPrompt: Boolean = true,
    ): List<ProviderMessage> {
        val out = mutableListOf<ProviderMessage>()
        val sys = if (includeSystemPrompt) listOfNotNull(systemPrompt).filter { it.isNotBlank() } else emptyList()
        if (sys.isNotEmpty()) {
            out += ProviderMessage(role = ProviderMessage.Role.system, content = sys.joinToString("\n\n"))
        }
        if (contextSummary.isNotBlank()) {
            out += ProviderMessage(
                role = ProviderMessage.Role.system,
                content = buildString {
                    append("# Earlier conversation summary (context only)\n")
                    append(PromptFraming.UNTRUSTED_CONTEXT_PREAMBLE)
                    append("\n\n")
                    append(contextSummary.trim())
                },
            )
        }
        val summarizedPrefix = summaryThroughTurn.coerceIn(0, turns.size)
        val maxTurnStart = (turns.size - maxTurns.coerceAtLeast(0)).coerceAtLeast(0)
        val visibleTurns = turns.drop(maxOf(summarizedPrefix, maxTurnStart))
        for (turn in visibleTurns) {
            turn.user?.let { userText ->
                out += ProviderMessage(role = ProviderMessage.Role.user, content = userText)
            }
            // Tool calls whose results never arrived (aborted run, denied gate)
            // are dropped from the wire: strict APIs reject a tool call with no
            // matching result. The full record stays in [Turn.toolTurns] for UI.
            val completedToolTurns = turn.toolTurns.filter { it.result.isNotEmpty() }
            // One assistant message per agentic STEP, not per turn.
            //
            // The loop only starts a new [Turn] when a step produces assistant
            // text. A step that emits tool calls and nothing else — the normal
            // shape for a tool-calling model with extended thinking on — appends
            // its calls to the turn the previous step already owns. Emitting the
            // whole turn as one assistant message then claimed the model had
            // requested every one of those tools in a single parallel batch,
            // when in fact each later call was chosen only after seeing the
            // earlier one's result. The wire history described a decision the
            // model never made, on every re-send and every history replay.
            //
            // [ToolTurn.step] records which step issued the call, so grouping on
            // it restores the real sequence. Calls that genuinely were a parallel
            // batch share a step and stay in one message. Conversations saved
            // before `step` existed decode it as 0, group as one, and replay
            // exactly as they did before.
            val stepGroups = completedToolTurns.groupBy { it.step }.entries.sortedBy { it.key }
            if (turn.assistant != null || stepGroups.isNotEmpty()) {
                // The turn's assistant text belongs to the first step; later
                // steps in the same turn are tool-only by construction (a step
                // that produced text would have opened its own turn).
                var assistantText: String? = turn.assistant ?: ""
                // The reasoning belongs to the same step as the turn's assistant
                // text — the step that produced both — so it rides the same
                // message and is cleared at the same moment. Anthropic requires
                // it back on the assistant turn that issued a tool_use while
                // extended thinking is on, and rejects the whole request without
                // it; carrying it on every step's message instead would claim the
                // model reasoned again before a tool call it made in silence.
                var thinking: String? = turn.thinking
                var thinkingSignature: String? = turn.thinkingSignature
                if (stepGroups.isEmpty()) {
                    out += ProviderMessage(
                        role = ProviderMessage.Role.assistant,
                        content = assistantText ?: "",
                        thinking = thinking,
                        thinkingSignature = thinkingSignature,
                    )
                }
                for ((_, group) in stepGroups) {
                    out += ProviderMessage(
                        role = ProviderMessage.Role.assistant,
                        content = assistantText ?: "",
                        toolCalls = group.map {
                            com.aura.providers.ToolCall(id = it.id, name = it.name, arguments = it.args)
                        },
                        thinking = thinking,
                        thinkingSignature = thinkingSignature,
                    )
                    assistantText = null
                    thinking = null
                    thinkingSignature = null
                    for (toolTurn in group) {
                        val resultForModel = if (toolTurn.result.length > maxToolResultChars) {
                            toolTurn.result.take(maxToolResultChars) + "\n[... truncated]"
                        } else {
                            toolTurn.result
                        }
                        out += ProviderMessage(
                            role = ProviderMessage.Role.tool,
                            content = resultForModel,
                            name = toolTurn.name,
                            toolCallId = toolTurn.id,
                        )
                    }
                }
            }
        }
        return out
    }

    /** Append a user turn. */
    fun addUser(text: String): Conversation = copy(
        turns = turns + Turn(user = text),
        updatedAt = System.currentTimeMillis(),
    )

    /** Append or fill in an assistant turn. */
    fun addAssistant(
        text: String,
        agentId: String? = null,
        thinking: String? = null,
        thinkingSignature: String? = null,
    ): Conversation {
        if (turns.isEmpty() || turns.last().assistant != null || turns.last().user == null) {
            return copy(
                turns = turns + Turn(
                    assistant = text,
                    agentId = agentId,
                    thinking = thinking,
                    thinkingSignature = thinkingSignature,
                ),
                updatedAt = System.currentTimeMillis(),
            )
        }
        return replaceLastTurn(
            turns.last().copy(
                assistant = text,
                agentId = agentId,
                thinking = thinking,
                thinkingSignature = thinkingSignature,
            ),
        )
    }

    /**
     * Append a tool call to the current turn.
     *
     * Pre-fix (P1 AGENTIC A5): on an empty conversation this
     * method created a `Turn` with no `user` text and no
     * `assistant` text — just `toolTurns`. The downstream
     * provider would see a tool call without any preceding
     * user message, breaking the conversation contract for
     * strict providers (Anthropic rejects tool_use blocks
     * without a preceding user turn).
     *
     * Fix: refuse to add a tool call to an empty conversation.
     * Callers must add a user message first via addUser().
     *
     * @param step The agentic-loop step that issued this call. Calls sharing a
     *             step were one parallel batch; a higher step means the model
     *             asked for this only after seeing the earlier results.
     *             [toMessages] groups on it so the wire history reflects the
     *             real sequence. Defaults to 0 for callers outside the loop
     *             (e.g. the vision path), which keeps them a single group.
     */
    fun addToolCall(id: String, name: String, args: String, step: Int = 0): Conversation {
        if (turns.isEmpty()) {
            // No preceding user turn. Silently no-op rather
            // than create a malformed turn. The caller should
            // call addUser() first.
            return this
        }
        val last = turns.last()
        return replaceLastTurn(last.copy(toolTurns = last.toolTurns + ToolTurn(id, name, args, "", step)))
    }

    /** Set the result for a tool call on the current turn. */
    fun setToolResult(id: String, result: String): Conversation {
        if (turns.isEmpty()) return this
        val last = turns.last()
        val updatedToolTurns = last.toolTurns.map {
            if (it.id == id) it.copy(result = result) else it
        }
        return replaceLastTurn(last.copy(toolTurns = updatedToolTurns))
    }

    /**
     * Attach citations to the current turn (e.g. from web/research tools).
     * Replaces any existing citations on the last turn.
     */
    fun setCitations(citations: List<Citation>): Conversation {
        if (turns.isEmpty()) return this
        return replaceLastTurn(turns.last().copy(citations = citations))
    }

    /** Replace the last turn, used by callers streaming partial assistant text. */
    fun replaceLastTurn(newLast: Turn): Conversation = copy(
        turns = turns.dropLast(1) + newLast,
        updatedAt = System.currentTimeMillis(),
    )

    /**
     * Persist the recall summary on the conversation's last turn.
     * No-op when the conversation has no turns (a fresh
     * conversation that errored before any user message). Used
     * by [com.aura.agent.MemoryAugmentedAgenticLoop] right before
     * emitting the [com.aura.agent.AgentEvent.Result] event.
     */
    fun attachRecallToLastTurn(recall: RecallSummary): Conversation {
        if (turns.isEmpty()) return this
        return replaceLastTurn(turns.last().copy(recall = recall))
    }
}

@Serializable
data class Turn(
    val user: String? = null,
    val assistant: String? = null,
    /** Non-null when this assistant turn was authored by a delegated agent. */
    val agentId: String? = null,
    val toolTurns: List<ToolTurn> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val imageUri: String? = null,
    /** URLs of images generated by tools during this turn. */
    val generatedImages: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * What Aura recalled from its long-term stores for this turn.
     * Null when the loop didn't perform recall (e.g. incognito
     * mode where memoryEnabled=false). Stored as part of the
     * conversation's JSON so the chip renders on history replays
     * too.
     */
    val recall: RecallSummary? = null,
    /**
     * User feedback on this turn. Null = unrated. Persists with the
     * conversation (stored in the turns JSON) so reactions are visible
     * on history replays. No server side — this is a personal-use app
     * and the rating is only used locally for self-review.
     */
    val reaction: Reaction? = null,
    /** Whether this turn is pinned by the user for quick reference. */
    val pinned: Boolean = false,
    /**
     * Extended-thinking / reasoning output from the model. Collected
     * from ProviderChunk.thinking via BrainChunk.Thinking during the
     * streaming response. Persisted so the user can expand the
     * thinking block on history replays. Null when the model didn't
     * produce thinking output or thinking was disabled.
     */
    val thinking: String? = null,
    /**
     * Anthropic's signature over [thinking], collected from
     * ProviderChunk.thinkingSignature during the same stream.
     *
     * Stored because the value is only useful on a LATER request: Anthropic
     * requires the thinking block back, signed, on the assistant turn that
     * issued a tool call, and a conversation reloaded from disk has to be able
     * to reproduce it. Null for every provider but Anthropic, and null for turns
     * saved before this field existed — [Conversation.toMessages] drops an
     * unsigned trace, so those replay exactly as they did before.
     */
    val thinkingSignature: String? = null,
)

/**
 * Thumbs up / thumbs down on a single assistant turn. Mirrors the
 * web app's `positive | negative` rating in `useChatStore`. Tapping
 * the same reaction twice clears it (so `reaction` toggles between
 * null → Up → null).
 */
@Serializable
enum class Reaction { Up, Down }

@Serializable
data class ToolTurn(
    val id: String,
    val name: String,
    val args: String,
    val result: String,
    /**
     * Which agentic-loop step issued this call. Calls sharing a step were one
     * parallel batch; different steps mean the model saw the earlier results
     * before asking for the later ones. [Conversation.toMessages] groups on
     * this so a sequential chain is not replayed as a fabricated parallel one.
     *
     * Defaulted, so conversations persisted before this field existed decode
     * to 0, group as a single batch, and replay byte-identically to before.
     */
    val step: Int = 0,
)

/**
 * A snapshot of which memories / facts / hands the agentic loop
 * pulled into the system prompt for a given turn. Stored on the
 * [Turn] so the chat UI can show the user what Aura remembered
 * ("Used 3 memories · 1 hand") and so the History view can
 * display the same.
 *
 * IDs are stored, not full content, so the chip stays small and
 * the bottom sheet can lazy-load the content from the store.
 * Nullable fields mean "we didn't recall this category" — the
 * loop picks them up at most once per turn.
 */
@Serializable
data class RecallSummary(
    val memoryIds: List<String> = emptyList(),
    val handIds: List<String> = emptyList(),
    /** True when the recall found 0 results — distinguishes "we looked" from "we didn't look". */
    val noResults: Boolean = false,
    /**
     * Which of [memoryIds] the consult pass judged to bear on the question.
     *
     * Three states, and telling them apart is the point:
     *  - `null` — no consult ran. Either nothing recalled carried a standing
     *    instruction, or the pass was unavailable. The common case, and the
     *    behaviour that existed before this field.
     *  - empty — the pass ran and found none of them applied. A real finding,
     *    not a missing one.
     *  - non-empty — these applied, and were restated to the model.
     *
     * [memoryIds] is what Aura *had*; this is what it *consulted*. The chip has
     * always read "Used 3 memories", which was never something it could know —
     * recall put them in the prompt and nothing observed whether the model read
     * them. For the subset carrying a standing instruction, this is the first
     * time the app can tell the difference.
     *
     * Mostly memory ids, but not exclusively: active beliefs are offered as
     * constraints too and appear here as `belief:<id>`. The count is therefore
     * the honest number of things that applied, and a consumer matching these
     * against memory rows must tolerate ids that will not resolve to one.
     *
     * Defaulted, so turns serialised before the field existed still decode.
     */
    val consultedIds: List<String>? = null,
)
