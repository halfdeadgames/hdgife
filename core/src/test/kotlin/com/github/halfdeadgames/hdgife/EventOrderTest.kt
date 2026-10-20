package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.StoryEventListener
import com.github.halfdeadgames.hdgife.story.RequirementResult
import com.github.halfdeadgames.hdgife.story.StoryEvent
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.StoryStepException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventOrderTest {
    private val shop = """
        Pay up.
        ~ emit2("spend", "coin", 3)
        * [Buy # requires: player.coin >= 3] Bought -> END
        * [Leave] Left -> END
    """.trimIndent()

    private fun spendingManager(coin: Int): StoryManager {
        val stats = MapStats().also { it["player", "coin"] = coin }
        val manager = managerOf(shop, stats)
        manager.onEvent = StoryEventListener { event ->
            if (event.name == "spend") stats["player", "coin"] = (stats.getStat("player", "coin") as Int) - (event.args[1] as Int)
        }
        return manager
    }

    @Test
    fun `choice requirements see what onEvent changed`() {
        val step = spendingManager(5).next()
        assertEquals(listOf(false, true), step.choices.map { it.isAvailable })
        assertEquals(2, assertIs<RequirementResult.Unmet>(step.choices[0].requirements.single()).actual)
    }

    @Test
    fun `with enough coin left the choice stays available`() {
        assertTrue(spendingManager(6).next().choices[0].isAvailable)
    }

    @Test
    fun `choose delivers events before the choices of its step`() {
        val stats = MapStats().also { it["player", "coin"] = 1 }
        val manager = managerOf(
            "* [Go] Went\n~ emit1(\"grant\", 5)\n* * [Buy # requires: player.coin >= 5] Bought -> END\n* * [Leave] Left -> END",
            stats,
        )
        manager.onEvent = StoryEventListener { stats["player", "coin"] = (stats.getStat("player", "coin") as Int) + (it.args[0] as Int) }
        manager.next()
        assertTrue(manager.choose(0).choices[0].isAvailable)
    }

    @Test
    fun `onEvent runs once per event, in order, and the step lists the same events`() {
        val manager = managerOf("A <>\n~ emit(\"x\")\nB\n~ emit1(\"y\", 1)\nC\n~ emit(\"z\")\n* [Go] -> END")
        val seen = ArrayList<StoryEvent>()
        manager.onEvent = StoryEventListener { seen += it }
        val step = manager.next()
        assertEquals(listOf("x", "y", "z"), seen.map { it.name })
        assertEquals(seen, step.events)
    }

    @Test
    fun `an event is delivered when the story ends too`() {
        val manager = managerOf("Hi\n~ emit(\"bye\")\n-> END")
        val seen = ArrayList<String>()
        manager.onEvent = StoryEventListener { seen += it.name }
        assertTrue(manager.next().isEnded)
        assertEquals(listOf("bye"), seen)
    }

    @Test
    fun `an exception in onEvent comes out as a StoryStepException that keeps the step`() {
        val manager = managerOf("Hi\n~ emit(\"a\")\n* [Go] -> END")
        manager.onEvent = StoryEventListener { error("nope") }
        val ex = assertFailsWith<StoryStepException> { manager.next() }
        assertEquals(listOf("a"), ex.step.events.map { it.name })
        assertEquals(listOf("Hi"), ex.step.texts())
    }

    @Test
    fun `ids count emits from one`() {
        val step = managerOf("~ emit(\"a\")\n~ emit1(\"b\", 1)\nLine\n~ emit(\"c\")").next()
        assertEquals(listOf(1, 2, 3), step.events.map { it.id })
    }

    @Test
    fun `ids stay the same through glue lookahead`() {
        val step = managerOf("A <>\n~ emit(\"x\")\nB\n~ emit(\"y\")\nC").next()
        assertEquals(listOf("x" to 1, "y" to 2), step.events.map { it.name to it.id })
    }

    @Test
    fun `ids continue across steps and eventCount follows`() {
        val manager = managerOf("~ emit(\"a\")\n* [Go]\n  ~ emit(\"b\")\n  Done -> END")
        assertEquals(0, manager.eventCount)
        assertEquals(listOf(1), manager.next().events.map { it.id })
        assertEquals(listOf(2), manager.choose(0).events.map { it.id })
        assertEquals(2, manager.eventCount)
    }

    private val twoEvents = "~ emit(\"first\")\nOne\n* [Go]\n  ~ emit(\"second\")\n  Two -> END"

    @Test
    fun `an event replayed after loading an earlier save has the same id`() {
        val manager = managerOf(twoEvents)
        manager.next()
        val save = manager.saveState()
        val before = manager.choose(0).events.single()
        manager.loadState(save)
        assertEquals(1, manager.eventCount)
        val again = manager.choose(0).events.single()
        assertEquals(before, again)
        assertEquals(2, again.id)
    }

    @Test
    fun `a fresh manager on the loaded save gives the same id, and events saved before are not delivered again`() {
        val first = managerOf(twoEvents)
        first.next()
        val save = first.saveState()
        val second = managerOf(twoEvents)
        second.loadState(save)
        assertEquals(1, second.eventCount)
        assertEquals(listOf("second" to 2), second.choose(0).events.map { it.name to it.id })
    }

    @Test
    fun `reset starts the ids again`() {
        val manager = managerOf("~ emit(\"a\")\nHi")
        assertEquals(1, manager.next().events.single().id)
        manager.reset()
        assertEquals(1, manager.next().events.single().id)
    }

    @Test
    fun `a story without the library has no events and an event count of zero`() {
        val manager = StoryManager(compileInk("Hi\n-> END"))
        assertEquals(0, manager.eventCount)
        assertTrue(manager.next().events.isEmpty())
    }
}
