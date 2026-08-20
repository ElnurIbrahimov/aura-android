package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolParameters
import com.aura.skills.SkillsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Names every skill available, and what each one is for.
 *
 * Until this existed the model could not find out what it could invoke. [UseSkillTool]
 * returns the available names only inside the *error* for a name that does not exist, so
 * discovery meant calling a tool wrongly on purpose and reading the failure. That was
 * survivable while the only skills were craft prompts nothing invoked; it is not survivable
 * as the thing standing between a shipped skill library and a model that never calls it.
 *
 * Returns the index and not the bodies. Ten procedures is most of a context window, an
 * index is a few hundred tokens, and [UseSkillTool] fetches the one the model chose. The
 * split is the reason both tools exist.
 *
 * A tool rather than a line in the system prompt, for two reasons: the prompt is assembled
 * inside the loop's memoised per-run context, and a description baked into the registry at
 * startup would go stale the moment the user writes a skill. This reads the store on every
 * call, so it cannot.
 *
 * Risk: READ_ONLY. It reads the names of the user's own skills, mutates nothing and reaches
 * no network — the same reasoning [UseSkillTool] records for its own risk level, which had
 * been wrong and made the policy engine demand a confirmation gate for a benign read.
 */
@Singleton
class ListSkillsTool @Inject constructor(
    private val skillsStore: SkillsStore,
) {
    val tool = Tool(
        name = "list_skills",
        description = "List the skills available to invoke with use_skill, each with the " +
            "name to pass and a one-line description of what it is for. Call this when a " +
            "task might have a skill written for it and you do not already know the name.",
        risk = ToolRisk.READ_ONLY,
        parameters = ToolParameters(),
        execute = { _, _ ->
            skillsStore.awaitLoaded()
            val skills = skillsStore.skills.value.sortedBy { it.name.lowercase() }
            ToolResult.Ok(
                if (skills.isEmpty()) {
                    // Said rather than returned empty. A blank result reads as a broken
                    // tool, and the model retries broken tools.
                    "No skills are defined yet. None to invoke."
                } else {
                    buildString {
                        appendLine("${skills.size} skill(s) available. Invoke one with use_skill.")
                        appendLine()
                        skills.forEach { skill ->
                            append("- ")
                            append(skill.name)
                            if (skill.description.isNotBlank()) {
                                append(" — ")
                                append(skill.description)
                            }
                            appendLine()
                        }
                    }.trimEnd()
                },
            )
        },
        category = "skills",
    )
}
