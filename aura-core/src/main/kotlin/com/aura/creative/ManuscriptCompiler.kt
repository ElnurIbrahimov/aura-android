package com.aura.creative

/**
 * Turns a drafted outline into one Markdown document.
 *
 * Creative Studio could write a novella and had no way to hand it to anyone —
 * the only outbound paths in the app were images and URLs, so prose, the thing
 * this subsystem exists to produce, terminated in a Room table. This is the exit.
 *
 * **Pure by construction.** It is handed already-resolved text rather than a
 * store, so the entire document format is decided here with no database, no
 * Android and no coroutines — the discipline [com.aura.creative.longform.SceneContextBuilder]
 * follows, and for the same reason: what the document *says* is the interesting
 * part, and it should be testable without a device.
 *
 * The rule under every decision below: **a manuscript that silently comes out
 * short is worse than one that says where the holes are.** A missing scene, an
 * unrecoverable one, and prose orphaned by a re-planned outline are all stated
 * in the document rather than quietly omitted.
 */
object ManuscriptCompiler {

    /** No scene has been drafted for this beat yet. */
    private const val NOT_WRITTEN = "*[not yet written]*"

    /**
     * A scene exists but its text could not be recovered.
     *
     * The reader resolves through `CreativeArtifactStore.currentRevision`
     * precisely so this case is distinguishable. `currentContent` would have
     * answered with `previewText` — the first 200 characters — and a stub that
     * length reads as a finished scene in the middle of a novel.
     */
    private const val UNAVAILABLE = "*[scene text unavailable]*"

    /** Long enough to identify the project, short enough for any filesystem. */
    private const val MAX_SLUG = 60

    /**
     * Compile [project]'s outline into Markdown, or null when there is nothing
     * worth exporting.
     *
     * @param sceneTexts resolved scene text keyed by `StoryBeat.artifactId`. A
     *   key present with a null value means the beat has a scene whose text
     *   could not be read — a different fact from having no scene at all, and
     *   the document distinguishes them.
     * @param sceneArtifactCount how many scene artifacts exist for the project
     *   in total. Compared against the outline to detect prose the outline has
     *   lost track of; see the footer below.
     * @return the document, or null when no beat has any text. Null rather than
     *   a lone title, so a caller that reached here despite its own guard shows
     *   an error instead of handing the user an empty file.
     */
    fun compile(
        project: CreativeProject,
        sceneTexts: Map<String, String?>,
        sceneArtifactCount: Int,
    ): String? {
        val beats = project.world.outline
        if (beats.isEmpty()) return null

        val withText = beats.count { beat ->
            beat.artifactId.isNotBlank() && !sceneTexts[beat.artifactId].isNullOrBlank()
        }
        if (withText == 0) return null

        return buildString {
            appendLine("# ${project.name}")
            appendLine()

            beats.forEachIndexed { index, beat ->
                // Numbered from the beat's position now, not from the artifact
                // title. Artifacts are created as "3. The Gate Opens" with the
                // index frozen at draft time, so deleting a beat leaves every
                // later artifact title off by one.
                appendLine("## ${index + 1}. ${beat.title}")
                appendLine()
                val body = when {
                    beat.artifactId.isBlank() -> NOT_WRITTEN
                    else -> sceneTexts[beat.artifactId]?.takeIf { it.isNotBlank() }?.trim() ?: UNAVAILABLE
                }
                appendLine(body)
                appendLine()
            }

            // Prose the outline has lost track of.
            //
            // Re-planning replaces every beat with a fresh one carrying a blank
            // artifactId, and a failed commit can leave an artifact behind with
            // its beat still marked planned. Either way the scenes are safe in
            // the table and invisible here. Saying so turns "my book vanished"
            // into a recoverable fact — the same choice LongformRunner makes
            // when it commits the artifact before the beat status, so a crash
            // leaves something visible rather than something lost.
            val orphans = sceneArtifactCount - beats.count { it.artifactId.isNotBlank() }
            if (orphans > 0) {
                appendLine("---")
                appendLine()
                appendLine(
                    "*$orphans scene${if (orphans == 1) "" else "s"} exist in this project but are " +
                        "not in the current outline, so they are not included above. Re-planning an " +
                        "outline detaches the scenes already written from it.*",
                )
            }
        }.trimEnd() + "\n"
    }

    /**
     * Filesystem-safe stem for the exported file, derived from a project name.
     *
     * Never empty: an unnamed project still needs a filename, and "manuscript"
     * is a better answer than a bare timestamp.
     */
    fun slug(name: String): String =
        name.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(MAX_SLUG)
            .trim('-')
            .ifBlank { "manuscript" }
}
