package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.Comparison
import com.github.halfdeadgames.hdgife.story.RequirementResult
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.Tags
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialogTest {
    private enum class Mood { Calm, Angry }

    @Test
    fun `glue joins across a stat call`() {
        val stats = MapStats().also { it["p", "hp"] = 7 }
        val manager = managerOf("You have\n~ temp hp = stat_of(\"p\", \"hp\")\n<> {hp} hp.", stats)
        assertEquals(listOf("You have 7 hp."), manager.next().texts())
    }

    @Test
    fun `glue joins when the stat is printed directly after the break`() {
        val stats = MapStats().also { it["p", "hp"] = 7 }
        val manager = managerOf("Health:\n<> {stat_of(\"p\", \"hp\")}!", stats)
        assertEquals(listOf("Health: 7!"), manager.next().texts())
    }

    @Test
    fun `glue joins across stat_text and fmt calls`() {
        val stats = MapStats().also { it["p", "hp"] = 7.25f }
        val manager = managerOf("Now\n<> {stat_text_of(\"p\", \"hp\")} / {fmt(1.5, 0, \"floor\")}", stats)
        manager.numberFormat = com.github.halfdeadgames.hdgife.story.NumberFormat(1)
        assertEquals(listOf("Now 7.3 / 1"), manager.next().texts())
    }

    @Test
    fun `stats work in choice text`() {
        val stats = MapStats().also { it["p", "gold"] = 50 }
        val manager = managerOf("* [Pay {stat_of(\"p\", \"gold\")} gold] Paid -> END", stats)
        assertEquals("Pay 50 gold", manager.next().choices.single().text)
    }

    @Test
    fun `stat_text and fmt work in choice text`() {
        val stats = MapStats().also { it["p", "gold"] = 50.55f }
        val manager = managerOf("* [Pay {stat_text_of(\"p\", \"gold\")} or {fmt(2.5, 0, \"floor\")}] Paid -> END", stats)
        manager.numberFormat = com.github.halfdeadgames.hdgife.story.NumberFormat(1)
        assertEquals("Pay 50.6 or 2", manager.next().choices.single().text)
    }

    @Test
    fun `stats work in choice text after glue`() {
        val stats = MapStats().also { it["p", "gold"] = 5 }
        val manager = managerOf("Wait\n<> now\n* [Pay {stat_of(\"p\", \"gold\")}] -> END", stats)
        val step = manager.next()
        assertEquals(listOf("Wait now"), step.texts())
        assertEquals("Pay 5", step.choices.single().text)
    }

    @Test
    fun `stat functions are lookahead safe on a compiled story`() {
        assertTrue(managerOf("Hi").isLookaheadSafe)
    }

    @Test
    fun `lookaheadSafe is a mode decided by the entry point`() {
        val plain = com.bladecoder.ink.runtime.Story(compileLib("Hi").toJson())
        val manager = StoryManager.forPlainStory(plain)
        assertFalse(manager.isLookaheadSafe)
        manager.replaceStory(compileLib("Hi"))
        assertTrue(manager.isLookaheadSafe)
        manager.replacePlainStory(plain)
        assertFalse(manager.isLookaheadSafe)
    }

    @Test
    fun `a speaker tag names one line only`() {
        val manager = managerOf("Halt. # speaker: guard\nMove along.")
        val lines = manager.next().lines
        assertEquals("guard", lines[0].speaker)
        assertNull(lines[1].speaker)
    }

    @Test
    fun `speak sets the persistent speaker for following lines`() {
        val manager = managerOf("~ speak(\"guard\")\nHalt.\nMove along.\n~ speak(\"\")\nSilence.")
        val lines = manager.next().lines
        assertEquals(listOf("guard", "guard", null), lines.map { it.speaker })
        assertNull(manager.speaker)
    }

    @Test
    fun `a tag speaker wins over the persistent speaker for its line`() {
        val manager = managerOf("~ speak(\"guard\")\nHalt. # speaker: merchant\nMove along.")
        assertEquals(listOf("merchant", "guard"), manager.next().lines.map { it.speaker })
    }

    @Test
    fun `speak changes whose stats stat reads`() {
        val stats = MapStats().also { it["guard", "mood"] = 10; it["merchant", "mood"] = 90 }
        val manager = managerOf(
            "~ speak(\"guard\")\n{stat(\"mood\")}\n~ speak(\"merchant\")\n{stat(\"mood\")}",
            stats,
        )
        assertEquals(listOf("10", "90"), manager.next().texts())
    }

    @Test
    fun `the speaker tag key can be changed`() {
        val manager = managerOf("Halt. # who: guard")
        manager.speakerTagKey = "who"
        assertEquals("guard", manager.next().lines.single().speaker)
    }

    @Test
    fun `beginScene sets the speaker and jumps to the knot`() {
        val manager = managerOf("-> END\n=== scene ===\nHello\n-> END")
        manager.beginScene("scene", "guard")
        val step = manager.next()
        assertEquals("guard", step.lines.single().speaker)
        assertEquals("guard", manager.speaker)
    }

    @Test
    fun `the speaker survives save and load`() {
        val manager = managerOf("~ speak(\"guard\")\nHalt.\n* [Go] -> END")
        manager.next()
        val saved = manager.saveState()
        val other = StoryManager(compiler.loadCompiled(manager.story.toJson()))
        assertNull(other.speaker)
        other.loadState(saved)
        assertEquals("guard", other.speaker)
    }

    @Test
    fun `the speaker set by the game survives save and load`() {
        val manager = managerOf("Hi\n* [Go] Bye -> END")
        manager.speaker = "merchant"
        manager.next()
        val saved = manager.saveState()
        manager.speaker = null
        manager.loadState(saved)
        assertEquals("merchant", manager.speaker)
    }

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
        assertEquals(Tags.empty, Tags.parse(emptyList()))
    }

    @Test
    fun `line tags include tags written on the line above`() {
        val manager = managerOf("# above: yes\nText # inline: also")
        val tags = manager.next().lines.single().tags
        assertEquals("yes", tags["above"])
        assertEquals("also", tags["inline"])
    }

    @Test
    fun `step tags collect the tags of every line`() {
        val manager = managerOf("One # a: 1\nTwo # b: 2")
        val tags = manager.next().tags
        assertEquals("1", tags["a"])
        assertEquals("2", tags["b"])
    }

    @Test
    fun `choice tags come from inside the choice text`() {
        val manager = managerOf("* [Go # style: loud] -> END")
        assertEquals("loud", manager.next().choices.single().tags["style"])
    }

    private fun requirement(clause: String, stats: MapStats, speaker: String? = "p") =
        managerOf("* [Go # requires: $clause] -> END", stats).also { it.speaker = speaker }.next().choices.single()

    private fun statsOf(vararg pairs: Pair<String, Any?>) = MapStats().also { provider ->
        for ((key, value) in pairs) {
            val (entity, stat) = key.split('.')
            provider[entity, stat] = value
        }
    }

    @Test
    fun `a numeric requirement is met`() {
        val choice = requirement("strength >= 12", statsOf("p.strength" to 12))
        assertTrue(choice.isAvailable)
        val requirementResult = assertIs<RequirementResult.Met>(choice.requirements.single())
        assertEquals(Comparison.GreaterEqual, requirementResult.op)
        assertEquals(12, requirementResult.target)
        assertEquals(12, requirementResult.actual)
        assertEquals("p", requirementResult.entity)
    }

    @Test
    fun `a numeric requirement is unmet`() {
        val choice = requirement("strength >= 12", statsOf("p.strength" to 11))
        assertFalse(choice.isAvailable)
        assertIs<RequirementResult.Unmet>(choice.requirements.single())
    }

    @Test
    fun `every comparison works on numbers`() {
        val stats = statsOf("p.x" to 5)
        fun met(clause: String) = requirement(clause, stats).isAvailable
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
        assertTrue(requirement("mood > 30.5", statsOf("p.mood" to 30.6f)).isAvailable)
        assertFalse(requirement("mood > 30.5", statsOf("p.mood" to 30.4f)).isAvailable)
        assertTrue(requirement("mood == 0.1", statsOf("p.mood" to 0.1f)).isAvailable)
    }

    @Test
    fun `a string requirement compares bare or quoted values`() {
        val stats = statsOf("p.faction" to "rebels")
        assertTrue(requirement("faction == rebels", stats).isAvailable)
        assertTrue(requirement("faction == \"rebels\"", stats).isAvailable)
        assertTrue(requirement("faction == 'rebels'", stats).isAvailable)
        assertFalse(requirement("faction == crown", stats).isAvailable)
        assertTrue(requirement("faction != crown", stats).isAvailable)
    }

    @Test
    fun `an enum stat compares as its name`() {
        assertTrue(requirement("mood == Angry", statsOf("p.mood" to Mood.Angry)).isAvailable)
    }

    @Test
    fun `a boolean requirement works with equality`() {
        val stats = statsOf("p.is_injured" to false)
        assertTrue(requirement("is_injured == false", stats).isAvailable)
        assertFalse(requirement("is_injured == true", stats).isAvailable)
        assertTrue(requirement("is_injured != true", stats).isAvailable)
    }

    @Test
    fun `an explicit entity overrides the speaker`() {
        val stats = statsOf("guard.mood" to 50, "p.mood" to 5)
        val choice = requirement("guard.mood > 30", stats)
        assertTrue(choice.isAvailable)
        assertEquals("guard", assertIs<RequirementResult.Met>(choice.requirements.single()).entity)
    }

    @Test
    fun `a requirement without entity or speaker is unmet`() {
        val choice = requirement("mood > 30", statsOf("p.mood" to 50), speaker = null)
        assertFalse(choice.isAvailable)
        assertNull(assertIs<RequirementResult.MissingStat>(choice.requirements.single()).entity)
    }

    @Test
    fun `a missing stat is unmet and reported as missing`() {
        val choice = requirement("strength >= 1", MapStats())
        assertFalse(choice.isAvailable)
        val requirementResult = assertIs<RequirementResult.MissingStat>(choice.requirements.single())
        assertEquals("strength", requirementResult.stat)
        assertEquals(1, requirementResult.target)
    }

    @Test
    fun `comparing a number stat to text is a mismatch`() {
        val sink = LogSink()
        val manager = managerOf("* [Go # requires: strength >= high] -> END", statsOf("p.strength" to 5))
        manager.speaker = "p"
        manager.log = sink
        val requirementResult = assertIs<RequirementResult.Mismatch>(manager.next().choices.single().requirements.single())
        assertFalse(requirementResult.isMet)
        assertContains(sink.messages.joinToString(), "strength >= high")
    }

    @Test
    fun `an ordering operator on a string is a mismatch`() {
        val requirementResult = assertIs<RequirementResult.Mismatch>(requirement("faction > 3", statsOf("p.faction" to "rebels")).requirements.single())
        assertFalse(requirementResult.isMet)
    }

    @Test
    fun `a boolean compared to a number is a mismatch`() {
        assertIs<RequirementResult.Mismatch>(requirement("is_injured == 4", statsOf("p.is_injured" to true)).requirements.single())
    }

    @Test
    fun `an ordering operator on a boolean is a mismatch`() {
        assertIs<RequirementResult.Mismatch>(requirement("is_injured > true", statsOf("p.is_injured" to true)).requirements.single())
    }

    @Test
    fun `an unreadable requirement is reported as unreadable and unmet`() {
        val sink = LogSink()
        val manager = managerOf("* [Go # requires: strength] -> END", statsOf("p.strength" to 5))
        manager.speaker = "p"
        manager.log = sink
        val choice = manager.next().choices.single()
        val requirementResult = assertIs<RequirementResult.Unreadable>(choice.requirements.single())
        assertFalse(requirementResult.isMet)
        assertEquals("strength", requirementResult.raw)
        assertFalse(choice.isAvailable)
        assertContains(sink.messages.joinToString(), "cannot read 'strength'")
    }

    @Test
    fun `a clause with an unknown operator is invalid`() {
        assertIs<RequirementResult.Unreadable>(requirement("strength => 3", statsOf("p.strength" to 5)).requirements.single())
    }

    @Test
    fun `several clauses must all be met`() {
        val stats = statsOf("p.a" to 1, "p.b" to 1)
        val choice = requirement("a >= 1, b >= 2", stats)
        assertEquals(2, choice.requirements.size)
        assertFalse(choice.isAvailable)
        assertTrue(requirement("a >= 1, b >= 1", stats).isAvailable)
    }

    @Test
    fun `a choice with no requirement is available`() {
        val choice = managerOf("* [Go] -> END").next().choices.single()
        assertTrue(choice.requirements.isEmpty())
        assertTrue(choice.isAvailable)
    }

    @Test
    fun `unavailable choices are still reported and can be chosen`() {
        val manager = managerOf("* [Go # requires: strength >= 99] Went -> END", statsOf("p.strength" to 1))
        manager.speaker = "p"
        val step = manager.next()
        assertFalse(step.choices.single().isAvailable)
        assertEquals(listOf("Went"), manager.choose(0).texts())
    }

    @Test
    fun `the requirements tag key can be changed`() {
        val manager = managerOf("* [Go # needs: strength >= 5] -> END", statsOf("p.strength" to 1))
        manager.speaker = "p"
        manager.requirementsTagKey = "needs"
        assertFalse(manager.next().choices.single().isAvailable)
    }

    @Test
    fun `choose rejects an index that does not exist`() {
        val manager = managerOf("* [Go] -> END")
        manager.next()
        kotlin.test.assertFailsWith<IllegalArgumentException> { manager.choose(3) }
    }
}
