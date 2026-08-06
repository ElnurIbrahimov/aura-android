package com.aura.evolution

import com.aura.agent.ToolRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * One LLM call per candidate (D1): merges the old approve-only reflection with
 * patch authoring. The EVOLUTION-role model receives the real artifact
 * (skill body, memory contents, available tools) and must return strict JSON
 * `{decision, reason, patch}` where `patch` is the actual change.
 *
 * The returned patch is schema-validated by [EvolutionPatchValidator] before
 * the author reports [Result.Approved] — callers can trust `patchJson`.
 *
 * Failure semantics:
 * - transport/model error → [Result.Error]; the candidate stays PENDING and
 *   is retried on a later run.
 * - reject decision, unparseable output, or invalid patch → [Result.Rejected];
 *   the candidate is resolved REJECTED and never proposed.
 */
@Singleton
class EvolutionPatchAuthor @Inject constructor(
    private val reflection: EvolutionReflectionExecutor,
    private val validator: EvolutionPatchValidator,
    private val toolRegistry: Provider<ToolRegistry>,
    private val skillsStore: com.aura.skills.SkillsStore? = null,
    private val memoryStore: com.aura.memory.MemoryStore? = null,
) {
    private val json = EvolutionPatchJson.json

    sealed interface Result {
        data class Approved(val reason: String, val patchJson: String) : Result
        data class Rejected(val reason: String) : Result
        data class Error(val code: String, val message: String) : Result
    }

    suspend fun author(candidate: EvolutionCandidateEntity): Result {
        val action = runCatching { EvolutionAction.valueOf(candidate.action) }.getOrNull()
            ?: return Result.Rejected("unknown action ${candidate.action}")
        val context = when (val built = buildContext(action, candidate)) {
            is ContextResult.Ok -> built.context
            is ContextResult.Fail -> return Result.Rejected(built.reason)
        }
        return when (val response = reflection.reflect(SYSTEM_PROMPT, context.prompt)) {
            is EvolutionReflectionExecutor.Result.Error ->
                Result.Error(response.code, response.message)
            is EvolutionReflectionExecutor.Result.Ok ->
                parseAndValidate(action, response.text, context.validation)
        }
    }

    // ── Context building ────────────────────────────────────────

    private data class AuthorContext(
        val prompt: String,
        val validation: EvolutionPatchValidator.Context,
    )

    private sealed interface ContextResult {
        data class Ok(val context: AuthorContext) : ContextResult
        data class Fail(val reason: String) : ContextResult
    }

    private suspend fun buildContext(
        action: EvolutionAction,
        candidate: EvolutionCandidateEntity,
    ): ContextResult {
        return when (action) {
        EvolutionAction.PATCH_SKILL -> skillContext(candidate) { skill ->
            """
            |Action: PATCH_SKILL — improve a skill that keeps failing.
            |Evidence: ${candidate.rationale}
            |
            |Current skill "${skill.name}":
            |Description: ${skill.description}
            |Body:
            |${skill.body.take(MAX_ARTIFACT_CHARS)}
            |
            |If you approve, "patch" must be {"description": optional string, "body": full replacement body}.
            |The body must be complete (not a diff), different from the current body, and must never contain API keys or credentials.
            """.trimMargin()
        }

        EvolutionAction.RETIRE_SKILL -> skillContext(candidate) { skill ->
            """
            |Action: RETIRE_SKILL — permanently delete a skill.
            |Evidence: ${candidate.rationale}
            |
            |Skill "${skill.name}":
            |Description: ${skill.description}
            |Body:
            |${skill.body.take(MAX_ARTIFACT_CHARS)}
            |
            |If you approve, "patch" must be {"reason": one-sentence justification}.
            """.trimMargin()
        }

        EvolutionAction.PROMOTE_TO_HAND -> skillContext(candidate) { skill ->
            val tools = toolRegistry.get().names().take(MAX_TOOL_NAMES).joinToString(", ")
            """
            |Action: PROMOTE_TO_HAND — turn a frequently-used skill into a hand (automation macro of tool calls).
            |Evidence: ${candidate.rationale}
            |
            |Skill "${skill.name}":
            |Description: ${skill.description}
            |Body:
            |${skill.body.take(MAX_ARTIFACT_CHARS)}
            |
            |Available tools (steps may ONLY use these exact names): $tools
            |
            |If you approve, "patch" must be {"handName": short name, "triggerPhrase": optional phrase, "steps": [{"tool": name, "args": {string: string}}, ...]}.
            |Steps must be concrete and executable; use between 1 and 20 steps.
            """.trimMargin()
        }

        EvolutionAction.CONSOLIDATE_MEMORIES -> {
            val store = memoryStore
                ?: return ContextResult.Fail("MemoryStore not available")
            val target = store.get(candidate.targetId)
                ?: return ContextResult.Fail("target memory not found: ${candidate.targetId}")
            val related = store.recent(RELATED_MEMORY_POOL)
                .filter { it.id != target.id && it.scope == target.scope && it.category == target.category }
                .take(MAX_RELATED_MEMORIES)
            if (related.isEmpty()) {
                return ContextResult.Fail("no related memories to consolidate with ${candidate.targetId}")
            }
            val shown = listOf(target) + related
            val listing = shown.joinToString("\n") { mem ->
                "- id=${mem.id} | ${mem.content.take(MAX_MEMORY_PREVIEW_CHARS)}"
            }
            val prompt = """
            |Action: CONSOLIDATE_MEMORIES — merge redundant memories into one.
            |Evidence: ${candidate.rationale}
            |Target memory id: ${target.id}
            |
            |Memories (the ONLY ids you may reference):
            |$listing
            |
            |If you approve, "patch" must be {"memoryIds": [>=2 ids from the list above, including ${target.id}], "consolidatedContent": the merged memory text, "category": optional}.
            |Only consolidate memories that genuinely describe the same fact. Never invent ids.
            """.trimMargin()
            ContextResult.Ok(
                AuthorContext(
                    prompt = prompt,
                    validation = EvolutionPatchValidator.Context(
                        targetId = candidate.targetId,
                        shownMemoryIds = shown.map { it.id }.toSet(),
                    ),
                )
            )
        }
        }
    }

    private suspend inline fun skillContext(
        candidate: EvolutionCandidateEntity,
        buildPrompt: (com.aura.skills.Skill) -> String,
    ): ContextResult {
        val store = skillsStore ?: return ContextResult.Fail("SkillsStore not available")
        store.awaitLoaded()
        val skill = store.findById(candidate.targetId)
            ?: return ContextResult.Fail("target skill not found: ${candidate.targetId}")
        return ContextResult.Ok(
            AuthorContext(
                prompt = buildPrompt(skill),
                validation = EvolutionPatchValidator.Context(
                    targetId = candidate.targetId,
                    currentSkillBody = skill.body,
                ),
            )
        )
    }

    // ── Response parsing ────────────────────────────────────────

    @Serializable
    private data class AuthorEnvelope(
        val decision: String = "",
        val reason: String = "",
        val patch: JsonObject? = null,
    )

    private fun parseAndValidate(
        action: EvolutionAction,
        rawText: String,
        validation: EvolutionPatchValidator.Context,
    ): Result {
        val stripped = stripFences(rawText)
        val envelope = runCatching { json.decodeFromString<AuthorEnvelope>(stripped) }.getOrNull()
            ?: return Result.Rejected("model output was not valid JSON")
        val reason = envelope.reason.ifBlank { "no reason given" }
        if (!envelope.decision.trim().equals("approve", ignoreCase = true)) {
            return Result.Rejected(reason)
        }
        val patch = envelope.patch
            ?: return Result.Rejected("approve decision without a patch")
        return when (val result = validator.validate(action, patch.toString(), validation)) {
            is EvolutionPatchValidator.Result.Valid -> Result.Approved(reason, result.canonicalJson)
            is EvolutionPatchValidator.Result.Invalid -> Result.Rejected("invalid patch: ${result.reason}")
        }
    }

    /**
     * Defensive fence-stripping: models wrap JSON in ``` fences or prose
     * despite the strict-JSON instruction. Extract the outermost object.
     */
    private fun stripFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            val closing = t.lastIndexOf("```")
            if (closing >= 0) t = t.substring(0, closing)
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start >= 0 && end > start) t = t.substring(start, end + 1)
        return t.trim()
    }

    private companion object {
        const val MAX_ARTIFACT_CHARS = 4_000
        const val MAX_MEMORY_PREVIEW_CHARS = 300
        const val MAX_RELATED_MEMORIES = 9
        const val RELATED_MEMORY_POOL = 50
        const val MAX_TOOL_NAMES = 80

        val SYSTEM_PROMPT = """
        You review and author self-improvement changes for a personal AI assistant.
        Be conservative: approve only clear, safe improvements backed by the evidence.
        Reply with EXACTLY one JSON object and nothing else:
        {"decision": "approve" or "reject", "reason": "one sentence", "patch": {…} or null}
        The patch schema depends on the action and is described in the user message.
        Never include API keys, tokens, or credentials anywhere in your output.
        """.trimIndent()
    }
}
