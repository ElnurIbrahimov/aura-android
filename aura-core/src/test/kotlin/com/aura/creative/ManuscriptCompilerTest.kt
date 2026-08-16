package com.aura.creative

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The document that finally leaves the phone.
 *
 * Pure by construction — the compiler is handed already-fetched text, so the
 * whole format is decided here with no database, no Android and no coroutines.
 * That is the same discipline `SceneContextBuilder` follows, and for the same
 * reason: the interesting decisions are about what the document *says*, and
 * those should be testable without a device.
 *
 * The load-bearing rule under all of it: a manuscript that silently comes out
 * short is worse than one that says where the holes are.
 */
class ManuscriptCompilerTest {

    private fun beat(
        n: Int,
        title: String = "Beat $n",
        artifactId: String = "art$n",
    ) = StoryBeat(id = "b$n", title = title, summary = "Summary $n", artifactId = artifactId)

    private fun project(beats: List<StoryBeat>, name: String = "The Lighthouse") = CreativeProject(
        id = "p1",
        name = name,
        description = "A keeper who cannot swim",
        genre = "literary",
        tone = "spare",
        world = WorldBible(outline = beats),
        templateId = "novel",
        turnCount = 0,
        createdAt = 0L,
        updatedAt = 0L,
    )

    /** Text as the reader resolved it: null means it could not be recovered. */
    private fun scenes(vararg pairs: Pair<String, String?>) = pairs.toMap()

    @Test
    fun `it opens with the project name as the title`() {
        val md = ManuscriptCompiler.compile(
            project = project(listOf(beat(1))),
            sceneTexts = scenes("art1" to "She reached the lighthouse."),
            sceneArtifactCount = 1,
        )!!

        assertTrue(md.startsWith("# The Lighthouse"), md.take(120))
    }

    /**
     * Order is the outline's, not the artifact table's. `forProjectByKind` is
     * `ORDER BY updatedAt DESC`, so an artifact-ordered manuscript would be
     * shuffled by whichever scene was last touched.
     */
    @Test
    fun `scenes follow outline order`() {
        val md = ManuscriptCompiler.compile(
            project = project(listOf(beat(1), beat(2), beat(3))),
            sceneTexts = scenes("art1" to "FIRST", "art2" to "SECOND", "art3" to "THIRD"),
            sceneArtifactCount = 3,
        )!!

        assertTrue(md.indexOf("FIRST") < md.indexOf("SECOND"))
        assertTrue(md.indexOf("SECOND") < md.indexOf("THIRD"))
    }

    /**
     * Headings come from the live beat, never the artifact title. Artifacts are
     * created as "3. The Gate Opens" with the index baked in at draft time, so
     * deleting a beat leaves every later artifact title off by one.
     */
    @Test
    fun `headings number from the current outline position`() {
        val md = ManuscriptCompiler.compile(
            project = project(listOf(beat(1, title = "Arrival"), beat(2, title = "The lantern room"))),
            sceneTexts = scenes("art1" to "a", "art2" to "b"),
            sceneArtifactCount = 2,
        )!!

        assertTrue(md.contains("## 1. Arrival"), md)
        assertTrue(md.contains("## 2. The lantern room"), md)
    }

    @Test
    fun `a beat with no scene is marked, not skipped`() {
        val md = ManuscriptCompiler.compile(
            project = project(listOf(beat(1), beat(2, artifactId = ""), beat(3))),
            sceneTexts = scenes("art1" to "FIRST", "art3" to "THIRD"),
            sceneArtifactCount = 2,
        )!!

        assertTrue(md.contains("## 2. Beat 2"), "the gap keeps its place in the manuscript")
        assertTrue(md.contains("*[not yet written]*"), md)
    }

