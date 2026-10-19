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

/** API review round two: NumberFormat, callback types, the configuration lambda, Tags, InkSource.lazy, StoryValidator. */
class ApiSurfaceTest {
    // ---- NumberFormat ----

    @Test
    fun `NumberFormat has value equality and with-copies`() {
        val base = NumberFormat(2, Rounding.FLOOR, true)
        assertEquals(NumberFormat(2, Rounding.FLOOR, true), base)
        assertEquals(NumberFormat(2, Rounding.FLOOR, true).hashCode(), base.hashCode())
        assertNotEquals(base, base.withDecimals(3))
        assertEquals(3, base.withDecimals(3).decimals)
        assertEquals(Rounding.CEIL, base.withRounding(Rounding.CEIL).rounding)
        assertFalse(base.withTrimTrailingZeros(false).trimTrailingZeros)
        assertEquals(NumberFormat(), NumberFormat(0, Rounding.ROUND, false))
        assertEquals("NumberFormat(decimals=2, rounding=FLOOR, trimTrailingZeros=true)", base.toString())
    }

    @Test
    fun `NumberFormat rejects decimals out of range through withDecimals`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { NumberFormat().withDecimals(10) }
    }

    // ---- callback types ----

    @Test
    fun `callback properties default to no-ops and accept both assignment styles`() {
        val m = managerOf("Hi")
        m.onEvent.onEvent(StoryEvent("x", emptyList(), 0))
        assertNull(m.formatOverride.formatFor("e", "s"))
        assertNull(m.valueConverter.convert(Any()))

        val seen = ArrayList<String>()
        m.onEvent = StoryEventListener { seen += it.name }
        m.onEvent.onEvent(StoryEvent("a", emptyList(), 0))
        m.onEvent = StoryEventListener { seen += "b" }
        m.onEvent.onEvent(StoryEvent("a", emptyList(), 0))
        assertEquals(listOf("a", "b"), seen)

        m.formatOverride = NumberFormatOverride { _, _ -> NumberFormat(1) }
        assertEquals(NumberFormat(1), m.formatOverride.formatFor("e", "s"))
        m.valueConverter = ValueConverter { 4 }
        assertEquals(4, m.valueConverter.convert(Any()))
    }

    // ---- configuration lambda ----

    @Test
    fun `the trailing lambda configures the manager before it is returned`() {
        val stats = MapStats().also { it["p", "hp"] = 7.5f }
        val log = LogSink()
        val events = ArrayList<String>()
        val m = StoryManager(compileLib("~ emit(\"hello\")\n{stat_text_of(\"p\", \"hp\")}")) {
            stateProvider = stats
            this.log = log
            numberFormat = NumberFormat(1)
            variableNamer = VariableNamer { entity, stat -> "v_${entity}_$stat" }
            onEvent = StoryEventListener { events += it.name }
        }
        assertSame(stats, m.stateProvider)
        assertSame(log, m.log)
        assertEquals("v_p_hp", m.variableNamer.nameFor("p", "hp"))
        assertEquals(listOf("7.5"), m.next().texts())
        assertEquals(listOf("hello"), events)
    }

    @Test
    fun `forPlainStory takes the same lambda and is not lookahead safe`() {
        val plain = com.bladecoder.ink.runtime.Story(compileInk("Hi").toJson())
        val m = StoryManager.forPlainStory(plain) { speakerTagKey = "who" }
        assertEquals("who", m.speakerTagKey)
        assertFalse(m.isLookaheadSafe)
    }

    // ---- Tags ----

    @Test
    fun `Tags parse gives the shared EMPTY for nothing`() {
        assertSame(Tags.EMPTY, Tags.parse(emptyList()))
        assertSame(Tags.EMPTY, Tags.parse(listOf("  ", "")))
        assertEquals(Tags.parse(listOf("a: 1")), Tags.parse(listOf(" a: 1 ")))
        assertEquals("1", Tags.parse(listOf("a: 1"))["a"])
    }

    // ---- InkSource.lazy ----

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

    // ---- StoryValidator ----

    @Test
    fun `StoryValidator validates a story that has no manager`() {
        val ok = StoryValidator.validate(compileLib("Hi"))
        assertTrue(ok.isOk)
        val bad = StoryValidator.validate(compileInk("EXTERNAL gone()\n~ gone()\nHi"))
        assertFalse(bad.isOk)
    }

    // ---- requirement results ----

    @Test
    fun `requirement results carry only the fields that apply and a stable raw`() {
        val m = managerOf("* [Go # requires: hp > 3, nope > 1, hp > 99, ??] -> END", MapStats().also { it["p", "hp"] = 5 })
        m.speaker = "p"
        val results = m.next().choices.single().requirements
        assertIs<RequirementResult.Met>(results[0])
        assertIs<RequirementResult.MissingStat>(results[1])
        assertIs<RequirementResult.Unmet>(results[2])
        assertIs<RequirementResult.Unreadable>(results[3])
        assertEquals(listOf("hp > 3", "nope > 1", "hp > 99", "??"), results.map { it.raw })
        assertEquals(listOf(true, false, false, false), results.map { it.isMet })
    }
}
