package com.github.halfdeadgames.hdgife

import com.badlogic.gdx.files.FileHandle
import com.bladecoder.ink.runtime.HdgifeStory
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.compiler.InkSource
import com.github.halfdeadgames.hdgife.compiler.of
import com.github.halfdeadgames.hdgife.story.Comparison
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.parseStyles
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HelpersTest {
    private fun ink(expression: String): String = compileLib("INCLUDE hdgife_util.ink\n{$expression}").Continue().trim()

    @Test
    fun `avg2 and avg3 do not truncate`() {
        assertEquals("1.5", ink("avg2(1, 2)"))
        assertEquals("2.0", ink("avg3(1, 2, 3)"))
        assertEquals("1.0", ink("avg3(1, 1, 1)"))
        assertEquals("0.6666667", ink("avg3(0, 1, 1)"))
    }

    @Test
    fun `blend weighs two values`() {
        assertEquals("17.5", ink("blend(10, 1, 20, 3)"))
        assertEquals("10.0", ink("blend(10, 1, 20, 0)"))
    }

    @Test
    fun `invert flips a 0 to 100 value`() {
        assertEquals("70", ink("invert(30)"))
        assertEquals("100", ink("invert(0)"))
    }

    @Test
    fun `clamp limits to the range`() {
        assertEquals("3", ink("clamp(5, 0, 3)"))
        assertEquals("0", ink("clamp(-1, 0, 3)"))
        assertEquals("2", ink("clamp(2, 0, 3)"))
    }

    @Test
    fun `scale100 maps a range onto 0 to 100 and clamps`() {
        assertEquals("50.0", ink("scale100(5, 0, 10)"))
        assertEquals("100", ink("scale100(15, 0, 10)"))
        assertEquals("0", ink("scale100(-5, 0, 10)"))
    }

    @Test
    fun `fmt prints whole numbers and decimals in the ink fallback`() {
        assertEquals("2", ink("fmt(2.7, 0, \"floor\")"))
        assertEquals("-3", ink("fmt(-2.1, 0, \"floor\")"))
        assertEquals("3", ink("fmt(2.5, 0, \"round\")"))
        assertEquals("1.3", ink("fmt(1.25, 1, \"round\")"))
        assertEquals("-3", ink("fmt(-2.5, 0, \"round\")"))
    }

    @Test
    fun `floor_text and round_text are gone`() {
        for (name in listOf("floor_text", "round_text")) {
            assertFalse(compileLib("Hi").hasFunction(name), name)
            assertFalse(name in HdgifeInk.libraryFunctions, name)
        }
    }

    private fun draws(seed: Int, pct: Int, count: Int = 40): List<String> {
        val manager = managerOf("INCLUDE hdgife_util.ink\n" + (1..count).joinToString("\n") { "{chance($pct)}" })
        manager.seedRandom(seed)
        return manager.next().texts()
    }

    @Test
    fun `chance is always true at 100 and never at 0`() {
        assertTrue(draws(1, 100).all { it == "true" })
        assertTrue(draws(1, 0).all { it == "false" })
    }

    @Test
    fun `chance is repeatable with a seed and mixes at 50`() {
        val firstDraws = draws(42, 50)
        assertEquals(firstDraws, draws(42, 50))
        assertTrue("true" in firstDraws && "false" in firstDraws)
        assertNotEquals(firstDraws, draws(43, 50))
    }

    private fun <T> assertNotEquals(first: T, second: T) = kotlin.test.assertNotEquals(first, second)

    @Test
    fun `InkSource of reads the file and names it by its normalized path`() {
        val dir = Files.createTempDirectory("hdgife-src").toFile()
        try {
            val file = dir.resolve("a.ink").also { it.writeText("Café", Charsets.UTF_8) }
            val source = InkSource.of(FileHandle(file))
            assertEquals(file.path.replace('\\', '/').trim('/'), source.name.trim('/'))
            assertEquals("Café", source.read())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `InkSource sibling finds files next to it and above it`() {
        val dir = Files.createTempDirectory("hdgife-src").toFile()
        try {
            dir.resolve("sub").mkdirs()
            val main = dir.resolve("sub/main.ink").also { it.writeText("Hi") }
            dir.resolve("sub/b.ink").writeText("VAR b = 1")
            dir.resolve("c.ink").writeText("VAR c = 2")
            val source = InkSource.of(FileHandle(main))
            val sibling = assertNotNull(source.sibling)
            val bSource = assertNotNull(sibling("b.ink"))
            assertEquals("VAR b = 1", bSource.read())
            assertTrue(bSource.name.endsWith("sub/b.ink"))
            val cSource = assertNotNull(sibling("../c.ink"))
            assertEquals("VAR c = 2", cSource.read())
            assertTrue(cSource.name.endsWith("/c.ink") && !cSource.name.contains(".."))
            assertNull(sibling("missing.ink"))
            assertNull(sibling("."), "a directory is not a source")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an InkSource built from text has no siblings`() {
        assertNull(InkSource("x.ink", "Hi").sibling)
    }

    @Test
    fun `HdgifeStory from keeps an HdgifeStory as it is`() {
        val story = compileInk("Hi") as HdgifeStory
        assertSame(story, HdgifeStory.from(story))
    }

    @Test
    fun `HdgifeStory from rebuilds a plain story with fresh state`() {
        val plain = Story(compileInk("VAR n = 0\n~ n++\nHi {n}").toJson())
        plain.Continue()
        val rebuilt = HdgifeStory.from(plain)
        assertNotSame<Story>(plain, rebuilt)
        assertEquals("Hi 1", rebuilt.Continue().trim())
    }

    @Test
    fun `Comparison parses every symbol and nothing else`() {
        assertEquals(Comparison.Greater, Comparison.parse(">"))
        assertEquals(Comparison.GreaterEqual, Comparison.parse(">="))
        assertEquals(Comparison.Less, Comparison.parse("<"))
        assertEquals(Comparison.LessEqual, Comparison.parse("<="))
        assertEquals(Comparison.Equal, Comparison.parse("=="))
        assertEquals(Comparison.NotEqual, Comparison.parse("!="))
        for (bad in listOf("=", "=>", "", " ", "===", "<>", "gt")) assertNull(Comparison.parse(bad), bad)
    }

    @Test
    fun `Comparison tests numbers and flags equality`() {
        assertTrue(Comparison.Greater.test(2.0, 1.0) && !Comparison.Greater.test(1.0, 1.0))
        assertTrue(Comparison.GreaterEqual.test(1.0, 1.0))
        assertTrue(Comparison.Less.test(0.0, 1.0) && !Comparison.Less.test(1.0, 1.0))
        assertTrue(Comparison.LessEqual.test(1.0, 1.0))
        assertTrue(Comparison.Equal.test(1.0, 1.0) && Comparison.NotEqual.test(1.0, 2.0))
        assertTrue(Comparison.Equal.isEquality && Comparison.NotEqual.isEquality)
        assertFalse(Comparison.Greater.isEquality)
    }

    @Test
    fun `an unterminated quote in a style value makes the marker plain text and logs nothing fatal`() {
        val styled = parseStyles("<color=\"red>text", emptyList(), { it }, LogSink())
        assertEquals("<color=\"red>text", styled.text)
        assertTrue(styled.open.isEmpty())
    }

    @Test
    fun `a terminated quote in a style value keeps spaces`() {
        val styled = parseStyles("<tip=\"two words\">x</tip>", emptyList(), { it }, null)
        assertEquals("x", styled.text)
        assertEquals(listOf(Style("tip", "two words")), styled.spans.single().styles)
    }
}
