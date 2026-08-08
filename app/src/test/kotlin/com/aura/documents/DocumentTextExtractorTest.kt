package com.aura.documents

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentTextExtractorTest {
    @Test
    fun `docx parser preserves paragraphs and decoded text`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Aura &amp; the glass city</w:t></w:r></w:p>
                <w:p><w:r><w:t>Second paragraph.</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val text = DocumentTextParsers.extractDocx(bytes)

        assertEquals("Aura & the glass city\n\nSecond paragraph.", text)
    }

    /**
     * A .docx small enough to clear `MAX_FILE_BYTES` can still hold a
     * `word/document.xml` that expands to gigabytes — WordprocessingML deflates
     * at roughly 344:1. Before the bound, the parser read the whole entry into
     * one array and took the process down with it.
     */
    @Test
    fun `docx parser refuses an entry that expands past the bound`() {
        val unit = "<w:p><w:r><w:t>A</w:t></w:r></w:p>"
        val repeats = (DocumentTextParsers.MAX_DOCX_XML_BYTES / unit.length) + 1_000
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("""<?xml version="1.0"?><w:document><w:body>""".toByteArray())
                repeat(repeats) { zip.write(unit.toByteArray()) }
                zip.write("</w:body></w:document>".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        // The hostile file itself is tiny — that is the whole point.
        assertTrue(
            bytes.size < DocumentTextExtractor.MAX_FILE_BYTES,
            "compressed fixture (${bytes.size} B) must pass the input-size gate",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            DocumentTextParsers.extractDocx(bytes)
        }
        assertTrue(
            failure.message.orEmpty().contains("too large to read safely"),
            "expected a size-bound message, got: ${failure.message}",
        )
    }
}