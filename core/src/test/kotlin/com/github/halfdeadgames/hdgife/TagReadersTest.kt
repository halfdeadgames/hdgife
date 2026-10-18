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
        val t = tags("a: 12", "b: -3", "c: +4", "d: 1.5", "e: x", "f:", "g: 99999999999", "h: 1 2", "i: ١٢")
        assertEquals(12, t.int("a"))
        assertEquals(-3, t.int("b"))
        assertEquals(4, t.int("c"))
        for (key in listOf("d", "e", "f", "g", "h", "i", "missing")) assertNull(t.int(key), key)
    }

    @Test
    fun `float reads decimals with a point and rejects the rest`() {
        val t = tags("a: 2.5", "b: -.25", "c: 7", "d: 1e3", "e: 1,5", "f: NaN", "g: Infinity", "h: 1f", "i: 0x1p3", "j: 1e999", "k: .")
        assertEquals(2.5f, t.float("a"))
        assertEquals(-0.25f, t.float("b"))
        assertEquals(7f, t.float("c"))
        assertEquals(1000f, t.float("d"))
        for (key in listOf("e", "f", "g", "h", "i", "j", "k", "missing")) assertNull(t.float(key), key)
    }

    @Test
    fun `bool reads true and false in any case and 1 and 0`() {
        val t = tags("a: true", "b: FALSE", "c: 1", "d: 0", "e: yes", "f", "g: 2")
        assertEquals(true, t.bool("a"))
        assertEquals(false, t.bool("b"))
        assertEquals(true, t.bool("c"))
        assertEquals(false, t.bool("d"))
        for (key in listOf("e", "f", "g", "missing")) assertNull(t.bool(key), key)
    }

    @Test
    fun `get gives the value, empty for a bare tag, null when missing`() {
        val t = tags("mood: warm", "shout")
        assertEquals("warm", t["mood"])
        assertEquals("", t["shout"])
        assertNull(t["nope"])
    }

    @Test
    fun `default overloads fall back when missing or unparseable`() {
        val t = tags("n: 5", "bad: x", "f: 0.5", "b: true", "s: hi")
        assertEquals(5, t.int("n", 9))
        assertEquals(9, t.int("bad", 9))
        assertEquals(9, t.int("missing", 9))
        assertEquals(0.5f, t.float("f", 1f))
        assertEquals(1f, t.float("bad", 1f))
        assertEquals(true, t.bool("b", false))
        assertEquals(false, t.bool("bad", false))
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
            val t = tags("f: 2.5", "g: 2,5", "b: TRUE")
            assertEquals(2.5f, t.float("f"))
            assertNull(t.float("g"))
            assertEquals(true, t.bool("b"))
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
