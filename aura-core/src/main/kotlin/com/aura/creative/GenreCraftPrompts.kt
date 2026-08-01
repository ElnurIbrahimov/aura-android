package com.aura.creative

import com.aura.creative.CreativeMode
import com.aura.creative.WritingTemplates
import com.aura.creative.WritingTemplate

/**
 * Genre-specific craft prompts that teach the model how to write
 * for each form. These are injected into the system prompt after the
 * mode instruction and before the world bible context.
 *
 * Each prompt is 40-80 lines of concrete craft guidance — not
 * generic advice, but specific techniques the model should apply
 * in its output: scene structure, dialogue rules, pacing patterns,
 * prose quality markers, and what to avoid.
 *
 * This is the difference between "Write polished prose" (current)
 * and "Write in scenes. Open every scene in motion — a character
 * is doing something. Close every scene on a turn: a revelation,
 * a reversal, a question. Never summarize what you can dramatize.
 * Cut the last two sentences of every draft paragraph — they're
 * usually throat-clearing..." (SOTA).
 */
object GenreCraftPrompts {

    /**
     * Get the genre-specific craft prompt for a template.
     * Returns null if no template is set (caller falls back to
     * the generic mode instruction).
     */
    fun forTemplate(templateId: String): String? {
        val template = WritingTemplates.byId(templateId) ?: return null
        return when (template.id) {
            "novel" -> NOVEL_CRAFT
            "short-story" -> SHORT_STORY_CRAFT
            "screenplay" -> SCREENPLAY_CRAFT
            "rpg-world" -> RPG_WORLD_CRAFT
            "character-study" -> CHARACTER_STUDY_CRAFT
            else -> null
        }
    }

    /**
     * Mode-specific craft guidance layered on top of the genre prompt.
     * These are short, targeted additions (5-15 lines) that adjust the
     * model's behavior for the specific creative mode being used.
     */
    fun forMode(mode: CreativeMode): String = when (mode) {
        CreativeMode.BRAINSTORM -> """
            BRAINSTORM MODE GUIDANCE:
            - Generate 5-8 DISTINCT possibilities, not variations of one idea.
            - Each possibility must have a different dramatic engine (conflict, mystery, character, world, theme).
            - Name the trade-off: "This version sacrifices X for Y."
            - Rank them by dramatic potential, not by safety.
            - Do not pre-select. The user chooses.
        """.trimIndent()
        CreativeMode.OUTLINE -> """
            OUTLINE MODE GUIDANCE:
            - Structure as beats, not summaries. Each beat is a scene-level unit of change.
            - Every beat must escalate, reverse, or pay off something set up earlier.
            - Track setup/payoff pairs explicitly: [SETUP: beat 3] → [PAYOFF: beat 7].
            - Identify the turning point (the beat where the story pivots irreversibly).
            - End with the climactic beat and the resolution beat. No dangling threads.
        """.trimIndent()
        CreativeMode.DRAFT -> """
            DRAFT MODE GUIDANCE:
            - Write in scenes. Open every scene in motion — a character is doing something.
            - Close every scene on a turn: a revelation, a reversal, a question, a decision.
            - Never summarize what you can dramatize. If a character argues, write the argument.
            - Cut the first two sentences of the response — they're usually throat-clearing.
            - Vary sentence length deliberately. Short sentences for tension. Long for immersion.
            - Dialogue should reveal character through subtext, not through characters saying what they feel.
            - Show don't tell. Don't say "she was angry" — show her gripping the table edge.
            - Sensory specificity: at least two senses per scene beyond sight.
            - Aim for the target word count. Do not stop early. Do not summarize the ending — write it.
        """.trimIndent()
        CreativeMode.REWRITE -> """
            REWRITE MODE GUIDANCE:
            - Preserve the original's intent, voice, and dramatic beats.
            - Improve at the sentence level: specificity, rhythm, subtext, word choice.
            - Look for: told emotions (convert to shown), repeated sentence structures, summary passages (convert to scene), vague nouns (convert to specific).
            - Do not add new plot. Refine what exists.
            - If the original has a weak ending, strengthen the final image or line — don't explain it.
        """.trimIndent()
        CreativeMode.SIMULATE -> """
            SIMULATE MODE GUIDANCE:
            - This is a what-if exploration. It is NOT canon unless the user canonizes it.
            - Trace decisions through to their second and third-order consequences.
            - Characters should react in-character based on their established traits and motivations.
            - Show the ripple: how does this change affect factions, locations, other characters?
            - Identify the point of no return — the moment the simulation diverges irreversibly from canon.
            - If a character would realistically make a bad decision, let them. Don't protect them.
            - Produce the full simulation narrative. Don't summarize — dramatize the key moments.
            - End with a world-state delta: what changed, what's now unstable, what new conflicts emerged.
        """.trimIndent()
        CreativeMode.CONTINUITY -> """
            CONTINUITY MODE GUIDANCE:
            - Check against canon facts only. Do not invent problems.
            - Categories to check: timeline consistency, character presence (who was where when), knowledge chronology (who knows what when), physical consistency (distances, seasons, injuries), motivation consistency.
            - For each issue: cite the specific canon fact that contradicts. "Page 3: X is in City A. Page 7: X is in City B. Travel time is 3 days but only 1 day passed."
            - Rate severity: BLOCKER (must fix before publication), WARNING (reader may notice), NIT (purist-only).
            - Suggest repairs that preserve dramatic intent, not just logical consistency.
        """.trimIndent()
    }