    /**
     * The reader could not recover the text — the revision pointer dangles, or
     * the row is gone. Emitting nothing here would produce a novel that looks
     * finished and is not.
     */
    @Test
    fun `a beat whose text cannot be recovered is marked`() {
        val md = ManuscriptCompiler.compile(
            project = project(listOf(beat(1), beat(2))),
            sceneTexts = scenes("art1" to "FIRST", "art2" to null),
            sceneArtifactCount = 2,
        )!!

        assertTrue(md.contains("*[scene text unavailable]*"), md)
        assertFalse(md.contains("*[not yet written]*"), "it has an artifact; it is not unwritten")
    }

    /**
     * Needs a second beat with real prose: a project whose *only* scene is blank
     * has nothing to export and correctly compiles to null, which would make
     * this pass for the wrong reason.
     */
    @Test
    fun `a resolved but empty scene is unavailable rather than a heading with no body`() {
        val md = ManuscriptCompiler.compile(
            project = project(listOf(beat(1), beat(2))),
            sceneTexts = scenes("art1" to "real prose", "art2" to "   "),
            sceneArtifactCount = 2,
        )!!

        assertTrue(md.contains("*[scene text unavailable]*"), md)
        assertTrue(md.contains("real prose"), "the healthy scene is untouched")
    }

    /**
     * The button is disabled in this state, so reaching here means the two
     * predicates disagreed. Returning null rather than a lone title means the
     * user gets an error instead of an empty file either way.
     */
    @Test
    fun `nothing drafted compiles to null, not a title with no body`() {
        assertNull(
            ManuscriptCompiler.compile(
                project = project(listOf(beat(1, artifactId = ""), beat(2, artifactId = ""))),
                sceneTexts = emptyMap(),
                sceneArtifactCount = 0,
            ),
        )
    }

    @Test
    fun `an empty outline compiles to null`() {
        assertNull(ManuscriptCompiler.compile(project(emptyList()), emptyMap(), 0))
    }

    /**
     * Re-planning an outline replaces every beat with a fresh one carrying a
     * blank artifactId, orphaning the scenes already written. Without this
     * footer the export reads as total data loss while the prose sits safely in
     * the artifact table.
     */
    @Test
    fun `orphaned scenes are named in a footer`() {
        val md = ManuscriptCompiler.compile(
            project = project(listOf(beat(1))),
            sceneTexts = scenes("art1" to "FIRST"),
            sceneArtifactCount = 4,
        )!!

        assertTrue(md.contains("3"), "the count of scenes outside the outline appears")
        assertTrue(md.lowercase().contains("not in the current outline"), md.takeLast(300))
    }

    @Test
    fun `no footer when every scene is accounted for`() {
        val md = ManuscriptCompiler.compile(
            project = project(listOf(beat(1), beat(2))),
            sceneTexts = scenes("art1" to "a", "art2" to "b"),
            sceneArtifactCount = 2,
        )!!

        assertFalse(md.lowercase().contains("not in the current outline"), md.takeLast(300))
    }

    /**
     * A stale count must never produce "-1 scenes exist" — the artifact table
     * and the outline are read separately and can disagree in either direction.
     */
    @Test
    fun `fewer artifacts than beats does not produce a negative footer`() {
        val md = ManuscriptCompiler.compile(
            project = project(listOf(beat(1), beat(2))),
            sceneTexts = scenes("art1" to "a", "art2" to "b"),
            sceneArtifactCount = 0,
        )!!

        assertFalse(md.contains("-"), "no negative orphan count")
    }

    @Test
    fun `the file name is a slug of the project name`() {
        assertEquals("the-lighthouse", ManuscriptCompiler.slug("The Lighthouse"))
        assertEquals("a-keeper-s-tale", ManuscriptCompiler.slug("A keeper's tale"))
        assertEquals("manuscript", ManuscriptCompiler.slug("   "), "a blank name still yields a filename")
        assertTrue(ManuscriptCompiler.slug("x".repeat(200)).length <= 60, "slugs stay filesystem-sane")
    }
}
