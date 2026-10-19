package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.Comparison
import com.github.halfdeadgames.hdgife.story.RequirementResult
import com.github.halfdeadgames.hdgife.story.validateStory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OR (`|`) and NOT (`!`, `not `) in requires tags. */
class RequirementLogicTest {
    private val stats = MapStats().also {
        it["player", "strength"] = 5
        it["player", "charm"] = 9
        it["player", "is_injured"] = false
        it["player", "faction"] = "a|b"
        it["player", "tag"] = "x,y"
        it["guard", "mood"] = 10
    }

    // Ink reads a bare | inside a choice's brackets itself, so the tag text is written with \|.
    private fun ink(requires: String) = "* [Go # requires: ${requires.replace("|", "\\|")}] -> END"

    private fun choices(requires: String, provider: MapStats = stats) =
        managerOf(ink(requires), provider).next().choices.single()

    private fun only(requires: String) = choices(requires).requirements.single()

    @Test
    fun `an or clause is met when any alternative is`() {
        val r = assertIs<RequirementResult.AnyOf>(only("player.strength >= 12 | player.charm >= 8"))
        assertTrue(r.isMet)
        assertEquals("player.strength >= 12 | player.charm >= 8", r.raw)
        assertIs<RequirementResult.Unmet>(r.results[0])
        assertIs<RequirementResult.Met>(r.results[1])
        assertEquals(listOf("player.strength >= 12", "player.charm >= 8"), r.results.map { it.raw })
    }

    @Test
    fun `an or clause is unmet when none is`() {
        val r = only("player.strength >= 12 | player.charm >= 20")
        assertIs<RequirementResult.AnyOf>(r)
        assertFalse(r.isMet)
        assertFalse(choices("player.strength >= 12 | player.charm >= 20").isAvailable)
    }

    @Test
    fun `negation with a bang and with not`() {
        val bang = assertIs<RequirementResult.Not>(only("!player.is_injured == true"))
        assertTrue(bang.isMet)
        assertIs<RequirementResult.Unmet>(bang.result)
        assertEquals("!player.is_injured == true", bang.raw)
        assertTrue(only("not player.strength > 9").isMet)
        assertFalse(only("!player.strength < 9").isMet)
        assertTrue(only("!!player.strength < 9").isMet)
    }

    @Test
    fun `a negated missing stat, mismatch or unreadable term is not met`() {
        assertFalse(only("!player.luck > 1").isMet)
        assertFalse(only("!player.faction > 3").isMet)
        assertFalse(only("!this is not a clause").isMet)
        assertFalse(only("!").isMet)
        assertIs<RequirementResult.Unreadable>(only("!"))
        // every alternative undecided, so the or is not met
        assertFalse(only("!player.luck > 1 | !player.dust > 1").isMet)
    }

    @Test
    fun `the word or works like a bar`() {
        val r = assertIs<RequirementResult.AnyOf>(only("player.strength >= 12 or player.charm >= 8"))
        assertTrue(r.isMet)
        assertEquals(listOf("player.strength >= 12", "player.charm >= 8"), r.results.map { it.raw })
        assertTrue(choices("player.strength >= 12 or player.charm >= 8, not player.is_injured == true").isAvailable)
        // or inside a name or a quoted value is not a separator
        val provider = MapStats().also { it["player", "color"] = "red or blue"; it["player", "order"] = 1 }
        assertTrue(choices("player.color == \"red or blue\"", provider).isAvailable)
        assertTrue(choices("player.order == 1", provider).isAvailable)
        assertIs<RequirementResult.AnyOf>(only("player.order == 2 or player.color == 'red or blue'"))
    }

    @Test
    fun `the bang applies to one term, so a bar after it starts a new alternative`() {
        // "!" binds to one term, so this is Not(luck) OR Not(strength > 9): the second is met
        val r = only("!player.luck > 1 | !player.strength > 9")
        assertTrue(r.isMet)
    }

    @Test
    fun `commas are and, bars are or, and the bang binds tightest`() {
        assertTrue(choices("player.strength >= 12 | player.charm >= 8, !player.is_injured == true").isAvailable)
        assertFalse(choices("player.strength >= 12 | player.charm >= 8, player.is_injured == true").isAvailable)
        assertFalse(choices("player.strength >= 12 | player.charm >= 20, !player.is_injured == true").isAvailable)
        val results = choices("player.strength >= 12 | player.charm >= 8, !player.is_injured == true").requirements
        assertEquals(2, results.size)
        assertIs<RequirementResult.AnyOf>(results[0])
        assertIs<RequirementResult.Not>(results[1])
    }

    @Test
    fun `bars and commas inside quotes are literal`() {
        val r = assertIs<RequirementResult.AnyOf>(only("player.faction == \"a|b\" | player.strength > 100"))
        assertTrue(r.isMet)
        assertEquals(2, r.results.size)
        val both = choices("player.tag == \"x,y\", player.strength > 1").requirements
        assertEquals(2, both.size)
        assertTrue(both.all { it.isMet })
        assertEquals(Comparison.EQUAL, (both[0] as RequirementResult.Met).op)
    }

    @Test
    fun `an unreadable alternative does not stop another one from being met`() {
        val r = assertIs<RequirementResult.AnyOf>(only("player.strength > 3 | ???"))
        assertTrue(r.isMet)
        assertIs<RequirementResult.Unreadable>(r.results[1])
        assertFalse(only("player.strength > 30 | ???").isMet)
        val empty = assertIs<RequirementResult.AnyOf>(only("player.strength > 3 |"))
        assertIs<RequirementResult.Unreadable>(empty.results[1])
    }

    @Test
    fun `a stat called not still works`() {
        val provider = MapStats().also { it["player", "not"] = 4 }
        assertTrue(choices("player.not > 1", provider).isAvailable)
        assertTrue(choices("not player.not > 9", provider).isAvailable)
    }

    @Test
    fun `speaker is the default entity inside or and not`() {
        val m = managerOf(ink("mood > 50 | !mood > 50"), stats)
        m.speaker = "guard"
        assertTrue(m.next().choices.single().isAvailable)
    }

    @Test
    fun `validation reads or and not clauses and still finds unreadable ones`() {
        fun issues(requires: String) =
            managerOf(ink(requires)).validateStory().of(com.github.halfdeadgames.hdgife.story.IssueKind.UNREADABLE_REQUIREMENT)
        assertTrue(issues("player.strength >= 12 | player.charm >= 8, !player.is_injured == true").isEmpty())
        assertTrue(issues("not player.strength > 3").isEmpty())
        assertEquals("hp > 3 | ???", issues("hp > 3 | ???").single().subject)
        assertEquals(1, issues("hp > 3, !").size)
        assertEquals(1, issues("hp > 3 |").size)
    }

    @Test
    fun `Comparison is stable api`() {
        assertEquals(Comparison.GREATER_EQUAL, Comparison.parse(">="))
        assertTrue(Comparison::class.java.annotations.none { it.annotationClass.simpleName == "ExperimentalHdgifeApi" })
    }
}
