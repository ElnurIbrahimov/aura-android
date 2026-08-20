package com.aura.skills

/**
 * The general-purpose skills Aura ships with.
 *
 * Skills were a complete mechanism with almost no content. [SkillsStore.seedBuiltins] is
 * idempotent and keyed on absent *names*, [SkillsStore.resetBuiltin] restores a rewritten
 * one, [com.aura.tools.UseSkillTool] injects a body as context and is READ_ONLY, and
 * `GlobalSearchRepository` already indexes the lot. What shipped through all of that was
 * `CraftSkills.seeds()` - writing-craft prompts read only by the creative engine. For every
 * other purpose a fresh install's skill list was empty, and an empty list is a feature
 * nobody finds.
 *
 * Each body is a procedure rather than a description, names tools that actually exist in
 * the registry, and ends with the rule that stops the obvious failure - because the failure
 * is the part the model would not have arrived at on its own. A skill that only restates
 * what a competent model already does is worse than absent: it spends a tool call and a
 * slice of context to say nothing.
 *
 * Seeded from `ProactiveBootstrap` beside [com.aura.creative.CraftSkills]. The two share a
 * namespace, which `BuiltinSkillsTest` holds them to.
 */
object BuiltinSkills {

    fun seeds(): List<Skill> = listOf(
        skill(
            name = "summarise-document",
            description = "Summarise a long document without quietly dropping half of it.",
            body = """
            Use when asked to summarise anything longer than fits comfortably in one read.

            1. Find it first. `search_documents` for the text if it is already indexed; `index_document`
               if it is not. Never summarise from a filename.
            2. Read it in passes, not in one gulp. First pass: what kind of document is this and what is
               it for. Second pass: the claims it actually makes.
            3. Produce, in this order:
               - one sentence on what the document is
               - the three to five claims it is actually making
               - anything it asserts without support
               - what you did not read, named explicitly
            4. That last line is not optional. A summary that silently covers 60 percent reads exactly
               like one that covers all of it, and the reader has no way to tell which they got.

            If the document contradicts itself, say so rather than picking the version that summarises
            more neatly.
            """,
        ),
        skill(
            name = "research-question",
            description = "Answer a factual question properly: search, read, cross-check, cite.",
            body = """
            Use when the answer must be right rather than plausible.

            1. Say what would settle the question before searching. If you cannot, the question needs
               narrowing first - do that with the user.
            2. Search with `web_search`, `brave_search` or `tavily_search`. For anything wide, use
               `parallel_research` or `deep_research` instead of many sequential searches.
            3. Open the actual sources with `read_url` or `fetch_url`. A search snippet is not a source;
               it is an advertisement for one.
            4. Cross-check every load-bearing claim against a second independent source. Two sites
               quoting the same press release are one source.
            5. Answer with the claim, the confidence, and the citation.

            When sources disagree, do not average them and do not pick the more recent one by reflex.
            Say who disagrees, on what, and which one you would act on and why. A clean answer built by
            hiding a real disagreement is the worst output available here.
            """,
        ),
        skill(
            name = "triage-notifications",
            description = "Read what has piled up and say what actually needs you.",
            body = """
            Use for "what did I miss" or "anything important".

            1. `notification_list` for what is waiting.
            2. Group by sender, not by time. Eleven from one group chat is one item.
            3. Sort each group into: needs a reply, needs a decision, needs nothing.
            4. Report only the first two. The third gets a count and no detail.
            5. For anything needing a reply, say who, how long it has waited, and what they asked -
               not the notification text verbatim.

            Never open, dismiss, or reply to anything as part of this. Triage is reading. Acting on any
            of it is a separate request the user has not made yet.
            """,
        ),
        skill(
            name = "plan-my-day",
            description = "Turn the calendar, tasks and reminders into one ordered list that fits in a real day.",
            body = """
            Use for "plan my day", "what's next", or the morning brief.

            1. `get_current_time` first - a plan that starts in the past is noise.
            2. `calendar_read` for today's fixed points, `manage_tasks` for what is open, and any
               reminders due.
            3. Build the list around the fixed points, not alongside them. Meetings are walls.
            4. Leave gaps. Travel, lunch, and the twenty minutes after a hard meeting are real. A plan
               with no slack is a plan that fails at 11am.
            5. Put at most three substantial tasks on it. More is a wish list, and the user will learn
               to ignore it.

            End with the single thing that matters most today, said in one line. If everything on the
            list is small, say that too - some days are admin days and pretending otherwise helps
            nobody.
            """,
        ),
        skill(
            name = "prepare-for-meeting",
            description = "Walk into a conversation knowing who they are and what is unfinished.",
            body = """
            Use before a call or meeting, ideally unprompted when one is close.

            1. `calendar_read` for who and when. `contacts_search` for who they are.
            2. `recall` for what you know about them, and `kg_query` for how they connect to the rest of
               what the user is working on.
            3. Search past conversations for the last exchange with them.
            4. Produce:
               - who they are, in one line
               - what was last discussed and when
               - what the user owes them, and what they owe the user
               - the open question this meeting is presumably to answer

            If you find nothing, say so plainly. A confident briefing assembled from nothing is worse
            than "I have no history with this person" - the user will walk in trusting it.
            """,
        ),
        skill(
            name = "extract-from-screen",
            description = "Pull structured information off whatever is on screen, and confirm before acting on it.",
            body = """
            Use when the user wants data out of an app you cannot query directly.

            1. `screen_read` for the structure. `capture_screen` only if you also need the pixels.
            2. Extract into named fields. Say which ones you could not find rather than guessing them.
            3. Read numbers back exactly as shown. Do not reformat currency, dates or IDs - a
               transcription that helpfully normalises is a transcription that introduced an error.
            4. Show the user what you extracted before doing anything with it.

            If the task also involves `screen_act`, stop here and confirm first. Reading a screen and
            driving one are different permissions in the user's mind even when the app grants both, and
            acting on a misread field is the failure that is hardest to undo.
            """,
        ),
        skill(
            name = "compare-options",
            description = "Decide between things without pretending the decision is obvious.",
            body = """
            Use for "should I use X or Y" and any decision with more than one defensible answer.

            1. Get the criteria from the user before scoring anything. Criteria invented after the fact
               describe the answer you already picked.
            2. Weight them. Unweighted criteria mean the option with the most rows wins.
            3. Score each option per criterion, and mark every score you are guessing at.
            4. Give the winner, the margin, and - this is the point - the one fact that would flip it.
               If nothing would, the decision was never close and the matrix was theatre.

            Where an option is worse on every axis, say so and drop it rather than padding the table to
            look balanced.
            """,
        ),
        skill(
            name = "debug-systematically",
            description = "Find the cause before proposing a fix.",
            body = """
            Use for anything broken. Especially when the fix looks obvious.

            1. Read the error. All of it, including the stack trace and the line numbers. Most of them
               contain the answer.
            2. Reproduce it. If you cannot reproduce it reliably, you are not debugging yet - gather
               more data instead of guessing.
            3. Ask what changed. Recent edits, new dependencies, a different device.
            4. For anything crossing a boundary, instrument each side and run once before theorising.
               Evidence of where it breaks beats a hypothesis about why.
            5. State one hypothesis, test it with the smallest possible change, and check the result
               before trying anything else.

            If three fixes have failed, stop fixing. Three failures in different places is the
            architecture telling you the shape is wrong, and a fourth attempt will find a fourth place.
            Use `code_interpreter` to test a theory in isolation rather than editing to see what
            happens.
            """,
        ),
        skill(
            name = "explain-without-jargon",
            description = "Explain something technical to someone who does not write code.",
            body = """
            Use when the user asks what something means, or why a technical decision was made.

            1. Lead with what it does for them, not what it is.
            2. One analogy, and only if it genuinely holds. A leaky analogy costs more than the jargon
               it replaced, because now they have to unlearn it.
            3. Name the trade-off. Every technical decision has one, and hiding it makes the choice look
               arbitrary rather than considered.
            4. Say what it means for them in practice: what changes, what breaks, what it costs.

            Do not simplify to the point of being wrong. "Roughly, and here is where that simplification
            breaks down" respects the reader; a clean lie does not. If they ask a follow-up that needs
            the real mechanism, give them the real mechanism.
            """,
        ),
        skill(
            name = "draft-in-my-voice",
            description = "Write something as the user would write it, not as a model would.",
            body = """
            Use when drafting a message, reply or post the user will send as themselves.

            1. `query_taste` for register and preferences, and `recall` for how they have written to
               this person before.
            2. Match length first. Most drafts read wrong because they are twice as long as anything the
               user has ever sent.
            3. Drop the scaffolding: no "I hope this finds you well", no restating their message back at
               them, no closing summary of what was just said.
            4. Keep their actual habits - if they open with the ask, open with the ask.
            5. Before returning it, read it once and ask: too formal, or too casual? Say which way you
               erred and offer the other version if it is close.

            Never send anything. Draft, show, and let the user send it.
            """,
        ),
    )

    private fun skill(name: String, description: String, body: String) = Skill(
        name = name,
        description = description,
        body = body.trimIndent().trim(),
        builtin = true,
    )
}
