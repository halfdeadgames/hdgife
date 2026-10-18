package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.Comparison
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.Tags
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialogTest {
    private enum class Mood { CALM, ANGRY }

    // ---- glue and lookahead ----

    @Test
    fun `glue joins across a stat call`() {
        val stats = MapStats().also { it["p", "hp"] = 7 }
        val m = managerOf("You have\n~ temp hp = stat_of(\"p\", \"hp\")\n<> {hp} hp.", stats)
        assertEquals(listOf("You have 7 hp."), m.next().texts())
    }

    @Test
    fun `glue joins when the stat is printed directly after the break`() {
        val stats = MapStats().also { it["p", "hp"] = 7 }
        val m = managerOf("Health:\n<> {stat_of(\"p\", \"hp\")}!", stats)
        assertEquals(listOf("Health: 7!"), m.next().texts())
    }

    @Test
    fun `glue joins across stat_text and fmt calls`() {
        val stats = MapStats().also { it["p", "hp"] = 7.25f }
        val m = managerOf("Now\n<> {stat_text_of(\"p\", \"hp\")} / {fmt(1.5, 0, \"floor\")}", stats)
        m.numberFormat = com.github.halfdeadgames.hdgife.story.NumberFormat(1)
        assertEquals(listOf("Now 7.3 / 1"), m.next().texts())
    }

    @Test
    fun `stats work in choice text`() {
        val stats = MapStats().also { it["p", "gold"] = 50 }
        val m = managerOf("* [Pay {stat_of(\"p\", \"gold\")} gold] Paid -> END", stats)
        assertEquals("Pay 50 gold", m.next().choices.single().text)
    }

    @Test
    fun `stat_text and fmt work in choice text`() {
        val stats = MapStats().also { it["p", "gold"] = 50.55f }
        val m = managerOf("* [Pay {stat_text_of(\"p\", \"gold\")} or {fmt(2.5, 0, \"floor\")}] Paid -> END", stats)
        m.numberFormat = com.github.halfdeadgames.hdgife.story.NumberFormat(1)
        assertEquals("Pay 50.6 or 2", m.next().choices.single().text)
    }

    @Test
    fun `stats work in choice text after glue`() {
        val stats = MapStats().also { it["p", "gold"] = 5 }
        val m = managerOf("Wait\n<> now\n* [Pay {stat_of(\"p\", \"gold\")}] -> END", stats)
        val step = m.next()
        assertEquals(listOf("Wait now"), step.texts())
        assertEquals("Pay 5", step.choices.single().text)
    }

    @Test
    fun `stat functions are lookahead safe on a compiled story`() {
        assertTrue(managerOf("Hi").lookaheadSafe)
    }

    // ---- speakers ----

    @Test
    fun `a speaker tag names one line only`() {
        val m = managerOf("Halt. # speaker: guard\nMove along.")
        val lines = m.next().lines
        assertEquals("guard", lines[0].speaker)
        assertNull(lines[1].speaker)
    }

    @Test
    fun `speak sets the persistent speaker for following lines`() {
        val m = managerOf("~ speak(\"guard\")\nHalt.\nMove along.\n~ speak(\"\")\nSilence.")
        val lines = m.next().lines
        assertEquals(listOf("guard", "guard", null), lines.map { it.speaker })
        assertNull(m.speaker)
    }

    @Test
    fun `a tag speaker wins over the persistent speaker for its line`() {
        val m = managerOf("~ speak(\"guard\")\nHalt. # speaker: merchant\nMove along.")
        assertEquals(listOf("merchant", "guard"), m.next().lines.map { it.speaker })
    }

    @Test
    fun `speak changes whose stats stat reads`() {
        val stats = MapStats().also { it["guard", "mood"] = 10; it["merchant", "mood"] = 90 }
        val m = managerOf(
            "~ speak(\"guard\")\n{stat(\"mood\")}\n~ speak(\"merchant\")\n{stat(\"mood\")}",
            stats,
        )
        assertEquals(listOf("10", "90"), m.next().texts())
    }

    @Test
    fun `the speaker tag key can be changed`() {
        val m = managerOf("Halt. # who: guard")
        m.speakerTagKey = "who"
        assertEquals("guard", m.next().lines.single().speaker)
    }

    @Test
    fun `beginScene sets the speaker and jumps to the knot`() {
        val m = managerOf("-> END\n=== scene ===\nHello\n-> END")
        m.beginScene("scene", "guard")
        val step = m.next()
        assertEquals("guard", step.lines.single().speaker)
        assertEquals("guard", m.speaker)
    }

    @Test
    fun `the speaker survives save and load`() {
        val m = managerOf("~ speak(\"guard\")\nHalt.\n* [Go] -> END")
        m.next()
        val saved = m.saveState()
        val other = StoryManager(compiler.loadCompiled(m.story.toJson()))
        assertNull(other.speaker)
        other.loadState(saved)
        assertEquals("guard", other.speaker)
    }

    @Test
    fun `the speaker set by the game survives save and load`() {
        val m = managerOf("Hi\n* [Go] Bye -> END")
        m.speaker = "merchant"
        m.next()
        val saved = m.saveState()
        m.speaker = null
        m.loadState(saved)
        assertEquals("merchant", m.speaker)
    }

    // ---- tags ----

    @Test
    fun `tags parse key value pairs`() {
        val tags = Tags.parse(listOf("speaker: guard", " mood :  angry "))
        assertEquals("guard", tags["speaker"])
        assertEquals("angry", tags["mood"])
        assertEquals(listOf("speaker: guard", "mood :  angry"), tags.raw)
    }

    @Test
    fun `a bare tag has an empty value`() {
        val tags = Tags.parse(listOf("shout"))
        assertEquals("", tags["shout"])
        assertTrue(tags.has("shout"))
        assertFalse(tags.has("whisper"))
        assertNull(tags["whisper"])
    }

    @Test
    fun `a colon after prose does not make a key`() {
        val tags = Tags.parse(listOf("Note to self: buy milk"))
        assertEquals(setOf("Note to self: buy milk"), tags.map.keys)
    }

    @Test
    fun `a repeated key keeps the last value in the map and all in all()`() {
        val tags = Tags.parse(listOf("tag: a", "tag: b", "tag: c"))
        assertEquals("c", tags["tag"])
        assertEquals(listOf("a", "b", "c"), tags.all("tag"))
    }

    @Test
    fun `tag keys are case sensitive`() {
        val tags = Tags.parse(listOf("Speaker: guard"))
        assertNull(tags["speaker"])
        assertEquals("guard", tags["Speaker"])
    }

    @Test
    fun `only the first colon splits a tag`() {
        assertEquals("a:b", Tags.parse(listOf("key: a:b"))["key"])
    }

    @Test
    fun `blank tags are dropped and an empty list gives EMPTY`() {
        assertTrue(Tags.parse(listOf("  ", "")).isEmpty)
        assertEquals(Tags.EMPTY, Tags.parse(emptyList()))
    }

    @Test
    fun `line tags include tags written on the line above`() {
        val m = managerOf("# above: yes\nText # inline: also")
        val tags = m.next().lines.single().tags
        assertEquals("yes", tags["above"])
        assertEquals("also", tags["inline"])
    }

    @Test
    fun `step tags collect the tags of every line`() {
        val m = managerOf("One # a: 1\nTwo # b: 2")
        val tags = m.next().tags
        assertEquals("1", tags["a"])
        assertEquals("2", tags["b"])
    }

    @Test
    fun `choice tags come from inside the choice text`() {
        val m = managerOf("* [Go # style: loud] -> END")
        assertEquals("loud", m.next().choices.single().tags["style"])
    }

    // ---- choice requirements ----

    private fun requirement(clause: String, stats: MapStats, speaker: String? = "p") =
        managerOf("* [Go # requires: $clause] -> END", stats).also { it.speaker = speaker }.next().choices.single()

    private fun statsOf(vararg pairs: Pair<String, Any?>) = MapStats().also { s ->
        for ((k, v) in pairs) {
            val (entity, stat) = k.split('.')
            s[entity, stat] = v
        }
    }

    @Test
    fun `a numeric requirement is met`() {
        val c = requirement("strength >= 12", statsOf("p.strength" to 12))
        assertTrue(c.available)
        val r = c.requirements.single()
        assertEquals(Comparison.GREATER_EQUAL, r.op)
        assertEquals(12, r.target)
        assertEquals(12, r.actual)
        assertEquals("p", r.entity)
    }

    @Test
    fun `a numeric requirement is unmet`() {
        val c = requirement("strength >= 12", statsOf("p.strength" to 11))
        assertFalse(c.available)
        assertFalse(c.requirements.single().mismatch)
    }

    @Test
    fun `every comparison works on numbers`() {
        val stats = statsOf("p.x" to 5)
        fun met(clause: String) = requirement(clause, stats).available
        assertTrue(met("x > 4"))
        assertFalse(met("x > 5"))
        assertTrue(met("x >= 5"))
        assertTrue(met("x < 6"))
        assertFalse(met("x < 5"))
        assertTrue(met("x <= 5"))
        assertTrue(met("x == 5"))
        assertTrue(met("x != 4"))
        assertFalse(met("x != 5"))
    }

    @Test
    fun `a float stat compares with a decimal target`() {
        assertTrue(requirement("mood > 30.5", statsOf("p.mood" to 30.6f)).available)
        assertFalse(requirement("mood > 30.5", statsOf("p.mood" to 30.4f)).available)
        assertTrue(requirement("mood == 0.1", statsOf("p.mood" to 0.1f)).available)
    }

    @Test
    fun `a string requirement compares bare or quoted values`() {
        val stats = statsOf("p.faction" to "rebels")
        assertTrue(requirement("faction == rebels", stats).available)
        assertTrue(requirement("faction == \"rebels\"", stats).available)
        assertTrue(requirement("faction == 'rebels'", stats).available)
        assertFalse(requirement("faction == crown", stats).available)
        assertTrue(requirement("faction != crown", stats).available)
    }

    @Test
    fun `an enum stat compares as its name`() {
        assertTrue(requirement("mood == ANGRY", statsOf("p.mood" to Mood.ANGRY)).available)
    }

    @Test
    fun `a boolean requirement works with equality`() {
        val stats = statsOf("p.is_injured" to false)
        assertTrue(requirement("is_injured == false", stats).available)
        assertFalse(requirement("is_injured == true", stats).available)
        assertTrue(requirement("is_injured != true", stats).available)
    }

    @Test
    fun `an explicit entity overrides the speaker`() {
        val stats = statsOf("guard.mood" to 50, "p.mood" to 5)
        val c = requirement("guard.mood > 30", stats)
        assertTrue(c.available)
        assertEquals("guard", c.requirements.single().entity)
    }

    @Test
    fun `a requirement without entity or speaker is unmet`() {
        val c = requirement("mood > 30", statsOf("p.mood" to 50), speaker = null)
        assertFalse(c.available)
        assertNull(c.requirements.single().entity)
    }

    @Test
    fun `a missing stat is unmet and has no actual value`() {
        val c = requirement("strength >= 1", MapStats())
        assertFalse(c.available)
        val r = c.requirements.single()
        assertNull(r.actual)
        assertFalse(r.mismatch)
        assertTrue(r.valid)
    }

    @Test
    fun `comparing a number stat to text is a mismatch`() {
        val sink = LogSink()
        val m = managerOf("* [Go # requires: strength >= high] -> END", statsOf("p.strength" to 5))
        m.speaker = "p"
        m.log = sink
        val r = m.next().choices.single().requirements.single()
        assertTrue(r.mismatch)
        assertFalse(r.met)
        assertContains(sink.messages.joinToString(), "strength >= high")
    }

    @Test
    fun `an ordering operator on a string is a mismatch`() {
        val r = requirement("faction > 3", statsOf("p.faction" to "rebels")).requirements.single()
        assertTrue(r.mismatch)
        assertFalse(r.met)
    }

    @Test
    fun `a boolean compared to a number is a mismatch`() {
        val r = requirement("is_injured == 4", statsOf("p.is_injured" to true)).requirements.single()
        assertTrue(r.mismatch)
    }

    @Test
    fun `an ordering operator on a boolean is a mismatch`() {
        val r = requirement("is_injured > true", statsOf("p.is_injured" to true)).requirements.single()
        assertTrue(r.mismatch)
    }

    @Test
    fun `an unreadable requirement is reported as invalid and unmet`() {
        val sink = LogSink()
        val m = managerOf("* [Go # requires: strength] -> END", statsOf("p.strength" to 5))
        m.speaker = "p"
        m.log = sink
        val choice = m.next().choices.single()
        val r = choice.requirements.single()
        assertFalse(r.valid)
        assertFalse(r.met)
        assertNull(r.op)
        assertEquals("strength", r.raw)
        assertFalse(choice.available)
        assertContains(sink.messages.joinToString(), "cannot read 'strength'")
    }

    @Test
    fun `a clause with an unknown operator is invalid`() {
        val r = requirement("strength => 3", statsOf("p.strength" to 5)).requirements.single()
        assertFalse(r.valid)
    }

    @Test
    fun `several clauses must all be met`() {
        val stats = statsOf("p.a" to 1, "p.b" to 1)
        val c = requirement("a >= 1, b >= 2", stats)
        assertEquals(2, c.requirements.size)
        assertFalse(c.available)
        assertTrue(requirement("a >= 1, b >= 1", stats).available)
    }

    @Test
    fun `a choice with no requirement is available`() {
        val c = managerOf("* [Go] -> END").next().choices.single()
        assertTrue(c.requirements.isEmpty())
        assertTrue(c.available)
    }

    @Test
    fun `unavailable choices are still reported and can be chosen`() {
        val m = managerOf("* [Go # requires: strength >= 99] Went -> END", statsOf("p.strength" to 1))
        m.speaker = "p"
        val step = m.next()
        assertFalse(step.choices.single().available)
        assertEquals(listOf("Went"), m.choose(0).texts())
    }

    @Test
    fun `the requirements tag key can be changed`() {
        val m = managerOf("* [Go # needs: strength >= 5] -> END", statsOf("p.strength" to 1))
        m.speaker = "p"
        m.requirementsTagKey = "needs"
        assertFalse(m.next().choices.single().available)
    }

    @Test
    fun `choose rejects an index that does not exist`() {
        val m = managerOf("* [Go] -> END")
        m.next()
        kotlin.test.assertFailsWith<IllegalArgumentException> { m.choose(3) }
    }
}
