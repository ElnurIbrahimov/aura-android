package com.aura.creative

import com.aura.security.SecureDataStore
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Craft guidance as data the user (and the evolution system) can edit, rather
 * than constants only a recompile can change.
 *
 * The bodies still live in [GenreCraftPrompts] — it is the seed source and the
 * fallback, so a deleted or unreadable skill degrades to the behaviour that
 * shipped rather than to an empty craft section.
 */
class CraftSkillsTest {

    private fun newStore(): SkillsStore {
        val secure = mockk<SecureDataStore>(relaxed = true)
        coEvery { secure.getString(any()) } returns null
        coEvery { secure.putString(any(), any()) } returns Unit
        return SkillsStore(secure)
    }

    private suspend fun seededStore(): SkillsStore =
        newStore().also { it.seedBuiltins(CraftSkills.seeds()) }

    // ---------------------------------------------------------------- seeding

    @Test
    fun `it seeds one skill per genre and one per mode`() = runTest {
        val store = seededStore()

        // Five genres carry craft in GenreCraftPrompts; every CreativeMode has
        // mode guidance. Derived rather than hardcoded so adding either kind of
        // prompt does not silently leave it unseeded.
        val expected = CraftSkills.seeds().size
        assertEquals(expected, store.skills.value.size)
        assertTrue(store.skills.value.all { it.builtin }, "every seeded craft skill is a builtin")
        assertNotNull(store.findByName(CraftSkills.templateSkillName("novel")))
        assertNotNull(store.findByName(CraftSkills.modeSkillName(CreativeMode.BRAINSTORM)))
    }

    @Test
    fun `seeding twice adds nothing the second time`() = runTest {
        val store = seededStore()
        val first = store.skills.value.size

        store.seedBuiltins(CraftSkills.seeds())

        assertEquals(first, store.skills.value.size)
    }

    /**
     * The reason seeding keys on absent *names* rather than an empty store: a
     * later version that adds a craft prompt must seed only that one, without
     * reverting anything the author has since rewritten.
     */
    @Test
    fun `seeding preserves an edited builtin and still adds a genuinely new one`() = runTest {
        val store = seededStore()
        val novel = store.findByName(CraftSkills.templateSkillName("novel"))!!
        store.update(novel.withBody("MY OWN RULES"))

        val withNewcomer = CraftSkills.seeds() +
            Skill(name = "craft-newcomer", description = "added in a later version", body = "new", builtin = true)
        store.seedBuiltins(withNewcomer)

        assertEquals("MY OWN RULES", store.findByName(CraftSkills.templateSkillName("novel"))?.body)
        assertNotNull(store.findByName("craft-newcomer"))
    }

    // ------------------------------------------------------------ the contract

    @Test
    fun `a builtin cannot be removed`() = runTest {
        val store = seededStore()
        val novel = store.findByName(CraftSkills.templateSkillName("novel"))!!

        store.remove(novel.id)

        assertNotNull(store.findByName(CraftSkills.templateSkillName("novel")), "builtins are editable, not deletable")
    }

    @Test
    fun `a user-authored skill can still be removed`() = runTest {
        val store = seededStore()
        store.add(Skill(name = "mine", description = "", body = "x"))
        val mine = store.findByName("mine")!!

        store.remove(mine.id)

        assertNull(store.findByName("mine"))
    }

    @Test
    fun `resetting a builtin restores the shipped body`() = runTest {
        val store = seededStore()
        val name = CraftSkills.templateSkillName("novel")
        store.update(store.findByName(name)!!.withBody("MY OWN RULES"))

        store.resetBuiltin(name, CraftSkills.seeds())

        assertEquals(GenreCraftPrompts.NOVEL_CRAFT, store.findByName(name)?.body)
    }

    // --------------------------------------------------------------- resolving

    @Test
    fun `resolution prefers the stored skill over the constant`() = runTest {
        val store = seededStore()
        val name = CraftSkills.templateSkillName("novel")
        store.update(store.findByName(name)!!.withBody("MY OWN RULES"))

        assertEquals("MY OWN RULES", CraftResolver(store).forTemplate("novel"))
    }

    /**
     * The fallback is the whole reason `GenreCraftPrompts` stays. An install
     * whose store is empty — first run before seeding, or a corrupt blob the
     * store discarded — must draft with the craft that shipped, not with none.
     */
    @Test
    fun `resolution falls back to the constant when nothing is stored`() = runTest {
        val resolver = CraftResolver(newStore())

        assertEquals(GenreCraftPrompts.NOVEL_CRAFT, resolver.forTemplate("novel"))
        assertEquals(GenreCraftPrompts.forMode(CreativeMode.DRAFT), resolver.forMode(CreativeMode.DRAFT))
    }

    @Test
    fun `an unknown template resolves to null, as it did before`() = runTest {
        assertNull(CraftResolver(seededStore()).forTemplate("no-such-template"))
    }

    @Test
    fun `mode resolution prefers the stored skill`() = runTest {
        val store = seededStore()
        val name = CraftSkills.modeSkillName(CreativeMode.DRAFT)
        store.update(store.findByName(name)!!.withBody("DRAFT MY WAY"))

        assertEquals("DRAFT MY WAY", CraftResolver(store).forMode(CreativeMode.DRAFT))
    }

    @Test
    fun `an emptied builtin falls back rather than sending a blank craft section`() = runTest {
        val store = seededStore()
        val name = CraftSkills.templateSkillName("novel")
        store.update(store.findByName(name)!!.withBody("   "))

        assertEquals(GenreCraftPrompts.NOVEL_CRAFT, CraftResolver(store).forTemplate("novel"))
    }

    @Test
    fun `every seeded body matches the constant it was seeded from`() = runTest {
        val store = seededStore()
        val resolver = CraftResolver(store)

        for (template in WritingTemplates.all) {
            assertEquals(
                GenreCraftPrompts.forTemplate(template.id),
                resolver.forTemplate(template.id),
                "seeded craft for ${template.id} must be byte-identical to what shipped",
            )
        }
        for (mode in CreativeMode.entries) {
            assertEquals(GenreCraftPrompts.forMode(mode), resolver.forMode(mode), "seeded mode craft for $mode")
        }
    }

    @Test
    fun `seeded names and descriptions satisfy Skill's own constraints`() {
        for (seed in CraftSkills.seeds()) {
            assertTrue(seed.name.length <= 80, "${seed.name} exceeds the 80-char name limit")
            assertTrue(seed.description.length <= 240, "${seed.name}'s description exceeds 240 chars")
            assertTrue(seed.description.isNotBlank(), "${seed.name} needs a description — it is the routing signal")
            assertFalse(seed.body.isBlank(), "${seed.name} has an empty body")
        }
    }
}
