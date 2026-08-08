package com.aura.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name a saved or shared image gets.
 *
 * It ends up in the user's gallery next to their photos, so it has to be a
 * legal file name on every device and it has to be distinguishable — two
 * images generated seconds apart would otherwise collide into "image.png"
 * and "image (1).png" with nothing to tell them apart. The provider's own
 * opaque hash is ugly but it is exactly the part that differs.
 */
class ImageFileNameTest {

    @Test
    fun `a provider url keeps its hash`() {
        assertEquals(
            "aura-b8d74297145641dc871b804dca429567.png",
            imageFileName("https://platform-outputs.agnes-ai.space/images/t2i/b8d74297145641dc871b804dca429567.png"),
        )
    }

    @Test
    fun `a query string is not part of the name`() {
        // Signed URLs carry the whole signature after the ?; it is not a name.
        assertEquals(
            "aura-cat.png",
            imageFileName("https://cdn.example.com/cat.png?X-Amz-Signature=deadbeef&expires=99"),
        )
    }

    @Test
    fun `a locally decoded base64 image gets a name too`() {
        // ImageGenTool writes inline b64_json responses to the cache and hands
        // back a file:// URI, which never sees the network.
        assertEquals("aura-img_4821.png", imageFileName("file:///data/user/0/com.aura/cache/img_4821.png"))
    }

    @Test
    fun `an extensionless url still produces a png name`() {
        assertEquals("aura-9f3a2b.png", imageFileName("https://images.example.com/9f3a2b"))
    }

    @Test
    fun `characters that are not legal in a file name are dropped`() {
        // The `?` truncates first as a query separator, so `e.png` is gone
        // before the filter ever runs — the stem is what precedes it.
        val name = imageFileName("https://x.test/a b:c*d?e.png")
        assertEquals("aura-abcd.png", name)
        assertTrue(name.none { it in "/\\:*?\"<>| " })
    }

    @Test
    fun `a url with nothing usable falls back rather than producing a dotfile`() {
        assertEquals("aura-image.png", imageFileName("https://example.com/"))
        assertEquals("aura-image.png", imageFileName(""))
    }

    @Test
    fun `an absurdly long segment is truncated`() {
        val name = imageFileName("https://x.test/${"a".repeat(400)}.png")
        // "aura-" + 48 + ".png"
        assertEquals(57, name.length)
    }
}
