package com.github.halfdeadgames.hdgife

import com.bladecoder.ink.runtime.HdgifeStory
import com.github.halfdeadgames.hdgife.compiler.InkCompileException
import com.github.halfdeadgames.hdgife.compiler.InkSource
import com.github.halfdeadgames.hdgife.story.IncompatibleLibraryException
import com.github.halfdeadgames.hdgife.story.SaveStateException
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.StoryEvent
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.StoryPathException
import com.github.halfdeadgames.hdgife.story.StoryStepException
import com.github.halfdeadgames.hdgife.story.TextMarker
import com.github.halfdeadgames.hdgife.story.TextSpan
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pre-1.0 API review: error model, final result classes, variables, replaceStory, library split and version. */
class ApiReviewTest {
    // ---- the bundled libraries ----

    private fun resource(path: String) =
        ApiReviewTest::class.java.classLoader.getResourceAsStream(path)!!.use { String(it.readBytes(), Charsets.UTF_8) }

    private fun functionsIn(text: String): Set<String> =
        Regex("(?m)^(?:===\\s*function|EXTERNAL)\\s+(\\w+)").findAll(text).map { it.groupValues[1] }.toSet()

    @Test
    fun `the reserved names are generated from both files`() {
        val core = functionsIn(resource("hdgife/hdgife.ink"))
        val util = functionsIn(resource("hdgife/hdgife_util.ink"))
        assertEquals(core + util, HdgifeInk.LIBRARY_FUNCTIONS.toSet())
        assertEquals(HdgifeInk.LIBRARY_FUNCTIONS.size, HdgifeInk.LIBRARY_FUNCTIONS.toSet().size, "no name twice")
        assertTrue(core.intersect(util).isEmpty())
        for (name in listOf("chance", "clamp", "avg2", "avg3", "blend", "invert", "scale100", "emit", "emit1", "emit2", "emit3", "speak", "stat", "fmt")) {
            assertTrue(name in HdgifeInk.LIBRARY_FUNCTIONS, name)
        }
    }

    @Test
    fun `the version constant matches the one in hdgife ink`() {
        val declared = Regex("(?m)^CONST\\s+hdgife_version\\s*=\\s*(\\d+)").find(resource("hdgife/hdgife.ink"))!!.groupValues[1].toInt()
        assertEquals(declared, HdgifeInk.VERSION)
    }

    @Test
    fun `the util file works alone`() {
        val story = compileInk("INCLUDE hdgife_util.ink\n{clamp(15, 0, 10)} {avg2(2, 4)} {invert(30)} {chance(100)}")
        assertEquals("10 3.0 70 true", story.Continue().trim())
    }

    @Test
    fun `the util file works next to the core file in either order and nothing is declared twice`() {
        for (head in listOf("INCLUDE hdgife.ink\nINCLUDE hdgife_util.ink", "INCLUDE hdgife_util.ink\nINCLUDE hdgife.ink")) {
            val m = StoryManager(compileInk("$head\n{clamp(15, 0, 10)} {fmt(avg2(1, 2), 1, \"round\")}"))
            assertEquals(listOf("10 1.5"), m.next().texts())
        }
    }

    @Test
    fun `the util file is included once even from several sources`() {
        val story = compiler.compile(
            listOf(
                InkSource("main.ink", "INCLUDE hdgife_util.ink\nINCLUDE other.ink\n{clamp(5, 0, 3)}"),
                InkSource("other.ink", "INCLUDE hdgife_util.ink\n"),
            ),
        )
        assertEquals("3", story.Continue().trim())
    }

    @Test
    fun `a source cannot take the name of a bundled file`() {
        assertFailsWith<IllegalArgumentException> { compiler.compile(listOf(InkSource("hdgife_util.ink", "x"))) }
    }

