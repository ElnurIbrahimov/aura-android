package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.creative.CreativeBranchStore
import com.aura.creative.CreativeProjectStore
import com.aura.creative.livingworld.LivingWorldStore
import com.aura.creative.livingworld.TimelineDiff
import com.aura.creative.livingworld.WorldClock
import com.aura.creative.livingworld.WorldEngine
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mine a living world for drama, or ask where two timelines part ways.
 *
 * `drama` ranks the events worth structuring a plot around — discoveries,
 * lies, conquests — by the notability the engine already scored them with;
 * a reveal's score is monotone in surprise x reach, so ORDER BY notability
 * IS the ranking, and nothing here re-computes anything. `divergence`
 * compares two branches of the same world and names the first event where
 * their histories part, plus how the standings differ now.
 */
@Singleton
class LivingWorldQueryTool @Inject constructor(
    private val projectStore: CreativeProjectStore,
    private val branchStore: CreativeBranchStore,
    private val livingWorldStore: LivingWorldStore,
) {
    fun definition() = ToolDefinition(
        name = "living_world_query",
        description = "Query a creative project's living world: 'drama' lists the most " +
            "notable discoveries, lies and conquests to structure plot around; 'divergence' " +
            "names the first event where two timeline branches part ways and how their " +
            "standings differ.",
        parameters = ToolParameters(
            properties = mapOf(
                "projectId" to ToolProperty("string", "Creative project ID"),
                "mode" to ToolProperty("string", "'drama' or 'divergence'"),
                "branch" to ToolProperty("string", "Branch name (defaults to main)"),
                "otherBranch" to ToolProperty("string", "Second branch for divergence (defaults to main)"),
            ),
            required = listOf("projectId", "mode"),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        category = "creative",
        execute = { call, _ ->
            val projectId = call.arguments["projectId"] as? String
                ?: return@Tool ToolResult.Error("missing 'projectId'", "bad_args")
            val mode = call.arguments["mode"] as? String
                ?: return@Tool ToolResult.Error("missing 'mode'", "bad_args")
            projectStore.get(projectId)
                ?: return@Tool ToolResult.Error("Project not found", "not_found")

            // Pure reads, deliberately not createMainBranch — that is a
            // get-or-create, and this tool is declared READ_ONLY. A missing
            // branch and an absent world are the same honest answer.
            suspend fun worldFor(name: String) = branchStore.forProject(projectId)
                .firstOrNull { it.name == name }
                ?.let { livingWorldStore.forProjectAndBranch(projectId, it.id) }

            when (mode) {
                "drama" -> {
                    val branch = call.arguments["branch"] as? String ?: "main"
                    val world = worldFor(branch)
                        ?: return@Tool ToolResult.Ok("No living world on branch '$branch'. Start one from the Living tab.")
                    val moments = livingWorldStore.topNotableOfKinds(world.id, DRAMA_KINDS, MAX_MOMENTS)
                    if (moments.isEmpty()) {
                        ToolResult.Ok("Nothing dramatic has happened on '$branch' yet — the world is young or quiet.")
                    } else {
                        ToolResult.Ok(
                            buildString {
                                appendLine("Biggest moments on '$branch', most notable first:")
                                for (moment in moments) {
                                    appendLine(
                                        "- ${WorldClock.label(moment.tickIndex)} — ${moment.summary} " +
                                            "(%.2f)".format(moment.notability),
                                    )
                                }
                            }.trim(),
                        )
                    }
                }
                "divergence" -> {
                    val nameA = call.arguments["branch"] as? String ?: "main"
                    val nameB = call.arguments["otherBranch"] as? String ?: "main"
                    val a = worldFor(nameA)
                        ?: return@Tool ToolResult.Ok("No living world on branch '$nameA'.")
                    val b = worldFor(nameB)
                        ?: return@Tool ToolResult.Ok("No living world on branch '$nameB'.")
                    if (a.id == b.id) {
                        return@Tool ToolResult.Ok("'$nameA' and '$nameB' are the same timeline.")
                    }
                    val base = when {
                        a.parentWorldId == b.id -> a.forkedAtTick
                        b.parentWorldId == a.id -> b.forkedAtTick
                        else -> minOf(a.forkedAtTick, b.forkedAtTick)
                    }
                    val pageA = livingWorldStore.ascAfter(a.id, base, SCAN_PAGE)
                    val pageB = livingWorldStore.ascAfter(b.id, base, SCAN_PAGE)
                    val divergence = TimelineDiff.firstDivergence(pageA, pageB)
                    val standings = TimelineDiff.standingsDiff(
                        livingWorldStore.decode(a.stateJson),
                        livingWorldStore.decode(b.stateJson),
                    ).take(MAX_STANDINGS)
                    ToolResult.Ok(
                        buildString {
                            if (divergence == null) {
                                val horizon = maxOf(
                                    pageA.lastOrNull()?.tickIndex ?: base,
                                    pageB.lastOrNull()?.tickIndex ?: base,
                                )
                                appendLine(
                                    "Identical through ${WorldClock.label(horizon)} — the scan is page-bounded, " +
                                        "so later divergence may exist beyond it.",
                                )
                            } else {
                                val moment = divergence.a ?: divergence.b
                                appendLine(
                                    "The timelines part at ${WorldClock.label(moment?.tickIndex ?: base)}:",
                                )
                                divergence.a?.let { appendLine("- '$nameA': ${it.summary}") }
                                divergence.b?.let { appendLine("- '$nameB': ${it.summary}") }
                            }
                            if (standings.isNotEmpty()) {
                                appendLine("Standings now ('$nameA' -> '$nameB'):")
                                standings.forEach { appendLine("- $it") }
                            }
                        }.trim(),
                    )
                }
                else -> ToolResult.Error("mode must be 'drama' or 'divergence'", "bad_args")
            }
        },
    )

    private companion object {
        val DRAMA_KINDS = listOf(
            WorldEngine.KIND_BELIEF_REVEAL,
            WorldEngine.KIND_LIE_TOLD,
            WorldEngine.KIND_CLAIM_WON,
        )
        const val MAX_MOMENTS = 12
        const val SCAN_PAGE = 400
        const val MAX_STANDINGS = 3
    }
}
