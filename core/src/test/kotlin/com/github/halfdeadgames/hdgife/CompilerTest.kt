package com.github.halfdeadgames.hdgife

import com.badlogic.gdx.files.FileHandle
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.compiler.InkCompileException
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.compiler.InkSource
import com.github.halfdeadgames.hdgife.story.StoryManager
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CompilerTest {
    private lateinit var dir: File

    @BeforeTest
    fun makeDir() {
        dir = Files.createTempDirectory("hdgife-test").toFile()
    }

    @AfterTest
    fun removeDir() {
        dir.deleteRecursively()
    }

    private fun write(path: String, text: String): File =
        File(dir, path).also { it.parentFile.mkdirs(); it.writeText(text) }

    private fun linesOf(story: Story) = StoryManager(story).next().texts()

    @Test
    fun `compiles a story from a string`() {
        assertEquals(listOf("Hello"), linesOf(compileInk("Hello\n-> END")))
    }

    @Test
    fun `extra sources are appended in the order given`() {
        val root = InkSource("main.ink", "Root")
        val a = InkSource("a.ink", "A")
        val b = InkSource("b.ink", "B")
        assertEquals(listOf("Root", "A", "B"), linesOf(compiler.compile(listOf(root, a, b))))
        assertEquals(listOf("Root", "B", "A"), linesOf(compiler.compile(listOf(root, b, a))))
    }

    @Test
    fun `compile with a main source and extras keeps the order`() {
        val story = compiler.compile(InkSource("m.ink", "M"), listOf(InkSource("x.ink", "X"), InkSource("y.ink", "Y")))
        assertEquals(listOf("M", "X", "Y"), linesOf(story))
    }

    @Test
    fun `an empty source list is rejected`() {
        assertFailsWith<IllegalArgumentException> { compiler.compile(emptyList()) }
    }

    @Test
    fun `duplicate source names are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(listOf(InkSource("a.ink", "A"), InkSource("a.ink", "B")))
        }
    }

    @Test
    fun `the bundled library name is reserved`() {
        assertFailsWith<IllegalArgumentException> { compiler.compile(listOf(InkSource("hdgife.ink", "x"))) }
    }

    @Test
    fun `include resolves relative to the including source and allows parent paths`() {
        val sources = listOf(
            InkSource("main.ink", "INCLUDE scenes/a.ink\nRoot"),
            InkSource("scenes/a.ink", "INCLUDE ../shared/b.ink\nA"),
            InkSource("shared/b.ink", "B"),
        )
        assertEquals(listOf("B", "A", "Root"), linesOf(compiler.compile(sources)))
    }

    @Test
    fun `a source that is both listed and included loads once`() {
        val sources = listOf(
            InkSource("main.ink", "INCLUDE a.ink\nRoot"),
            InkSource("a.ink", "A"),
        )
        assertEquals(listOf("A", "Root"), linesOf(compiler.compile(sources)))
    }

    @Test
    fun `include of an unlisted file is looked up next to a file source`() {
        write("sub/extra.ink", "Extra")
        val main = write("main.ink", "INCLUDE sub/extra.ink\nMain")
        val story = compiler.compile(FileHandle(main))
        assertEquals(listOf("Extra", "Main"), linesOf(story))
    }

    @Test
    fun `include of an unlisted string source names the missing file`() {
        val e = assertFailsWith<InkCompileException> {
            compiler.compile(listOf(InkSource("main.ink", "INCLUDE nowhere.ink\nHi")))
        }
        assertContains(e.message!!, "nowhere.ink")
    }

    @Test
    fun `a syntax error names the source`() {
        val e = assertFailsWith<InkCompileException> {
            compiler.compile(listOf(InkSource("broken.ink", "-> nowhere_knot")))
        }
        assertContains(e.errors.joinToString("\n"), "broken.ink")
    }

    @Test
    fun `a syntax error in an extra source names that source`() {
        val e = assertFailsWith<InkCompileException> {
            compiler.compile(listOf(InkSource("main.ink", "Hi"), InkSource("extra/bad.ink", "-> missing_knot")))
        }
        assertContains(e.message!!, "extra/bad.ink")
    }

    @Test
    fun `a source that cannot be read is reported with its name`() {
        val unreadable = InkSource("main.ink", { error("disk gone") })
        val e = assertFailsWith<InkCompileException> { compiler.compile(listOf(unreadable)) }
        assertEquals("main.ink", e.source)
        assertContains(e.message!!, "disk gone")
    }

    @Test
    fun `an include that cannot be read is reported with its name`() {
        val bad = InkSource("bad.ink", { error("no access") })
        val e = assertFailsWith<InkCompileException> {
            compiler.compile(listOf(InkSource("main.ink", "INCLUDE bad.ink\nHi"), bad))
        }
        assertEquals("bad.ink", e.source)
    }

    @Test
    fun `compileFolder puts main ink first and sorts the rest by path`() {
        write("main.ink", "Root")
        write("b.ink", "B")
        write("a.ink", "A")
        write("sub/c.ink", "C")
        val story = compiler.compileFolder(FileHandle(dir))
        assertEquals(listOf("Root", "A", "B", "C"), linesOf(story))
    }

    @Test
    fun `compileFolder without main ink uses the first path as root`() {
        write("010_extra.ink", "Second")
        write("000_base.ink", "First")
        assertEquals(listOf("First", "Second"), linesOf(compiler.compileFolder(FileHandle(dir))))
    }

    @Test
    fun `compileFolder can skip subfolders`() {
        write("main.ink", "Root")
        write("sub/c.ink", "C")
        assertEquals(listOf("Root"), linesOf(compiler.compileFolder(FileHandle(dir), recursive = false)))
    }

    @Test
    fun `compileFolder with no ink files fails`() {
        write("readme.txt", "nothing")
        assertFailsWith<InkCompileException> { compiler.compileFolder(FileHandle(dir)) }
    }

    @Test
    fun `compile reads file handles as UTF-8`() {
        val main = write("main.ink", "Café — ok")
        assertEquals(listOf("Café — ok"), linesOf(compiler.compile(FileHandle(main))))
    }

    @Test
    fun `warnings go to the log`() {
        val sink = LogSink()
        val service = InkCompilerService().also { it.log = sink }
        service.compile(listOf(InkSource("main.ink", "TODO: later\nHi")))
        assertContains(sink.messages.joinToString(), "later")
    }

    @Test
    fun `loadCompiled gives a story that plays the same as the original`() {
        val original = compileLib("VAR n = 4\nHello {n}\n* [Pick] Done -> END")
        val loaded = compiler.loadCompiled(original.toJson())
        val a = StoryManager(original).next()
        val b = StoryManager(loaded).next()
        assertEquals(a.texts(), b.texts())
        assertEquals(a.choices.map { it.text }, b.choices.map { it.text })
    }

    @Test
    fun `loadCompiled keeps the stat functions working`() {
        val stats = MapStats().also { it["p", "hp"] = 9 }
        val loaded = compiler.loadCompiled(compileLib("{stat_of(\"p\", \"hp\")}").toJson())
        assertEquals(listOf("9"), StoryManager(loaded, stats).next().texts())
    }

    @Test
    fun `loadCompiled rejects garbage`() {
        val e = assertFailsWith<InkCompileException> { compiler.loadCompiled("not json") }
        assertNotNull(e.cause)
        assertNull(e.source)
    }
}