    @Test
    fun `the old emit names are gone`() {
        assertFailsWith<InkCompileException> { compileLib("~ emit0(\"x\")\nHi") }
        val m = managerOf("~ emit(\"a\")\n~ emit1(\"b\", 1)\n~ emit2(\"c\", 1, 2)\n~ emit3(\"d\", 1, 2, 3)\nHi")
        val step = m.next()
        assertEquals(listOf("a", "b", "c", "d"), step.events.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), step.events.map { it.args.size })
    }

    @Test
    fun `floor_text and round_text are not in the library`() {
        assertFailsWith<InkCompileException> { compileLib("{floor_text(2.5)}") }
        assertFailsWith<InkCompileException> { compileLib("{round_text(2.5, 1)}") }
    }

    // ---- error model ----

    @Test
    fun `every deliberate exception is an HdgifeException`() {
        assertFailsWith<HdgifeException> { compiler.loadCompiled("not json") }
        assertFailsWith<HdgifeException> { managerOf("Hi").loadState("garbage") }
        assertFailsWith<HdgifeException> { managerOf("Hi", lib = false).seedRandom(1) }
        assertFailsWith<HdgifeException> { managerOf("Hi").beginScene("nowhere") }
        val boom = managerOf("EXTERNAL boom()\nHi {boom()}\n=== function boom() ===\n~ return 0")
        boom.bindFunction("boom") { throw IllegalStateException("x") }
        assertFailsWith<HdgifeException> { boom.next() }
    }

    @Test
    fun `programmer errors stay IllegalArgument and IllegalState`() {
        val m = managerOf("Hi\n* [Go] -> END")
        m.next()
        assertFailsWith<IllegalArgumentException> { m.choose(9) }
        assertFailsWith<IllegalArgumentException> { m.setVariable("nope", 1) }
        assertFailsWith<IllegalArgumentException> { m.observe("nope") { _, _ -> } }
    }

    @Test
    fun `beginScene wraps ink's path error and leaves the speaker`() {
        val m = managerOf("=== scene ===\nHello\n-> END")
        m.speaker = "keep"
        val ex = assertFailsWith<StoryPathException> { m.beginScene("nope.missing", "other") }
        assertEquals("nope.missing", ex.path)
        assertIs<com.bladecoder.ink.runtime.StoryException>(ex.cause)
        assertEquals("keep", m.speaker)
        m.beginScene("scene", "guard")
        assertEquals("guard", m.speaker)
        assertEquals(listOf("Hello"), m.next().texts())
    }

    @Test
    fun `a save from a newer version and a broken envelope are SaveStateExceptions`() {
        val m = managerOf("Hi")
        val ex = assertFailsWith<SaveStateException> { m.loadState("{\"v\":99,\"ink\":\"{}\"}") }
        assertContains(ex.message.orEmpty(), "99")
        assertFailsWith<SaveStateException> { m.loadState("{\"v\":1}\n") }.also { assertIs<HdgifeException>(it) }
    }

    @Test
    fun `an incompatible library carries the versions`() {
        val old = compileInk("VAR hdgife_speaker = \"\"\nHi")
        val ex = assertFailsWith<IncompatibleLibraryException> { StoryManager(old) }
        assertEquals(0, ex.found)
        assertEquals(HdgifeInk.VERSION, ex.expected)
        val missing = assertFailsWith<IncompatibleLibraryException> { managerOf("Hi", lib = false).seedRandom(1) }
        assertContains(missing.message.orEmpty(), "INCLUDE hdgife.ink")
    }

    @Test
    fun `the log hears about a refused library`() {
        val sink = LogSink()
        val m = managerOf("Hi")
        m.log = sink
        assertFailsWith<IncompatibleLibraryException> { m.replaceStory(compileInk("VAR hdgife_speaker = \"\"\nHi")) }
        assertContains(sink.messages.joinToString(), "out of date")
    }

    // ---- variables ----

    private val varInk = "VAR hp = 10\nVAR rate = 1.5\nVAR name = \"Ann\"\nHi"

    @Test
    fun `getVariable reads untyped and typed`() {
        val m = managerOf(varInk)
        assertEquals(10, m.getVariable("hp"))
        assertNull(m.getVariable("nope"))
        assertEquals(10, m.getVariable("hp", Int::class))
        assertEquals(10.0, m.getVariable("hp", Double::class))
        assertEquals(10L, m.getVariable("hp", Long::class))
        assertEquals(1.5, m.getVariable("rate", Double::class))
        assertNull(m.getVariable("nope", Int::class))
        assertFailsWith<IllegalArgumentException> { m.getVariable("name", Int::class) }
    }

    @Test
    fun `variableAs is the inline form`() {
        val m = managerOf(varInk)
        assertEquals(10f, m.variableAs<Float>("hp"))
        assertEquals("Ann", m.variableAs<String>("name"))
        assertNull(m.variableAs<Int>("nope"))
        assertFailsWith<IllegalArgumentException> { m.variableAs<String>("hp") }
    }

    // ---- replaceStory ----

    @Test
    fun `replaceStory keeps observers, functions and the speaker`() {
        val m = managerOf("EXTERNAL add(a)\nVAR hp = 1\n~ hp = 4\n{add(1)}\n=== function add(a) ===\n~ return a")
        m.bindFunction("add") { it.int(0) + 100 }
        var seen = 0
        m.observe("hp") { _, new -> seen = new as Int }
        m.speaker = "p"
        val next = compileLib("EXTERNAL add(a)\nVAR hp = 1\n~ hp = 7\n{add(2)}\n=== function add(a) ===\n~ return a")
        m.replaceStory(next)
        assertSame(next, m.story)
        assertEquals("p", m.speaker)
        assertEquals(listOf("102"), m.next().texts())
        assertEquals(7, seen)
    }

    @Test
    fun `a refused replacement leaves the story`() {
        val m = managerOf("Hi")
        val before = m.story
        assertFailsWith<IncompatibleLibraryException> { m.replaceStory(compileInk("VAR hdgife_speaker = \"\"\nHi")) }
        assertSame(before, m.story)
    }

    private fun assertSame(expected: Any?, actual: Any?) = kotlin.test.assertSame(expected, actual)

    @Test
    fun `the constructor takes an HdgifeStory and the plain one has its own entry point`() {
        val hdgife: HdgifeStory = compileLib("{stat_of(\"p\", \"hp\")}")
        assertTrue(StoryManager(hdgife, MapStats().also { it["p", "hp"] = 3 }).lookaheadSafe)
        val plain = StoryManager.forPlainStory(
            com.bladecoder.ink.runtime.Story(compileLib("{stat_of(\"p\", \"hp\")}").toJson()),
            MapStats().also { it["p", "hp"] = 3 },
        )
        assertFalse(plain.lookaheadSafe)
        assertEquals(listOf("3"), plain.next().texts())
    }

    // ---- final result classes ----

    @Test
    fun `result classes have value equality and a readable toString`() {
        val a = managerOf("Hello <b>there</b>\n~ emit1(\"e\", 1)\n* [Go] -> END").next()
        val b = managerOf("Hello <b>there</b>\n~ emit1(\"e\", 1)\n* [Go] -> END").next()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a.lines.single(), b.lines.single())
        assertEquals(a.choices.single(), b.choices.single())
        assertEquals(a.events.single(), b.events.single())
        assertContains(a.toString(), "Hello there")
        assertContains(a.events.single().toString(), "name=e")
        val c = managerOf("Other\n-> END").next()
        assertNotEquals(a, c)
    }

    @Test
    fun `style, span, marker and event compare by value`() {
        assertEquals(Style("color", "red"), Style("color", "red"))
        assertEquals(Style("color", "red").hashCode(), Style("color", "red").hashCode())
        assertNotEquals(Style("color", "red"), Style("color", "blue"))
        assertEquals(TextSpan("a", listOf(Style("b"))), TextSpan("a", listOf(Style("b"))))
        assertEquals(TextMarker("pause", 3, "0.5"), TextMarker("pause", 3, "0.5"))
        assertNotEquals(TextMarker("pause", 3, "0.5"), TextMarker("pause", 4, "0.5"))
        assertEquals(StoryEvent("x", listOf(1), 0, 2), StoryEvent("x", listOf(1), 0, 2))
        assertContains(TextMarker("pause", 3, "0.5").toString(), "offset=3")
    }

    @Test
    fun `the marker keeps its name, offset, value, params order`() {
        val step = managerOf("Wait<pause=0.5/> here<slam a=1/>").next()
        val markers = step.lines.single().markers
        assertEquals(listOf("pause", "slam"), markers.map { it.name })
        assertEquals(listOf(4, 9), markers.map { it.offset })
        assertEquals("0.5", markers[0].value)
        assertEquals(mapOf("a" to "1"), markers[1].params)
    }

    @Test
    fun `result classes expose no copy and no componentN`() {
        val classes = listOf(
            com.github.halfdeadgames.hdgife.story.DialogStep::class, com.github.halfdeadgames.hdgife.story.DialogLine::class,
            com.github.halfdeadgames.hdgife.story.DialogChoice::class, StoryEvent::class,
            com.github.halfdeadgames.hdgife.story.ChoiceRequirement::class, com.github.halfdeadgames.hdgife.story.ValidationIssue::class,
            com.github.halfdeadgames.hdgife.story.StoryReport::class, TextSpan::class, Style::class, TextMarker::class,
        )
        for (type in classes) {
            val methods = type.java.methods.map { it.name }
            assertTrue(methods.none { it == "copy" || it.startsWith("component") }, "${type.simpleName}: $methods")
            assertTrue(java.lang.reflect.Modifier.isFinal(type.java.modifiers), "${type.simpleName} is final")
        }
    }

    @Test
    fun `a StoryStepException is an HdgifeException with the partial step`() {
        val m = managerOf("Line one\n~ boom()\nLine two\n-> END\nEXTERNAL boom()\n=== function boom() ===\n~ return 0")
        m.bindFunction("boom") { error("bang") }
        val ex = assertFailsWith<StoryStepException> { m.next() }
        assertIs<HdgifeException>(ex)
        assertEquals(listOf("Line one"), ex.step.texts())
    }
}
