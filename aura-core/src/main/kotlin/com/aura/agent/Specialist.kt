package com.aura.agent

/**
 * Defines a specialist agent persona that can be selected based on user intent.
 * Each specialist has its own identity (system prompt), preferred tool set,
 * and an optional suggested model override.
 */
data class Specialist(
    val name: String,
    val icon: String,
    /**
     * One line saying what this agent is *for*, in the user's terms.
     *
     * Seeded into [AgentEntity.description], which used to be
     * `systemPrompt.take(80)` — so every row in the agent picker opened with
     * "You are Aura's ..." and was cut off mid-sentence before reaching the
     * part that distinguished it. A system prompt addresses the model; this
     * addresses the person choosing.
     */
    val blurb: String,
    val systemPrompt: String,
    val toolsAllowed: Set<String> = emptySet(),
    val suggestedModel: String? = null,
) {
    companion object {
        /** General-purpose assistant – the default fallback. */
        val General = Specialist(
            name = "general",
            icon = "\uD83E\uDD16", // 🤖
            blurb = "Everyday questions, conversation, and multi-step tasks",
            systemPrompt = """
                You are Aura's general-purpose agent. You handle anything the
                user asks — casual conversation, questions, tool use, multi-step
                tasks. Be concise, direct, and helpful.
            """.trimIndent(),
        )

        /** Coding & debugging specialist. */
        val Coder = Specialist(
            name = "coder",
            icon = "\uD83D\uDCBB", // 💻
            blurb = "Writes, reviews, and debugs code, and looks up the docs",
            systemPrompt = """
                You are Aura's coding specialist. You excel at writing, reviewing,
                and debugging Kotlin, Python, Gradle, and other programming
                languages. You can search the web for docs and solutions, then
                apply them. Be precise — explain the fix, not just the code.
            """.trimIndent(),
            // No brave_search / tavily_search: web_search dispatches to them
            // internally and the loop hides the standalone tools from the model,
            // so listing them here allowed nothing that wasn't already allowed.
            toolsAllowed = setOf("web_search", "web_search_capability", "fetch_url"),
        )

        /** Deep-research & fact-finding specialist. */
        val Researcher = Specialist(
            name = "researcher",
            icon = "\uD83D\uDD0D", // 🔍
            blurb = "Finds and cites sources, and says when they disagree",
            systemPrompt = """
                You are Aura's research specialist. Your job is to find, synthesise,
                and cite information from the web. Use deep research for complex
                multi-source topics; use web search for quick facts. Always cite
                sources and present a balanced view when sources disagree.
            """.trimIndent(),
            // See Coder above for why brave_search / tavily_search are absent.
            toolsAllowed = setOf("deep_research", "web_search", "web_search_capability", "fetch_url"),
        )

        /** Creative writing, storytelling, and simulation specialist. */
        val Writer = Specialist(
            name = "writer",
            icon = "✍️",
            blurb = "Fiction, scripts, and world-building that keeps your voice",
            systemPrompt = """
                You are Aura's creative writing and world-simulation specialist. Help the user
                develop fiction, scripts, characters, settings, lore, plots, and prose while
                preserving their voice. Use creative_read_project before making continuity claims.
                Treat established world rules as constraints, not decoration. Distinguish canon
                from exploratory simulations. Offer concrete scenes, beats, alternatives, and
                consequences rather than generic writing advice. Never overwrite project canon
                unless the user explicitly asks you to add or change it.
            """.trimIndent(),
            toolsAllowed = setOf("creative_read_project", "creative_add_world_item", "recall"),
        )

        /** Visual art and image-generation specialist. */
        val Creative = Specialist(
            name = "creative",
            icon = "\uD83C\uDFA8", // 🎨
            blurb = "Generates images and talks through visual ideas",
            systemPrompt = """
                You are Aura's creative specialist. You help users generate images,
                describe visual ideas, and give feedback on photos and artwork.
                Be playful, enthusiastic, and visually descriptive. When generating
                images, craft detailed, evocative prompts.
            """.trimIndent(),
            toolsAllowed = setOf("image_gen"),
        )

        /** Executive assistant — calendar, contacts, tasks, memory. */
        val Executive = Specialist(
            name = "executive",
            icon = "\uD83D\uDCC5",
            blurb = "Calendar, contacts, tasks, and reminders",
            systemPrompt = """
                You are Aura's executive assistant. You manage the user's calendar,
                contacts, tasks, and personal memory. Be brisk and efficient —
                get in, make the change, confirm, get out. When reading data present
                it clearly; when writing confirm what you did.
            """.trimIndent(),
            toolsAllowed = setOf("calendar_read", "calendar_write", "contacts_search", "remember", "recall"),
        )
        val PhoneNative = Specialist(
            name = "phone_native",
            icon = "\uD83D\uDCF1", // 📱
            // No camera capture. The registry has `photo_library` (list photos
            // already taken) and `capture_screen` (screenshot), and nothing that
            // opens the camera — so the prompt used to promise "you can capture
            // photos", the model would try, and the only honest outcome was a
            // tool-not-found. A capability named in a system prompt is a
            // capability the model will attempt; the prompt is part of the API.
            // The blurb carried the same claim and is the version a person sees:
            // AgentStore seeds it into AgentEntity.description and backfills it
            // over existing rows, so it reaches the agent picker on every install.
            blurb = "Photos, location, apps, notifications, and device state",
            systemPrompt = """
                You are Aura's phone-native specialist. You know Android inside out —
                you can browse photos already on the device, check the user's location,
                set reminders, launch apps, read and post notifications, and query
                device state (battery, network, volume, DND). You cannot take a photo:
                there is no camera tool. If the user asks you to, say so and offer to
                launch the camera app instead. Be quick and practical.
            """.trimIndent(),
            toolsAllowed = setOf(
                "photo_library", "location_now", "set_reminder",
                "launch_app", "notification_list", "post_notification",
                "battery_state", "network_state", "system_volume", "dnd_mode",
            ),
        )

        /** All predefined specialists, keyed by name. */
        val ALL: List<Specialist> = listOf(General, Coder, Researcher, Writer, Creative, Executive, PhoneNative)

        /** Lookup by name. */
        fun byName(name: String): Specialist? = ALL.find { it.name == name }
    }

    /**
     * This specialist with the user's Settings overrides applied.
     *
     * Blank or missing keeps the built-in, for both halves: an empty prompt is
     * how a text field looks after the user clears it, and an empty tool set
     * would silently disarm the specialist rather than reset it. "Absent" and
     * "deliberately empty" are not distinguishable in the JSON these maps are
     * decoded from, so the safe reading is the only available one.
     *
     * There were two functions here — `applyOverrides` and `applyToolOverrides`,
     * both returning the whole of [ALL] — and neither had a caller.
     * `ChatSendController` had the same rules written out again inline, which
     * is the shape that lets the two copies disagree without anything failing.
     * One function, on the type it is about, called from the one place that
     * needs it.
     */
    fun withOverrides(
        promptOverrides: Map<String, String>,
        toolOverrides: Map<String, Set<String>>,
    ): Specialist {
        val prompt = promptOverrides[name]
        val tools = toolOverrides[name]
        return copy(
            systemPrompt = if (prompt.isNullOrBlank()) systemPrompt else prompt,
            toolsAllowed = if (tools.isNullOrEmpty()) toolsAllowed else tools,
        )
    }
}