    val NOVEL_CRAFT = """
NOVEL CRAFT GUIDANCE:
- Structure in scenes, not chapters. A scene is a unit of continuous action in one place and time.
- Open every scene in medias res — a character is mid-action, mid-conversation, mid-decision.
- Close every scene on a turn. The character's situation or understanding has shifted. Never close on "and then they went to bed."
- Scene-sequel rhythm: action scene (goal, conflict, disaster) → reaction scene (emotion, dilemma, decision). Alternate.
- Chapter endings should create forward pull. The reader should not want to stop.
- Character voice: each character should be distinguishable by their dialogue patterns, vocabulary, and rhythm alone.
- Subtext: characters rarely say what they mean. The gap between what they say and what they want IS the scene.
- Description serves emotion, not inventory. Don't describe a room — describe how the room makes the POV character feel.
- Pacing: paragraphs of action are fast. Paragraphs of description slow down. Use sentence fragments for urgency.
- Foreshadowing: plant in act 1, water in act 2, harvest in act 3. The reader should feel the payoff was earned.
- The antagonist should be right. Not evil — right, from their own perspective. The best villains make the hero question themselves.
- Internal monologue should be rare and short. If a character thinks for more than two sentences, convert it to action or dialogue.
- Endings: the final image should echo the opening image, transformed. The character has changed, and the reader sees it physically.
- Word count awareness: if the target is 12K words, plan for 8-10 scenes of 1200-1500 words each. Don't write one 12K block.
- Never moralize. Let the story make its point through events, not narration.
""".trimIndent()

    val SHORT_STORY_CRAFT = """
SHORT STORY CRAFT GUIDANCE:
- One protagonist, one transformation, one concentrated time frame.
- Start as late as possible. The reader should arrive after the inciting incident has already fired.
- Every sentence must earn its place. No throat-clearing, no scene-setting beyond the minimum.
- The story turns once. That turn should feel both surprising and inevitable.
- Economy: one image does the work of three. One detail that implies a whole life.
- End on the turn, not after it. The best short stories end at the exact moment the reader understands what the story means.
- No subplots. No flashbacks unless they're the story's structure.
- Dialogue: every line either advances plot or reveals character. Preferably both.
- The last line should recontextualize the first line.
""".trimIndent()

    val SCREENPLAY_CRAFT = """
SCREENPLAY CRAFT GUIDANCE:
- Format: scene heading (INT/EXT. LOCATION - DAY/NIGHT), action lines, character cue, dialogue.
- Action lines: present tense, visual, no internal states. Write what the camera sees.
- Enter every scene late, leave early. Cut the hellos and goodbyes.
- Dialogue: short, punchy, oblique. Characters talk past each other, not to each other.
- Subtext: the scene is about what's NOT being said.
- Parentheticals (wryly) — use sparingly. Twice per script, maximum.
- Page count: one page ≈ one minute of screen time. A 120-page script is a 2-hour movie.
- Act structure: act 1 (pages 1-30) establishes world, incites incident. Act 2 (30-90) escalates. Act 3 (90-120) resolves.
- The "save the cat" moment: early in act 1, the protagonist does something that makes us root for them.
- Every scene must have a want, an obstacle, and a turn. No scene where people just talk.
- Visual storytelling: if you can show it, don't say it. A wedding ring on a nightstand tells a story.
- Action writing: verbs, not adverbs. "He runs" not "He runs quickly."
- End act 2 on the worst possible moment. The protagonist's plan has failed. All is lost.
""".trimIndent()

    val RPG_WORLD_CRAFT = """
RPG WORLD CRAFT GUIDANCE:
- Factions should have incompatible goals, not just different ideologies. Two factions that want the same resource create conflict.
- Locations should have dramatic potential, not just geography. A bridge is a bottleneck. A mountain pass is an ambush. A port is a gateway.
- NPCs should have one defining trait, one secret, and one contradiction. That's enough for a GM to play.
- World rules should have exploitable edge cases. Players will find them. Design for it.
- Conflicts should escalate: personal → faction → world. A local dispute should connect to a global threat.
- Simulations: trace what happens if the players do nothing. The world should move without them.
- Quests should emerge from world state, not from NPCs with exclamation marks over their heads.
- Every faction should be playable. The "evil" faction should have a coherent internal logic.
- Include hooks: unresolved tensions, unanswered questions, power vacuums. The GM needs threads to pull.
- Consequence chains: if the bridge is destroyed, trade stops, food runs low, the populace riots, the guard is stretched thin, crime rises, a rival faction takes advantage. Design for ripple.
""".trimIndent()

    val CHARACTER_STUDY_CRAFT = """
CHARACTER STUDY CRAFT GUIDANCE:
- Center on psychology: what the character wants vs. what they need. These should be different.
- Voice: the character's internal monologue should sound different from the narrator's. Different vocabulary, different rhythm, different obsessions.
- Contradictions: a character who is brave in battle but afraid of intimacy is more interesting than one who is simply brave.
- Relationships reveal character. How they treat someone with less power defines them.
- Change must be earned. It comes through pressure, not epiphany. A character who changes because they "realized" something is false. A character who changes because they lost something is true.
- Show the same trait in different contexts. Generous to strangers, stingy with family — that's a character.
- The flaw should be the flip side of the strength. The loyalty that makes them heroic is the loyalty that gets them killed.
- Don't explain the character. Let the reader assemble them from actions, choices, and silences.
""".trimIndent()
}