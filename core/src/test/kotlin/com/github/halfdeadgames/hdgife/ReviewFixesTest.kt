package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.splitClauses
import com.github.halfdeadgames.hdgife.story.VariableNamer
import com.github.halfdeadgames.hdgife.story.StoryEventListener
import com.bladecoder.ink.runtime.HdgifeStory
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.compiler.InkSource
import com.github.halfdeadgames.hdgife.story.IncompatibleLibraryException
import com.github.halfdeadgames.hdgife.story.RequirementResult
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.StoryStepException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReviewFixesTest {
    private fun stats(entity: String, stat: String, value: Any?) = MapStats().also { it[entity, stat] = value }

    private fun causes(e: Throwable): List<Throwable> = generateSequence(e) { it.cause }.toList()

    @Test
    fun `a second manager on the same story takes over the library functions`() {
        val story = compileLib("{stat_of(\"p\", \"hp\")}")
        StoryManager(story, stats("p", "hp", 1))
        val second = StoryManager(story, stats("p", "hp", 2))
        assertEquals(listOf("2"), second.next().texts())
    }

    @Test
    fun `a name bound by an earlier manager cannot be bound again with bindFunction`() {
        val story = compileLib("Hi")
        StoryManager(story).bindFunction("give") { 0 }
        assertFailsWith<IllegalArgumentException> { StoryManager(story).bindFunction("give") { 1 } }
    }

    @Test
    fun `assigning the story it already has rebinds without doubling anything`() {
        val manager = managerOf("EXTERNAL give(x)\nVAR hp = 1\n~ give(1)\n~ hp = 5\nDone\n=== function give(x) ===\n~ return 0")
        var calls = 0
        manager.bindFunction("give") { calls++ }
        val seen = ArrayList<Any?>()
        manager.observe("hp") { _, new -> seen += new }
        manager.replaceStory(manager.story as HdgifeStory)
        manager.next()
        assertEquals(1, calls)
        assertEquals(listOf<Any?>(5), seen)
    }

    @Test
    fun `assigning a story that another manager had bound uses this manager`() {
        val managed = compileLib("{stat_of(\"p\", \"hp\")}")
        StoryManager(managed, stats("p", "hp", 1))
        val previous = compileLib("Old")
        val manager = StoryManager(previous, stats("p", "hp", 2))
        manager.replaceStory(managed)
        assertEquals(listOf("2"), manager.next().texts())
        assertNull(previous.getExternalFunction("stat_of"), "the old story is unbound")
    }

    @Test
    fun `a refused story leaves the manager as it was`() {
        val manager = managerOf("<b>Hi\n* [Go] -> END", stats("p", "hp", 3))
        manager.bindFunction("later") { 0 }
        manager.speaker = "p"
        manager.next()
        val before = manager.story
        val saved = manager.saveState()
        val old = compileInk("VAR hdgife_speaker = \"\"\nEXTERNAL emit1(name, a)\n~ emit1(\"x\", 1)\nHi\n-> END\n=== function emit1(name, a) ===\n~ return 0")
        assertFailsWith<IncompatibleLibraryException> { manager.replaceStory(old) }
        assertSame(before, manager.story)
        assertNotNull(before.getExternalFunction("stat"))
        assertNotNull(before.getExternalFunction("later"))
        assertNull(old.getExternalFunction("stat"), "nothing was bound to the refused story")
        assertEquals("p", manager.speaker)
        assertEquals(saved, manager.saveState(), "open styles and state are unchanged")
    }

    private val boomInk = "EXTERNAL boom()\n~ emit(\"early\")\n<b>Line one\n~ boom()\nLine two\n-> END\n=== function boom() ===\n~ return 0"

    @Test
    fun `a throwing bound function gives a StoryStepException with the partial step`() {
        val manager = managerOf(boomInk)
        manager.bindFunction("boom") { error("bang") }
        val heard = ArrayList<String>()
        manager.onEvent = StoryEventListener { heard += it.name }
        val ex = assertFailsWith<StoryStepException> { manager.next() }
        assertEquals(listOf("Line one"), ex.step.texts())
        assertEquals(listOf("b"), ex.step.openStyles.map { it.name })
        assertEquals(listOf("early"), ex.step.events.map { it.name })
        assertFalse(ex.step.isEnded)
        assertTrue(ex.step.choices.isEmpty())
        assertTrue(causes(ex).any { it.message == "bang" })
        assertEquals(listOf("early"), heard)
    }

    @Test
    fun `after a failed step the manager can reset and run again without stale events`() {
        val manager = managerOf(boomInk)
        manager.bindFunction("boom") { error("bang") }
        assertFailsWith<StoryStepException> { manager.next() }
        manager.reset()
        manager.unbindFunction("boom")
        val step = manager.next()
        assertEquals(listOf("Line one", "Line two"), step.texts())
        assertEquals(listOf("early"), step.events.map { it.name })
    }

    @Test
    fun `an ink runtime error also gives a StoryStepException`() {
        val manager = managerOf("EXTERNAL foo()\nHi\n~ foo()\n=== function foo() ===\n~ return 0")
        manager.allowExternalFunctionFallbacks = false
        val ex = assertFailsWith<StoryStepException> { manager.next() }
        assertTrue(ex.step.lines.isEmpty())
    }

    @Test
    fun `a throwing state provider comes out of next as a StoryStepException`() {
        val manager = managerOf("{stat(\"hp\")}", NarrativeStateProviderThatThrows)
        manager.speaker = "p"
        val ex = assertFailsWith<StoryStepException> { manager.next() }
        assertTrue(causes(ex).any { it.message == "provider down" })
    }

    @Test
    fun `a throwing state provider comes out of syncStatsToInk as it is`() {
        val story = compileLib("VAR p_hp = 0\nHi")
        val manager = StoryManager(story, NarrativeStateProviderThatThrows)
        val ex = assertFailsWith<IllegalStateException> { manager.syncStats("p", listOf("hp")) }
        assertEquals("provider down", ex.message)
    }

    private fun requirements(clause: String, stats: MapStats, log: LogSink? = null) =
        managerOf("* [Go # requires: $clause] -> END", stats).also { it.log = log; it.speaker = "p" }.next().choices.single().requirements

    @Test
    fun `a quoted entity name may hold spaces`() {
        val requirementResult = assertIs<RequirementResult.Met>(requirements("\"Old Man\".mood > 3", stats("Old Man", "mood", 5)).single())
        assertEquals("Old Man", requirementResult.entity)
        assertEquals("mood", requirementResult.stat)
        assertTrue(requirementResult.isMet)
    }

    @Test
    fun `a quoted entity name may hold a dot`() {
        val requirementResult = assertIs<RequirementResult.Met>(requirements("'Mr. X'.mood == 1", stats("Mr. X", "mood", 1)).single())
        assertEquals("Mr. X", requirementResult.entity)
        assertTrue(requirementResult.isMet)
    }

    @Test
    fun `a quoted stat name may hold spaces`() {
        val requirementResult = assertIs<RequirementResult.Met>(requirements("\"hit points\" >= 10", stats("p", "hit points", 12)).single())
        assertEquals("hit points", requirementResult.stat)
        assertTrue(requirementResult.isMet)
    }

    @Test
    fun `a quoted string target may hold commas and spaces`() {
        val list = requirements("title == \"Sir, the Bold\", strength >= 2", MapStats().also {
            it["p", "title"] = "Sir, the Bold"
            it["p", "strength"] = 3
        })
        assertEquals(2, list.size)
        assertEquals("Sir, the Bold", assertIs<RequirementResult.Met>(list[0]).target)
        assertTrue(list.all { it.isMet })
    }

    @Test
    fun `an apostrophe inside a bare value is not a quote`() {
        val list = requirements("name == O'Brien, hp > 1", MapStats().also {
            it["p", "name"] = "O'Brien"
            it["p", "hp"] = 5
        })
        assertEquals(2, list.size)
        assertTrue(list.all { it.isMet })
    }

    @Test
    fun `an unterminated quote does not crash and is not met`() {
        val list = requirements("title == \"Sir, the Bold", stats("p", "title", "Sir, the Bold"))
        assertEquals(1, list.size)
        assertFalse(list.single().isMet)
    }

    @Test
    fun `splitClauses splits outside quotes only`() {
        assertEquals(listOf("a == \"x, y\"", " b > 1"), splitClauses("a == \"x, y\", b > 1"))
        assertEquals(listOf("a == 'x, y'"), splitClauses("a == 'x, y'"))
        assertEquals(listOf("\"A, B\".c > 1", " d < 2"), splitClauses("\"A, B\".c > 1, d < 2"))
        assertEquals(listOf("a == \"x, y"), splitClauses("a == \"x, y"))
    }

    @Test
    fun `seedRandom sets lastSeed`() {
        val manager = managerOf("Hi")
        manager.seedRandom(5)
        assertEquals(5, manager.lastSeed)
    }

    @Test
    fun `seed without the library throws`() {
        val manager = managerOf("Hi", lib = false)
        assertFailsWith<IncompatibleLibraryException> { manager.seedRandom(1) }
    }

    private val bSource = "VAR bv = 7"

    @Test
    fun `a trailing line comment on an INCLUDE is not part of the name`() {
        val story = compiler.compile(listOf(InkSource("main.ink", "INCLUDE b.ink // the b file\n{bv}"), InkSource("b.ink", bSource)))
        assertEquals("7", story.Continue().trim())
    }

    @Test
    fun `a trailing block comment on an INCLUDE is not part of the name`() {
        val story = compiler.compile(listOf(InkSource("main.ink", "INCLUDE b.ink /* the b file */\n{bv}"), InkSource("b.ink", bSource)))
        assertEquals("7", story.Continue().trim())
    }

    @Test
    fun `an INCLUDE inside a block comment is ignored`() {
        val story = compileInk("/*\nINCLUDE missing.ink\n*/\nHi")
        assertEquals("Hi", story.Continue().trim())
    }

    @Test
    fun `an INCLUDE after a block comment still works`() {
        val main = InkSource.lazy("main.ink", sibling = { rel ->
            if (rel == "b.ink") InkSource("b.ink", bSource) else null
        }) { "/* note\nINCLUDE gone.ink\n*/\nINCLUDE b.ink\n{bv}" }
        assertEquals("7", compiler.compile(main).Continue().trim())
    }

    @Test
    fun `a block opener inside a line comment does not hide the next INCLUDE`() {
        val main = InkSource.lazy("main.ink", sibling = { rel ->
            if (rel == "b.ink") InkSource("b.ink", bSource) else null
        }) { "// see /* later\nINCLUDE b.ink\n{bv}" }
        assertEquals("7", compiler.compile(main).Continue().trim())
    }

    @Test
    fun `speak works with external fallbacks switched off`() {
        val manager = managerOf("~ speak(\"guard\")\n{stat(\"hp\")}\nHi", stats("guard", "hp", 7))
        manager.allowExternalFunctionFallbacks = false
        val step = manager.next()
        assertEquals(listOf("7", "Hi"), step.texts())
        assertEquals(listOf("guard", "guard"), step.lines.map { it.speaker })
    }

    @Test
    fun `speak survives save and load with fallbacks off`() {
        val manager = managerOf("~ speak(\"guard\")\nHi\n* [Go] -> END")
        manager.allowExternalFunctionFallbacks = false
        manager.next()
        val saved = manager.saveState()
        manager.reset()
        assertNull(manager.speaker)
        manager.loadState(saved)
        assertEquals("guard", manager.speaker)
    }

    @Test
    fun `speak is lookahead safe so glue still joins`() {
        val manager = managerOf("Start <>\n~ speak(\"guard\")\nEnd")
        manager.allowExternalFunctionFallbacks = false
        val step = manager.next()
        assertEquals(listOf("Start End"), step.texts())
        assertEquals("guard", manager.speaker)
    }

    @Test
    fun `speak with an empty name clears the speaker`() {
        val manager = managerOf("~ speak(\"guard\")\nA\n~ speak(\"\")\nB")
        val step = manager.next()
        assertEquals(listOf("guard", null), step.lines.map { it.speaker })
        assertNull(manager.speaker)
    }

    private val stringInk = "EXTERNAL f()\n~ temp t = \"x{f()}\"\n{t}\n=== function f() ===\n~ return 1"

    @Test
    fun `an unsafe function in string evaluation is an error as in upstream ink`() {
        val manager = managerOf(stringInk)
        manager.bindFunction("f", lookaheadSafe = false) { 1 }
        val ex = assertFailsWith<StoryStepException> { manager.next() }
        assertTrue(causes(ex).any { it.message.orEmpty().contains("string generation") })
    }

    @Test
    fun `an unsafe function in choice text is an error`() {
        val manager = managerOf("EXTERNAL f()\n* [Take {f()}] -> END\n=== function f() ===\n~ return 1")
        manager.bindFunction("f", lookaheadSafe = false) { 1 }
        assertFailsWith<StoryStepException> { manager.next() }
    }

    @Test
    fun `a safe function works in string evaluation`() {
        val manager = managerOf(stringInk)
        manager.bindFunction("f", lookaheadSafe = true) { 1 }
        assertEquals(listOf("x1"), manager.next().texts())
    }

    @Test
    fun `an unsafe function outside strings still works`() {
        val manager = managerOf("EXTERNAL f()\nA {f()}\n=== function f() ===\n~ return 1")
        manager.bindFunction("f", lookaheadSafe = false) { 1 }
        assertEquals(listOf("A 1"), manager.next().texts())
    }

    @Test
    fun `bindExternalFunction with safe true on an HdgifeStory works in strings`() {
        val story = compileInk(stringInk) as HdgifeStory
        story.bindExternalFunction("f", Story.ExternalFunction<Any?> { 1 }, true)
        assertEquals("x1", story.Continue().trim())
    }

    @Test
    fun `bindExternalFunction with safe false on an HdgifeStory errors in strings`() {
        val story = compileInk(stringInk) as HdgifeStory
        story.bindExternalFunction("f", Story.ExternalFunction<Any?> { 1 }, false)
        val ex = assertFailsWith<Exception> { story.Continue() }
        assertContains(ex.message.orEmpty(), "string generation")
    }

    @Test
    fun `Hdgife VERSION matches projectVersion`() {
        val expected = System.getProperty("hdgife.projectVersion")
        assertNotNull(expected, "the build passes projectVersion to the tests")
        assertEquals(expected, Hdgife.version)
    }

    @Test
    fun `a custom variableNamer decides where stats are written`() {
        val manager = managerOf("VAR v_hp = 0\nHi", stats("hero", "hp", 9))
        manager.variableNamer = VariableNamer { _, stat -> "v_$stat" }
        val skipped = manager.syncStats("hero", listOf("hp", "mp"))
        assertEquals(9, manager.getVariable("v_hp"))
        assertEquals(listOf("v_mp"), skipped)
    }

    @Test
    fun `stateProvider can be reassigned and cleared`() {
        val manager = managerOf("{stat_of(\"p\", \"hp\")}\n* [Go] {stat_of(\"p\", \"hp\")} -> END", stats("p", "hp", 1))
        assertEquals(listOf("1"), manager.next().texts())
        manager.reset()
        manager.stateProvider = stats("p", "hp", 2)
        assertEquals(listOf("2"), manager.next().texts())
        manager.reset()
        manager.stateProvider = null
        assertEquals(listOf("0.0"), manager.next().texts())
    }

    @Test
    fun `fmt with a non numeric argument formats zero`() {
        val manager = managerOf("{fmt(\"abc\", 2, \"round\")}")
        assertEquals(listOf("0.00"), manager.next().texts())
    }

    private object NarrativeStateProviderThatThrows : com.github.halfdeadgames.hdgife.state.NarrativeStateProvider {
        override fun getStat(entityId: String, statName: String): Any? = throw IllegalStateException("provider down")
    }
}
