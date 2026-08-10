package com.aura.a11y

/**
 * Renders a [UiSnapshot] as the text a model reads.
 *
 * Line-oriented, not JSON. The model does not need a tree — it needs a menu of
 * what it can do plus the words on screen, and JSON spends most of its budget
 * on punctuation and key names that repeat on every element.
 *
 * ```
 * app=com.whatsapp screen=ConversationActivity snapshot=7 size=1080x2400
 * #1  input  "Message" [entry] (540,2210) editable
 * #2  button "Send" [send] (1000,2210)
 * #3  text   "Mum: are you coming?"
 * #4  list   scrollable (540,1100)
 * [12 more — refine with filter=]
 * ```
 *
 * Truncation happens HERE, at element boundaries. The agentic loop's
 * `truncateToolResult` is a blunt `take(4000)` that would slice a line in half
 * and leave the model reading a coordinate as a label. Every large tool in this
 * codebase truncates its own output ahead of that safety net for the same
 * reason.
 */
object UiSnapshotSerializer {

    /**
     * ~3.6k, not the loop's 4k. The gap absorbs the header and leaves the
     * blunt cut unreachable in normal operation.
     */
    const val MAX_CHARS = 3_600

    private const val MAX_LABEL_CHARS = 60

    /**
     * Space held back for the trailing notice, whichever one is emitted. Both
     * are well under this; the margin costs one element at most.
     */
    private const val FOOTER_RESERVE = 100

    fun render(snapshot: UiSnapshot): String {
        val header = buildString {
            append("app=${snapshot.packageName}")
            if (snapshot.activityName.isNotBlank()) append(" screen=${snapshot.activityName}")
            append(" snapshot=${snapshot.id}")
            append(" size=${snapshot.screenWidth}x${snapshot.screenHeight}")
            if (snapshot.hasPasswordField) {
                // Stated rather than silent: the model will be refused if it
                // tries to act, and an unexplained refusal reads as a bug.
                append(" [password field visible — actions are blocked]")
            }
        }

        val lines = snapshot.elements.map { render(it) }
        val kept = mutableListOf<String>()
        // Reserve for the footer up front. Appending it after the budget check
        // is how the first version overran by 18 characters: the cut-off line
        // fit, and then the "[N more]" notice pushed the total over. The
        // reservation is unconditional because dropping a line is exactly when
        // the footer appears.
        var used = header.length + 1 + FOOTER_RESERVE
        var dropped = snapshot.truncatedCount
        for (line in lines) {
            if (used + line.length + 1 > MAX_CHARS) {
                dropped += lines.size - kept.size
                break
            }
            kept += line
            used += line.length + 1
        }

        return buildString {
            appendLine(header)
            kept.forEach { appendLine(it) }
            if (dropped > 0) {
                append("[$dropped more — narrow with filter=, or raise max_elements]")
            } else if (kept.isEmpty()) {
                // An empty screen and an unreadable one look identical
                // otherwise, and they call for completely different next moves.
                append("[no elements matched — the screen may be a canvas, a game, or FLAG_SECURE]")
            }
        }.trimEnd()
    }

    private fun render(e: UiElement): String = buildString {
        append("#${e.index} ")
        append(e.role.padEnd(6))
        if (e.label.isNotBlank()) append(" \"${truncateLabel(e.label)}\"")
        e.selector.viewId?.let { append(" [$it]") }
        // Coordinates only for things that can be acted on. On a text line they
        // are noise, and noise at 40 lines is a real fraction of the budget.
        if (e.actionable) append(" (${e.bounds.centerX},${e.bounds.centerY})")
        val flags = buildList {
            if (e.editable) add("editable")
            if (e.scrollable) add("scrollable")
            if (e.checkable) add(if (e.checked) "checked" else "unchecked")
            if (!e.enabled) add("disabled")
        }
        if (flags.isNotEmpty()) append(" ${flags.joinToString(" ")}")
    }

    private fun truncateLabel(label: String): String {
        val flat = label.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_LABEL_CHARS) flat else flat.take(MAX_LABEL_CHARS - 1) + "…"
    }
}
