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

class ApiReviewTest {
    private fun resource(path: String) =
        ApiReviewTest::class.java.classLoader.getResourceAsStream(path)!!.use { String(it.readBytes(), Charsets.UTF_8) }

    private fun functionsIn(text: String): Set<String> =
        Regex("(?m)^(?:===\\s*function|EXTERNAL)\\s+(\\w+)").findAll(text).map { it.groupValues[1] }.toSet()

    @Test
    fun `the reserved names are generated from both files`() {
        val core = functionsIn(resource("hdgife/hdgife.ink"))
        val util = functionsIn(resource("hdgife/hdgife_util.ink"))
        assertEquals(core + util, HdgifeInk.libraryFunctions.toSet())
        assertEquals(HdgifeInk.libraryFunctions.size, HdgifeInk.libraryFunctions.toSet().size, "no name twice")
        assertTrue(core.intersect(util).isEmpty())
        for (name in listOf("chance", "clamp", "avg2", "avg3", "blend", "invert", "scale100", "emit", "emit1", "emit2", "emit3", "speak", "stat", "fmt")) {
            assertTrue(name in HdgifeInk.libraryFunctions, name)
        }
    }

    @Test
    fun `the version constant matches the one in hdgife ink`() {
        val declared = Regex("(?m)^CONST\\s+hdgife_version\\s*=\\s*(\\d+)").find(resource("hdgife/hdgife.ink"))!!.groupValues[1].toInt()
        assertEquals(declared, HdgifeInk.version)
    }

    @Test
    fun `the util file works alone`() {
        val story = compileInk("INCLUDE hdgife_util.ink\n{clamp(15, 0, 10)} {avg2(2, 4)} {invert(30)} {chance(100)}")
        assertEquals("10 3.0 70 true", story.Continue().trim())
    }

    @Test
    fun `the util file works next to the core file in either order and nothing is declared twice`() {
        for (head in listOf("INCLUDE hdgife.ink\nINCLUDE hdgife_util.ink", "INCLUDE hdgife_util.ink\nINCLUDE hdgife.ink")) {
            val manager = StoryManager(compileInk("$head\n{clamp(15, 0, 10)} {fmt(avg2(1, 2), 1, \"round\")}"))
            assertEquals(listOf("10 1.5"), manager.next().texts())
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
        val manager = managerOf("~ emit(\"a\")\n~ emit1(\"b\", 1)\n~ emit2(\"c\", 1, 2)\n~ emit3(\"d\", 1, 2, 3)\nHi")
        val step = manager.next()
        assertEquals(listOf("a", "b", "c", "d"), step.events.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), step.events.map { it.args.size })
    }

