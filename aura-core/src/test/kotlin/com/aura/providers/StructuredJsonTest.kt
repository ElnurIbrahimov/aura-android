package com.aura.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [StructuredJson.stripFences] replaces four divergent implementations, so these
 * cases are drawn from what each of them could and could not handle:
 *
 *  - `EvolutionPatchAuthor.stripFences` — fences plus an outermost `{`..`}` span
 *  - `LlmWriteGate.extractJson` — three regexes, the last of which
 *    (`\{(.*?)}`, non-greedy) returned a **truncated** object on any nested brace
 *  - `LlmProfileExtractor.extractJsonBlock` — brace-depth counter, the most
 *    correct of the four and the basis for this one
 *  - `KnowledgeGraphTool` — `removeSurrounding`, which silently no-ops unless
 *    *both* delimiters match
 *
 * The nesting and in-string cases are the ones that separate them.
 */
class StructuredJsonTest {

    private fun parse(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(StructuredJson.stripFences(raw)).jsonObject }.getOrNull()

    @Test
    fun `bare object passes through`() {
        assertEquals("""{"store":true}""", StructuredJson.stripFences("""{"store":true}"""))
    }

    @Test
    fun `json fence is stripped`() {
        val raw = "```json\n{\"store\":true}\n```"
        assertEquals(true, parse(raw)!!["store"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `bare fence is stripped`() {
        assertEquals(true, parse("```\n{\"store\":true}\n```")!!["store"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `prose before and after is discarded`() {
        val raw = "Sure! Here is the result:\n\n{\"store\":true}\n\nLet me know if you need more."
        assertEquals(true, parse(raw)!!["store"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `nested objects survive`() {
        // LlmWriteGate's non-greedy `\{(.*?)}` truncated at the FIRST closing
        // brace, producing `{"a":{"b":1}` — unparseable, silently.
        val raw = """{"a":{"b":{"c":1}},"d":2}"""
        val parsed = parse(raw)!!
        assertEquals(2, parsed["d"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            1,
            parsed["a"]!!.jsonObject["b"]!!.jsonObject["c"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `braces inside string values do not end the scan`() {
        // A depth counter that is not string-aware stops at the `}` in the note.
        val raw = """{"note":"closing } brace","ok":true}"""
        val parsed = parse(raw)!!
        assertEquals("closing } brace", parsed["note"]!!.jsonPrimitive.content)
        assertEquals(true, parsed["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `escaped quotes inside strings do not end the scan`() {
        val raw = """{"note":"she said \"hi\" and { left","ok":true}"""
        val parsed = parse(raw)!!
        assertEquals("""she said "hi" and { left""", parsed["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trailing prose after the object is discarded`() {
        val raw = """{"store":true} — hope that helps! {not json}"""
        assertEquals("""{"store":true}""", StructuredJson.stripFences(raw))
    }

    @Test
    fun `a top-level array is recovered`() {
        assertEquals("""[{"a":1},{"a":2}]""", StructuredJson.stripFences("""prefix [{"a":1},{"a":2}] suffix"""))
    }

    @Test
    fun `unbalanced input returns the trimmed text rather than throwing`() {
        // The caller's parse then fails on something it can log, which is
        // strictly better than this function inventing a value.
        val raw = """{"store":true"""
        assertEquals(raw, StructuredJson.stripFences(raw))
        assertEquals(null, parse(raw))
    }

    @Test
    fun `text with no json at all returns the trimmed text`() {
        assertEquals("I could not do that.", StructuredJson.stripFences("  I could not do that.  "))
    }

    @Test
    fun `fence with language tag and no newline`() {
        assertEquals("""{"a":1}""", StructuredJson.stripFences("""```json{"a":1}```"""))
    }

    @Test
    fun `uppercase JSON fence is stripped`() {
        assertEquals("""{"a":1}""", StructuredJson.stripFences("```JSON\n{\"a\":1}\n```"))
    }
}
