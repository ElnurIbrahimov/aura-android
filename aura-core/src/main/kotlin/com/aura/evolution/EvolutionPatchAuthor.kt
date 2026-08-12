package com.aura.evolution

import com.aura.agent.ToolRegistry
import com.aura.providers.StructuredJson
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
 * - the model said "reject", or said "approve" and handed back a patch that
 *   fails validation → [Result.Rejected]; the candidate is resolved REJECTED
 *   and never proposed. Both are *judgements about the candidate*.
 * - the model's output could not be read at all → [Result.Inconclusive]; the
 *   candidate stays PENDING.
 *
 * That last case used to be [Result.Rejected], which meant a transient
 * formatting slip — a stray fence, a truncated object — permanently discarded a
 * self-improvement candidate that nothing would ever look at again. A failure to
 * parse is not a decision. It is the same class of event as a timeout, which was
 * already treated as retryable, and it is now treated the same way.
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

        /** A judgement about the candidate. Terminal — it is never revisited. */
        data class Rejected(val reason: String) : Result

        /**
         * The model replied, but nothing usable could be read out of it.
         * Retryable: the candidate stays PENDING.
         */
        data class Inconclusive(val reason: String) : Result

        data class Error(val code: String, val message: String) : Result
    }

    suspend fun author(candidate: EvolutionCandidateEntity): Result {
        val action = runCatching { EvolutionAction.valueOf(candidate.action) }.getOrNull()
            ?: return Result.Rejected("unknown action ${candidate.action}")
        val context = when (val built = buildContext(action, candidate)) {
            is ContextResult.Ok -> built.context
            is ContextResult.Fail -> return Result.Rejected(built.reason)
        }
        return when (val response = reflection.reflect(SYSTEM_PROMPT, context.prompt, ENVELOPE_SCHEMA)) {
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
            // Prefer the cluster the detector actually found. Falling back to
            // recent same-scope memories is only for candidates raised before
            // the detector recorded its cluster; those rows carry no argsJson.
            val cluster = runCatching {
                EvolutionPatchJson.json
                    .decodeFromString(MemoryClusterArgs.serializer(), candidate.argsJson)
                    .memoryIds
            }.getOrNull().orEmpty()
            val related = if (cluster.isNotEmpty()) {
                cluster.filter { it != target.id }.mapNotNull { store.get(it) }
                    .filter { it.scope == target.scope }
                    .take(MAX_RELATED_MEMORIES)
            } else {
                store.recent(RELATED_MEMORY_POOL)
                    .filter { it.id != target.id && it.scope == target.scope && it.category == target.category }
                    .take(MAX_RELATED_MEMORIES)
            }
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
        val stripped = StructuredJson.stripFences(rawText)
        val envelope = runCatching { json.decodeFromString<AuthorEnvelope>(stripped) }
            .onFailure { android.util.Log.w(TAG, "unreadable author envelope: ${it.message}", it) }
            .getOrNull()
            ?: return Result.Inconclusive("model output was not valid JSON")
        val reason = envelope.reason.ifBlank { "no reason given" }
        // A blank decision is unreadable output, not a rejection. The old code
        // folded the two together, so an envelope that parsed but carried no
        // decision was scored as "the model said no".
        val decision = envelope.decision.trim()
        if (decision.isBlank()) {
            return Result.Inconclusive("model output carried no decision")
        }
        if (!decision.equals("approve", ignoreCase = true)) {
            return Result.Rejected(reason)
        }
        val patch = envelope.patch
            ?: return Result.Rejected("approve decision without a patch")
        return when (val result = validator.validate(action, patch.toString(), validation)) {
            is EvolutionPatchValidator.Result.Valid -> Result.Approved(reason, result.canonicalJson)
            is EvolutionPatchValidator.Result.Invalid -> Result.Rejected("invalid patch: ${result.reason}")
        }
    }

    private companion object {
        const val TAG = "EvolutionPatchAuthor"

        /**
         * Mirrors [AuthorEnvelope]. `patch` is deliberately an unconstrained
         * object: its real shape depends on the [EvolutionAction] and is
         * described in the per-action user prompt, and it is validated properly
         * by [EvolutionPatchValidator] afterwards. Pinning a union of four patch
         * shapes here would duplicate the validator badly and drift from it.
         *
         * `patch` is also not `required` — a reject decision legitimately has
         * none, and requiring it would push the model toward inventing a patch
         * in order to satisfy the schema, which is the worst possible failure
         * mode for a system that applies what it authors.
         */
        val ENVELOPE_SCHEMA = com.aura.providers.ResponseSchema(
            name = "author_evolution_patch",
            schema = kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                put(
                    "properties",
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "decision",
                            kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                                put(
                                    "enum",
                                    kotlinx.serialization.json.JsonArray(
                                        listOf(
                                            kotlinx.serialization.json.JsonPrimitive("approve"),
                                            kotlinx.serialization.json.JsonPrimitive("reject"),
                                        ),
                                    ),
                                )
                            },
                        )
                        put(
                            "reason",
                            kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                            },
                        )
                        put(
                            "patch",
                            kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                            },
                        )
                    },
                )
                put(
                    "required",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            kotlinx.serialization.json.JsonPrimitive("decision"),
                            kotlinx.serialization.json.JsonPrimitive("reason"),
                        ),
                    ),
                )
            },
        )

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
