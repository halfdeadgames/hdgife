package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.Tags
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagReadersTest {
    private fun tags(vararg raw: String) = Tags.parse(raw.toList())

    @Test
    fun `int reads whole numbers and nothing else`() {
        val parsed = tags("a: 12", "b: -3", "c: +4", "d: 1.5", "e: x", "f:", "g: 99999999999", "h: 1 2", "i: ١٢")
        assertEquals(12, parsed.int("a"))
        assertEquals(-3, parsed.int("b"))
        assertEquals(4, parsed.int("c"))
        for (key in listOf("d", "e", "f", "g", "h", "i", "missing")) assertNull(parsed.int(key), key)
    }

    @Test
    fun `float reads decimals with a point and rejects the rest`() {
        val parsed = tags("a: 2.5", "b: -.25", "c: 7", "d: 1e3", "e: 1,5", "f: NaN", "g: Infinity", "h: 1f", "i: 0x1p3", "j: 1e999", "k: .")
        assertEquals(2.5f, parsed.float("a"))
        assertEquals(-0.25f, parsed.float("b"))
        assertEquals(7f, parsed.float("c"))
        assertEquals(1000f, parsed.float("d"))
        for (key in listOf("e", "f", "g", "h", "i", "j", "k", "missing")) assertNull(parsed.float(key), key)
    }

    @Test
    fun `bool reads true and false in any case and 1 and 0`() {
        val parsed = tags("a: true", "b: FALSE", "c: 1", "d: 0", "e: yes", "f", "g: 2")
        assertEquals(true, parsed.bool("a"))
        assertEquals(false, parsed.bool("b"))
        assertEquals(true, parsed.bool("c"))
        assertEquals(false, parsed.bool("d"))
        for (key in listOf("e", "f", "g", "missing")) assertNull(parsed.bool(key), key)
    }

    @Test
    fun `get gives the value, empty for a bare tag, null when missing`() {
        val parsed = tags("mood: warm", "shout")
        assertEquals("warm", parsed["mood"])
        assertEquals("", parsed["shout"])
        assertNull(parsed["nope"])
    }

    @Test
    fun `default overloads fall back when missing or unparseable`() {
        val parsed = tags("n: 5", "bad: x", "f: 0.5", "b: true", "s: hi")
        assertEquals(5, parsed.int("n", 9))
        assertEquals(9, parsed.int("bad", 9))
        assertEquals(9, parsed.int("missing", 9))
        assertEquals(0.5f, parsed.float("f", 1f))
        assertEquals(1f, parsed.float("bad", 1f))
        assertEquals(true, parsed.bool("b", false))
        assertEquals(false, parsed.bool("bad", false))
    }

    @Test
    fun `the last of a repeated key is read`() {
        assertEquals(2, tags("n: 1", "n: 2").int("n"))
    }

    @Test
    fun `parsing ignores the default locale`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val parsed = tags("f: 2.5", "g: 2,5", "b: TRUE")
            assertEquals(2.5f, parsed.float("f"))
            assertNull(parsed.float("g"))
            assertEquals(true, parsed.bool("b"))
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(true, tags("b: TRUE", "c: True").bool("b"))
            assertEquals(true, tags("c: True").bool("c"))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun `readers work on the tags of a real line`() {
        val tags = managerOf("Hello # volume: 0.5 # loud: true # count: 3").next().lines.single().tags
        assertEquals(0.5f, tags.float("volume"))
        assertEquals(true, tags.bool("loud"))
        assertEquals(3, tags.int("count"))
    }
}
