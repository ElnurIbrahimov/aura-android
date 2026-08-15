package com.aura.creative

import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aura's craft guidance, as skills the author can read and edit.
 *
 * [GenreCraftPrompts] holds several hundred lines of genre and mode technique
 * compiled into the binary, where nobody but a recompile could change it — and
 * `SkillsStore`, built to hold exactly this shape of thing, shipped empty. This
 * object is the bridge: it turns each prompt into a seeded [Skill], so the
 * guidance becomes something the author can rewrite and the evolution system's
 * `PATCH_SKILL` action can finally act on.
 *
 * The constants stay. They are the seed source and the fallback — see
 * [CraftResolver] — so a deleted, blank or unreadable skill degrades to the
 * craft that shipped rather than to an empty section in the prompt.
 */
object CraftSkills {

    /** Genre craft, one per writing template that has any. */
    const val TEMPLATE_PREFIX = "craft-"

    /** Mode craft, layered on top of the genre prompt. */
    const val MODE_PREFIX = "craft-mode-"

    fun templateSkillName(templateId: String): String = "$TEMPLATE_PREFIX$templateId"

    fun modeSkillName(mode: CreativeMode): String = "$MODE_PREFIX${mode.name.lowercase()}"

    /**
     * Every craft prompt that exists, as a builtin skill.
     *
     * Derived from [WritingTemplates.all] and [CreativeMode.entries] rather than
     * listed by hand, so a genre or mode added later cannot be left unseeded —
     * and `CraftSkillsTest` counts against this list for the same reason.
     */
    fun seeds(): List<Skill> {
        val templates = WritingTemplates.all.mapNotNull { template ->
            val body = GenreCraftPrompts.forTemplate(template.id) ?: return@mapNotNull null
            Skill(
                name = templateSkillName(template.id),
                description = "Craft technique for writing a ${template.name.lowercase()}. " +
                    "Applied automatically to every scene of a ${template.name.lowercase()} project.",
                body = body,
                builtin = true,
            )
        }
        val modes = CreativeMode.entries.map { mode ->
            Skill(
                name = modeSkillName(mode),
                description = "How to behave in ${mode.label} mode. Layered on top of the genre craft.",
                body = GenreCraftPrompts.forMode(mode),
                builtin = true,
            )
        }
        return templates + modes
    }
}

/**
 * Reads craft guidance, preferring the author's version over the shipped one.
 *
 * Every lookup falls back to [GenreCraftPrompts]. That is deliberate and it is
 * why the constants were not deleted: an install that has not seeded yet, a
 * blob the store discarded as corrupt, or a body the author emptied must all
 * draft with the craft that shipped. A creative engine silently writing with no
 * craft guidance at all is worse than one ignoring an edit.
 */
@Singleton
class CraftResolver @Inject constructor(
    private val skillsStore: SkillsStore,
) {
    /** Genre craft for [templateId], or null when the template has none — as before. */
    suspend fun forTemplate(templateId: String): String? {
        val shipped = GenreCraftPrompts.forTemplate(templateId) ?: return null
        return stored(CraftSkills.templateSkillName(templateId)) ?: shipped
    }

    /** Mode craft for [mode]. Never null; every mode ships with guidance. */
    suspend fun forMode(mode: CreativeMode): String =
        stored(CraftSkills.modeSkillName(mode)) ?: GenreCraftPrompts.forMode(mode)

    private suspend fun stored(name: String): String? {
        skillsStore.awaitLoaded()
        return skillsStore.findByName(name)?.body?.takeIf { it.isNotBlank() }
    }
}
