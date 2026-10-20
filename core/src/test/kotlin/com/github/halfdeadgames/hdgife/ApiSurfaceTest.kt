package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.VariableNamer
import com.github.halfdeadgames.hdgife.compiler.InkSource
import com.github.halfdeadgames.hdgife.story.NumberFormat
import com.github.halfdeadgames.hdgife.story.NumberFormatOverride
import com.github.halfdeadgames.hdgife.story.RequirementResult
import com.github.halfdeadgames.hdgife.story.Rounding
import com.github.halfdeadgames.hdgife.story.StoryEvent
import com.github.halfdeadgames.hdgife.story.StoryEventListener
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.StoryValidator
import com.github.halfdeadgames.hdgife.story.Tags
import com.github.halfdeadgames.hdgife.story.ValueConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApiSurfaceTest {
    @Test
    fun `NumberFormat has value equality and with-copies`() {
        val base = NumberFormat(2, Rounding.Floor, true)
        assertEquals(NumberFormat(2, Rounding.Floor, true), base)
        assertEquals(NumberFormat(2, Rounding.Floor, true).hashCode(), base.hashCode())
        assertNotEquals(base, base.withDecimals(3))
        assertEquals(3, base.withDecimals(3).decimals)
        assertEquals(Rounding.Ceil, base.withRounding(Rounding.Ceil).rounding)
        assertFalse(base.withTrimTrailingZeros(false).trimTrailingZeros)
        assertEquals(NumberFormat(), NumberFormat(0, Rounding.Round, false))
        assertEquals("NumberFormat(decimals=2, rounding=Floor, trimTrailingZeros=true)", base.toString())
    }

    @Test
    fun `NumberFormat rejects decimals out of range through withDecimals`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { NumberFormat().withDecimals(10) }
    }

    @Test
    fun `callback properties default to no-ops and accept both assignment styles`() {
        val manager = managerOf("Hi")
        manager.onEvent.onEvent(StoryEvent("x", emptyList(), 0))
        assertNull(manager.formatOverride.formatFor("e", "s"))
        assertNull(manager.valueConverter.convert(Any()))

        val seen = ArrayList<String>()
        manager.onEvent = StoryEventListener { seen += it.name }
        manager.onEvent.onEvent(StoryEvent("a", emptyList(), 0))
        manager.onEvent = StoryEventListener { seen += "b" }
        manager.onEvent.onEvent(StoryEvent("a", emptyList(), 0))
        assertEquals(listOf("a", "b"), seen)

        manager.formatOverride = NumberFormatOverride { _, _ -> NumberFormat(1) }
        assertEquals(NumberFormat(1), manager.formatOverride.formatFor("e", "s"))
        manager.valueConverter = ValueConverter { 4 }
        assertEquals(4, manager.valueConverter.convert(Any()))
    }

    @Test
    fun `the trailing lambda configures the manager before it is returned`() {
        val stats = MapStats().also { it["p", "hp"] = 7.5f }
        val log = LogSink()
        val events = ArrayList<String>()
        val manager = StoryManager(compileLib("~ emit(\"hello\")\n{stat_text_of(\"p\", \"hp\")}")) {
            stateProvider = stats
            this.log = log
            numberFormat = NumberFormat(1)
            variableNamer = VariableNamer { entity, stat -> "v_${entity}_$stat" }
            onEvent = StoryEventListener { events += it.name }
        }
        assertSame(stats, manager.stateProvider)
        assertSame(log, manager.log)
        assertEquals("v_p_hp", manager.variableNamer.nameFor("p", "hp"))
        assertEquals(listOf("7.5"), manager.next().texts())
        assertEquals(listOf("hello"), events)
    }

    @Test
    fun `forPlainStory takes the same lambda and is not lookahead safe`() {
        val plain = com.bladecoder.ink.runtime.Story(compileInk("Hi").toJson())
        val manager = StoryManager.forPlainStory(plain) { speakerTagKey = "who" }
        assertEquals("who", manager.speakerTagKey)
        assertFalse(manager.isLookaheadSafe)
    }

    @Test
    fun `Tags parse gives the shared EMPTY for nothing`() {
        assertSame(Tags.empty, Tags.parse(emptyList()))
        assertSame(Tags.empty, Tags.parse(listOf("  ", "")))
        assertEquals(Tags.parse(listOf("a: 1")), Tags.parse(listOf(" a: 1 ")))
        assertEquals("1", Tags.parse(listOf("a: 1"))["a"])
    }

    @Test
    fun `InkSource lazy reads only when asked`() {
        var reads = 0
        val source = InkSource.lazy("a.ink") { reads++; "Hi" }
        assertEquals(0, reads)
        assertEquals("Hi", source.read())
        assertEquals(1, reads)
        assertNull(source.sibling)
        assertEquals("b", InkSource.lazy("a.ink", sibling = { InkSource("b", "x") }) { "" }.sibling!!("q")!!.name)
    }

    @Test
    fun `StoryValidator validates a story that has no manager`() {
        val ok = StoryValidator.validate(compileLib("Hi"))
        assertTrue(ok.isOk)
        val bad = StoryValidator.validate(compileInk("EXTERNAL gone()\n~ gone()\nHi"))
        assertFalse(bad.isOk)
    }

    @Test
    fun `requirement results carry only the fields that apply and a stable raw`() {
        val manager = managerOf("* [Go # requires: hp > 3, nope > 1, hp > 99, ??] -> END", MapStats().also { it["p", "hp"] = 5 })
        manager.speaker = "p"
        val results = manager.next().choices.single().requirements
        assertIs<RequirementResult.Met>(results[0])
        assertIs<RequirementResult.MissingStat>(results[1])
        assertIs<RequirementResult.Unmet>(results[2])
        assertIs<RequirementResult.Unreadable>(results[3])
        assertEquals(listOf("hp > 3", "nope > 1", "hp > 99", "??"), results.map { it.raw })
        assertEquals(listOf(true, false, false, false), results.map { it.isMet })
    }
}
