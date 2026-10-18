package com.github.halfdeadgames.hdgife

import com.bladecoder.ink.runtime.InkList
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.story.StoryEvent
import com.github.halfdeadgames.hdgife.story.IncompatibleLibraryException
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.StoryStepException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventsTest {
    private val eventInk = """
        Line one
        ~ emit1("first", 1)
        Line two <>
        ~ emit2("second", "x", true)
        glued
        ~ emit("third")
        ~ emit3("fourth", 1, 2.5, "z")
        -> END
    """.trimIndent()

    @Test
    fun `a story from an old hdgife ink is refused loudly`() {
        val old = compileInk("""
            VAR hdgife_speaker = ""
            EXTERNAL emit1(name, a)
            ~ emit1("x", 1)
            Hi
            -> END
            === function emit1(name, a) ===
            ~ return 0
        """.trimIndent())
        val ex = assertFailsWith<IncompatibleLibraryException> { StoryManager(old) }
        assertContains(ex.message.orEmpty(), "out of date")
        assertEquals(0, ex.found)
        assertEquals(HdgifeInk.VERSION, ex.expected)
    }

    @Test
    fun `a user story with its own emit and no hdgife ink runs untouched`() {
        val story = compileInk("""
            VAR count = 0
            ~ emit1("x", 1)
            Hi
            -> END
            === function emit1(name, a) ===
            ~ count++
            ~ return 0
        """.trimIndent())
        val step = StoryManager(story).next()
        assertEquals(listOf("Hi"), step.texts())
        assertTrue(step.events.isEmpty())
    }

    @Test
    fun `reset after a throwing textCleaner drops late events`() {
        val m = managerOf("""
            Line
            ~ emit("a")
            Next
            ~ emit("b")
            End
        """.trimIndent())
        var calls = 0
        val cleaner = m.textCleaner
        m.textCleaner = { if (++calls == 2) throw IllegalStateException("boom") else cleaner(it) }
        assertIs<IllegalStateException>(assertFailsWith<StoryStepException> { m.next() }.cause)
        m.textCleaner = cleaner
        m.reset()
        val step = m.next()
        assertEquals(listOf("a", "b"), step.events.map { it.name })
        assertEquals(listOf("Line", "Next", "End"), step.texts())
        assertEquals(listOf(1, 2), step.events.map { it.lineIndex })
    }

    // ---- events ----

    @Test
    fun `events are reported once each and in order`() {
        val step = managerOf(eventInk).next()
        assertEquals(listOf("first", "second", "third", "fourth"), step.events.map { it.name })
        assertEquals(listOf(1), step.events[0].args)
        assertEquals(listOf<Any?>("x", true), step.events[1].args)
        assertEquals(emptyList(), step.events[2].args)
        assertEquals(listOf<Any?>(1, 2.5f, "z"), step.events[3].args)
    }

    @Test
    fun `an event is not reported again by a later step`() {
        val m = managerOf("~ emit(\"once\")\nHi\n* [Go] Bye -> END")
        assertEquals(1, m.next().events.size)
        assertTrue(m.choose(0).events.isEmpty())
    }

    @Test
    fun `glue lookahead does not duplicate events`() {
        val m = managerOf("A <>\n~ emit(\"x\")\nB\n~ emit(\"y\")\nC")
        val step = m.next()
        assertEquals(listOf("x", "y"), step.events.map { it.name })
        assertEquals(listOf("A B", "C"), step.texts())
    }

    @Test
    fun `glue joins across an emit between a line and the glue line`() {
        val step = managerOf("You have\n~ emit1(\"seen\", 1)\n<> 5 hp.\nNext").next()
        assertEquals(listOf("You have 5 hp.", "Next"), step.texts())
        assertEquals(listOf("seen" to 0), step.events.map { it.name to it.lineIndex })
    }

    @Test
    fun `an emit that glue does not join is reported before the line after it`() {
        val step = managerOf("A\n~ emit(\"x\")\nB").next()
        assertEquals(listOf("A", "B"), step.texts())
        assertEquals(listOf("x" to 1), step.events.map { it.name to it.lineIndex })
    }

    @Test
    fun `several emits around glue and breaks keep order and line index`() {
        val m = managerOf(
            "One\n~ emit(\"a\")\n<> two\n~ emit(\"b\")\n~ emit(\"c\")\nThree <>\n~ emit(\"d\")\n<> four\n~ emit(\"e\")\n-> END",
        )
        val step = m.next()
        assertEquals(listOf("One two", "Three four"), step.texts())
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 1, "d" to 1, "e" to 2), step.events.map { it.name to it.lineIndex })
    }

    @Test
    fun `an emit inside an ink function is reported once across glue`() {
        val m = managerOf("Gold\n~ reward(3)\n<> up.\nDone\n=== function reward(n) ===\n~ emit1(\"gold\", n)\n~ return n")
        val step = m.next()
        assertEquals(listOf("Gold up.", "Done"), step.texts())
        assertEquals(listOf("gold"), step.events.map { it.name })
        assertEquals(listOf<Any?>(3), step.events.single().args)
    }

    @Test
    fun `events are exactly once over a whole run with choices`() {
        val m = managerOf(
            """
            Start
            ~ emit("s")
            <> here
            * [Left] Went left
                ~ emit("l")
                <> fully
                -> done
            * [Right] -> done
            = done
            ~ emit("d")
            End
            -> END
            """.trimIndent(),
        )
        val seen = ArrayList<String>()
        m.onEvent = { seen += it.name }
        val first = m.next()
        assertEquals(listOf("Start here"), first.texts())
        assertEquals(listOf("s"), first.events.map { it.name })
        val second = m.choose(0)
        assertEquals(listOf("Went left fully", "End"), second.texts())
        assertEquals(listOf("l", "d"), second.events.map { it.name })
        assertEquals(listOf("s", "l", "d"), seen)
    }

    @Test
    fun `a plain story reports events once but its emit ends glue lookahead`() {
        val plain = Story(compileLib("A\n~ emit(\"x\")\n<> B\n~ emit(\"y\")\nC").toJson())
        val manager = StoryManager.forPlainStory(plain)
        assertFalse(manager.lookaheadSafe)
        val step = manager.next()
        assertEquals(listOf("A", "B", "C"), step.texts())
        assertEquals(listOf("x" to 1, "y" to 2), step.events.map { it.name to it.lineIndex })
    }

    @Test
    fun `an emit in choice text fires once when the choice is created`() {
        val m = managerOf("Pick\n* [Buy {cost()}] -> END\n=== function cost() ===\n~ emit1(\"shown\", 1)\n~ return 5")
        val step = m.next()
        assertEquals("Buy 5", step.choices.single().text)
        assertEquals(listOf("shown" to 0), step.events.map { it.name to it.lineIndex })
        assertTrue(m.next().events.isEmpty())
    }

    @Test
    fun `events after a load are reported once`() {
        val m = managerOf("A\n* [Go]\n  ~ emit(\"go\")\n  B\n  -> END")
        m.next()
        val saved = m.saveState()
        assertEquals(listOf("go"), m.choose(0).events.map { it.name })
        m.loadState(saved)
        assertEquals(listOf("go"), m.choose(0).events.map { it.name })
    }

    @Test
    fun `an event line index points at the line produced right after it`() {
        val step = managerOf("One\n~ emit(\"a\")\nTwo\n~ emit(\"b\")\n-> END").next()
        assertEquals(listOf("One", "Two"), step.texts())
        assertEquals(listOf("a" to 1, "b" to 2), step.events.map { it.name to it.lineIndex })
    }

    @Test
    fun `onEvent receives each event in order after the step is built`() {
        val m = managerOf(eventInk)
        val seen = ArrayList<StoryEvent>()
        m.onEvent = { seen += it }
        val step = m.next()
        assertEquals(step.events, seen)
    }

    @Test
    fun `reset drops pending events`() {
        val m = managerOf("~ emit(\"a\")\nHi")
        m.reset()
        assertEquals(1, m.next().events.size)
    }

    @Test
    fun `emit works without a provider and without a manager`() {
        assertEquals("Hi", compileLib("~ emit(\"x\")\nHi").Continue().trim())
    }

    // ---- bindFunction ----

    private val addInk = "{add(2, 3)}\n-> END\nEXTERNAL add(a, b)\n=== function add(a, b) ===\n~ return 0"

    @Test
    fun `a bound function replaces the ink fallback`() {
        val m = managerOf(addInk)
        m.bindFunction("add") { it.int(0) + it.int(1) }
        assertEquals(listOf("5"), m.next().texts())
    }

    @Test
    fun `without a binding the ink fallback runs`() {
        assertEquals(listOf("0"), managerOf(addInk).next().texts())
    }

    @Test
    fun `a bound function can return null for statements`() {
        val m = managerOf("~ ping()\nDone\n-> END\nEXTERNAL ping()\n=== function ping() ===\n~ return 0")
        var calls = 0
        m.bindFunction("ping") { calls++; null }
        assertEquals(listOf("Done"), m.next().texts())
        assertEquals(1, calls)
    }

    @Test
    fun `a lookahead safe bound function works in choice text`() {
        val m = managerOf("* [Cost {price()}] -> END\nEXTERNAL price()\n=== function price() ===\n~ return 0")
        m.bindFunction("price", lookaheadSafe = true) { 42 }
        assertEquals("Cost 42", m.next().choices.single().text)
    }

    @Test
    fun `a bound function can be replaced after unbind`() {
        val m = managerOf(addInk)
        m.bindFunction("add") { 1 }
        m.unbindFunction("add")
        m.bindFunction("add") { 2 }
        assertEquals(listOf("2"), m.next().texts())
    }

    @Test
    fun `binding the same name twice replaces the first`() {
        val m = managerOf(addInk)
        m.bindFunction("add") { 1 }
        m.bindFunction("add") { 7 }
        assertEquals(listOf("7"), m.next().texts())
    }

    @Test
    fun `a name bound by the library cannot be rebound`() {
        val m = managerOf("Hi")
        assertFailsWith<IllegalArgumentException> { m.bindFunction("stat") { 0 } }
    }

    @Test
    fun `bindings survive assigning a new story`() {
        val m = managerOf(addInk)
        m.bindFunction("add") { it.int(0) * 10 }
        m.replaceStory(compileLib(addInk))
        assertEquals(listOf("20"), m.next().texts())
    }

    @Test
    fun `function args are typed and coerced`() {
        val m = managerOf("{show(2, 2.5, \"s\", true)}\n-> END\nEXTERNAL show(a, b, c, d)\n=== function show(a, b, c, d) ===\n~ return 0")
        var captured = ""
        m.bindFunction("show") { args ->
            captured = "${args.int(0)} ${args.float(0)} ${args.float(1)} ${args.string(2)} ${args.bool(3)} ${args.size} ${args[9]}"
            0
        }
        m.next()
        assertEquals("2 2.0 2.5 s true 4 null", captured)
    }

    @Test
    fun `function args name the function and position on a wrong type`() {
        val m = managerOf("{show(\"s\")}\n-> END\nEXTERNAL show(a)\n=== function show(a) ===\n~ return 0")
        m.bindFunction("show") { it.int(0) }
        val e = assertFailsWith<Exception> { m.next() }
        val message = generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() }
        assertContains(message, "show: argument 0")
    }

    // ---- variables ----

    private val varInk = "VAR hp = 10\nVAR rate = 1.5\nVAR name = \"Ann\"\nVAR alive = true\nHello\n* [Hit]\n  ~ hp -= 3\n  Ouch\n  -> END"

    @Test
    fun `variable returns the ink value or null`() {
        val m = managerOf(varInk)
        assertEquals(10, m.getVariable("hp"))
        assertEquals(1.5f, m.getVariable("rate"))
        assertEquals("Ann", m.getVariable("name"))
        assertEquals(true, m.getVariable("alive"))
        assertNull(m.getVariable("nope"))
    }

    @Test
    fun `typed variable coerces ints to wider numbers`() {
        val m = managerOf(varInk)
        assertEquals(10, m.variableAs<Int>("hp"))
        assertEquals(10f, m.variableAs<Float>("hp"))
        assertEquals(10.0, m.variableAs<Double>("hp"))
        assertEquals(10L, m.variableAs<Long>("hp"))
        assertEquals(1.5, m.variableAs<Double>("rate"))
        assertNull(m.variableAs<Int>("nope"))
    }

    @Test
    fun `typed variable throws on a real mismatch`() {
        val m = managerOf(varInk)
        assertFailsWith<IllegalArgumentException> { m.variableAs<String>("hp") }
        assertFailsWith<IllegalArgumentException> { m.variableAs<Int>("rate") }
    }

    @Test
    fun `setVariable changes the ink value`() {
        val m = managerOf(varInk)
        m.setVariable("hp", 99)
        m.setVariable("name", "Bo")
        m.setVariable("rate", 2.0)
        assertEquals(99, m.getVariable("hp"))
        assertEquals("Bo", m.getVariable("name"))
        assertEquals(2.0f, m.getVariable("rate"))
    }

    @Test
    fun `setVariable rejects an undeclared variable`() {
        assertFailsWith<IllegalArgumentException> { managerOf(varInk).setVariable("nope", 1) }
    }

    @Test
    fun `setVariable keeps an InkList`() {
        val m = managerOf("LIST items = sword, shield\nVAR bag = (sword)\nHi")
        val list = InkList(m.getVariable("bag") as InkList)
        m.setVariable("bag", list)
        assertTrue(m.getVariable("bag") is InkList)
    }

    @Test
    fun `variableNames lists all globals sorted including the library ones`() {
        val names = managerOf(varInk).variableNames
        assertEquals(names.sorted(), names)
        assertTrue(names.containsAll(listOf("alive", "hdgife_speaker", "hp", "name", "rate")))
    }

    @Test
    fun `variableNames of a story without globals is empty`() {
        assertEquals(emptyList(), managerOf("Hi", lib = false).variableNames)
    }

    @Test
    fun `observe reports the old and new value when ink changes a variable`() {
        val m = managerOf(varInk)
        val changes = ArrayList<Pair<Any?, Any?>>()
        m.observe("hp") { old, new -> changes += old to new }
        m.next()
        assertTrue(changes.isEmpty())
        m.choose(0)
        assertEquals(listOf<Pair<Any?, Any?>>(10 to 7), changes)
    }

    @Test
    fun `observe reports changes made with setVariable`() {
        val m = managerOf(varInk)
        val changes = ArrayList<Pair<Any?, Any?>>()
        m.observe("hp") { old, new -> changes += old to new }
        m.setVariable("hp", 4)
        m.setVariable("hp", 5)
        assertEquals(listOf<Pair<Any?, Any?>>(10 to 4, 4 to 5), changes)
    }

    @Test
    fun `unobserve stops the callbacks`() {
        val m = managerOf(varInk)
        var calls = 0
        val observation = m.observe("hp") { _, _ -> calls++ }
        m.setVariable("hp", 1)
        m.unobserve(observation)
        m.setVariable("hp", 2)
        assertEquals(1, calls)
        assertFalse(observation.active)
        m.unobserve(observation)
    }

    @Test
    fun `unobserve by name stops every observer of that variable`() {
        val m = managerOf(varInk)
        var calls = 0
        m.observe("hp") { _, _ -> calls++ }
        m.observe("hp") { _, _ -> calls++ }
        m.observe("name") { _, _ -> calls += 100 }
        m.unobserve("hp")
        m.setVariable("hp", 1)
        m.setVariable("name", "Cy")
        assertEquals(100, calls)
    }

    @Test
    fun `an observation closes like AutoCloseable`() {
        val m = managerOf(varInk)
        var calls = 0
        m.observe("hp") { _, _ -> calls++ }.use { }
        m.setVariable("hp", 1)
        assertEquals(0, calls)
    }

    @Test
    fun `observe rejects an undeclared variable`() {
        assertFailsWith<IllegalArgumentException> { managerOf(varInk).observe("nope") { _, _ -> } }
    }

    @Test
    fun `observers keep working after reset and see the restored value as the baseline`() {
        val m = managerOf(varInk)
        val changes = ArrayList<Pair<Any?, Any?>>()
        m.observe("hp") { old, new -> changes += old to new }
        m.setVariable("hp", 1)
        m.reset()
        changes.clear()
        m.setVariable("hp", 2)
        assertEquals(listOf<Pair<Any?, Any?>>(10 to 2), changes)
    }

    @Test
    fun `observers move to a new story and drop when it lacks the variable`() {
        val sink = LogSink()
        val m = managerOf(varInk)
        m.log = sink
        var hp = 0
        var name = 0
        m.observe("hp") { _, _ -> hp++ }
        m.observe("name") { _, _ -> name++ }
        m.replaceStory(compileLib("VAR hp = 1\nHi"))
        m.setVariable("hp", 5)
        assertEquals(1, hp)
        assertEquals(0, name)
        assertContains(sink.messages.joinToString(), "name")
    }

    @Test
    fun `defaultVariableName replaces odd characters`() {
        assertEquals("player_hp", StoryManager.defaultVariableName("player", "hp"))
        assertEquals("a_b_c_d", StoryManager.defaultVariableName("a b", "c-d"))
    }

    @Test
    fun `cleanLine tidies spaces and punctuation`() {
        assertEquals("Hello, world!", com.github.halfdeadgames.hdgife.story.cleanLine("  Hello ,   world ! "))
    }
}