    @Test
    fun `floor_text and round_text are not in the library`() {
        assertFailsWith<InkCompileException> { compileLib("{floor_text(2.5)}") }
        assertFailsWith<InkCompileException> { compileLib("{round_text(2.5, 1)}") }
    }

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
        val manager = managerOf("Hi\n* [Go] -> END")
        manager.next()
        assertFailsWith<IllegalArgumentException> { manager.choose(9) }
        assertFailsWith<IllegalArgumentException> { manager.setVariable("nope", 1) }
        assertFailsWith<IllegalArgumentException> { manager.observe("nope") { _, _ -> } }
    }

    @Test
    fun `beginScene wraps ink's path error and leaves the speaker`() {
        val manager = managerOf("=== scene ===\nHello\n-> END")
        manager.speaker = "keep"
        val ex = assertFailsWith<StoryPathException> { manager.beginScene("nope.missing", "other") }
        assertEquals("nope.missing", ex.path)
        assertIs<com.bladecoder.ink.runtime.StoryException>(ex.cause)
        assertEquals("keep", manager.speaker)
        manager.beginScene("scene", "guard")
        assertEquals("guard", manager.speaker)
        assertEquals(listOf("Hello"), manager.next().texts())
    }

    @Test
    fun `a save from a newer version and a broken envelope are SaveStateExceptions`() {
        val manager = managerOf("Hi")
        val ex = assertFailsWith<SaveStateException> { manager.loadState("{\"v\":99,\"ink\":\"{}\"}") }
        assertContains(ex.message.orEmpty(), "99")
        assertFailsWith<SaveStateException> { manager.loadState("{\"v\":1}\n") }.also { assertIs<HdgifeException>(it) }
    }

    @Test
    fun `an incompatible library carries the versions`() {
        val old = compileInk("VAR hdgife_speaker = \"\"\nHi")
        val ex = assertFailsWith<IncompatibleLibraryException> { StoryManager(old) }
        assertEquals(0, ex.found)
        assertEquals(HdgifeInk.version, ex.expected)
        val missing = assertFailsWith<IncompatibleLibraryException> { managerOf("Hi", lib = false).seedRandom(1) }
        assertContains(missing.message.orEmpty(), "INCLUDE hdgife.ink")
    }

    @Test
    fun `the log hears about a refused library`() {
        val sink = LogSink()
        val manager = managerOf("Hi")
        manager.log = sink
        assertFailsWith<IncompatibleLibraryException> { manager.replaceStory(compileInk("VAR hdgife_speaker = \"\"\nHi")) }
        assertContains(sink.messages.joinToString(), "out of date")
    }

    private val varInk = "VAR hp = 10\nVAR rate = 1.5\nVAR name = \"Ann\"\nHi"

    @Test
    fun `getVariable reads untyped and typed`() {
        val manager = managerOf(varInk)
        assertEquals(10, manager.getVariable("hp"))
        assertNull(manager.getVariable("nope"))
        assertEquals(10, manager.getVariable("hp", Int::class))
        assertEquals(10.0, manager.getVariable("hp", Double::class))
        assertEquals(10L, manager.getVariable("hp", Long::class))
        assertEquals(1.5, manager.getVariable("rate", Double::class))
        assertNull(manager.getVariable("nope", Int::class))
        assertFailsWith<IllegalArgumentException> { manager.getVariable("name", Int::class) }
    }

    @Test
    fun `variableAs is the inline form`() {
        val manager = managerOf(varInk)
        assertEquals(10f, manager.variableAs<Float>("hp"))
        assertEquals("Ann", manager.variableAs<String>("name"))
        assertNull(manager.variableAs<Int>("nope"))
        assertFailsWith<IllegalArgumentException> { manager.variableAs<String>("hp") }
    }

    @Test
    fun `replaceStory keeps observers, functions and the speaker`() {
        val manager = managerOf("EXTERNAL add(a)\nVAR hp = 1\n~ hp = 4\n{add(1)}\n=== function add(a) ===\n~ return a")
        manager.bindFunction("add") { it.int(0) + 100 }
        var seen = 0
        manager.observe("hp") { _, new -> seen = new as Int }
        manager.speaker = "p"
        val next = compileLib("EXTERNAL add(a)\nVAR hp = 1\n~ hp = 7\n{add(2)}\n=== function add(a) ===\n~ return a")
        manager.replaceStory(next)
        assertSame(next, manager.story)
        assertEquals("p", manager.speaker)
        assertEquals(listOf("102"), manager.next().texts())
        assertEquals(7, seen)
    }

    @Test
    fun `a refused replacement leaves the story`() {
        val manager = managerOf("Hi")
        val before = manager.story
        assertFailsWith<IncompatibleLibraryException> { manager.replaceStory(compileInk("VAR hdgife_speaker = \"\"\nHi")) }
        assertSame(before, manager.story)
    }

    private fun assertSame(expected: Any?, actual: Any?) = kotlin.test.assertSame(expected, actual)

    @Test
    fun `the constructor takes an HdgifeStory and the plain one has its own entry point`() {
        val hdgife: HdgifeStory = compileLib("{stat_of(\"p\", \"hp\")}")
        assertTrue(StoryManager(hdgife, MapStats().also { it["p", "hp"] = 3 }).isLookaheadSafe)
        val plain = StoryManager.forPlainStory(
            com.bladecoder.ink.runtime.Story(compileLib("{stat_of(\"p\", \"hp\")}").toJson()),
            MapStats().also { it["p", "hp"] = 3 },
        )
        assertFalse(plain.isLookaheadSafe)
        assertEquals(listOf("3"), plain.next().texts())
    }

    @Test
    fun `result classes have value equality and a readable toString`() {
        val step = managerOf("Hello <b>there</b>\n~ emit1(\"e\", 1)\n* [Go] -> END").next()
        val sameStep = managerOf("Hello <b>there</b>\n~ emit1(\"e\", 1)\n* [Go] -> END").next()
        assertEquals(step, sameStep)
        assertEquals(step.hashCode(), sameStep.hashCode())
        assertEquals(step.lines.single(), sameStep.lines.single())
        assertEquals(step.choices.single(), sameStep.choices.single())
        assertEquals(step.events.single(), sameStep.events.single())
        assertContains(step.toString(), "Hello there")
        assertContains(step.events.single().toString(), "name=e")
        val otherStep = managerOf("Other\n-> END").next()
        assertNotEquals(step, otherStep)
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
            com.github.halfdeadgames.hdgife.story.RequirementResult.Met::class,
            com.github.halfdeadgames.hdgife.story.RequirementResult.Unmet::class,
            com.github.halfdeadgames.hdgife.story.RequirementResult.Mismatch::class,
            com.github.halfdeadgames.hdgife.story.RequirementResult.MissingStat::class,
            com.github.halfdeadgames.hdgife.story.RequirementResult.Unreadable::class, com.github.halfdeadgames.hdgife.story.ValidationIssue::class,
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
        val manager = managerOf("Line one\n~ boom()\nLine two\n-> END\nEXTERNAL boom()\n=== function boom() ===\n~ return 0")
        manager.bindFunction("boom") { error("bang") }
        val ex = assertFailsWith<StoryStepException> { manager.next() }
        assertIs<HdgifeException>(ex)
        assertEquals(listOf("Line one"), ex.step.texts())
    }
}
