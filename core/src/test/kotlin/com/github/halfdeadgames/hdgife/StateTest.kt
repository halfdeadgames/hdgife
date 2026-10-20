package com.github.halfdeadgames.hdgife

import com.bladecoder.ink.runtime.SimpleJson
import com.github.halfdeadgames.hdgife.story.IncompatibleLibraryException
import com.github.halfdeadgames.hdgife.story.SaveStateException
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.StoryManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateTest {
    private val loopInk = """
        VAR count = 0
        -> loop
        === loop ===
        ~ count += 1
        Roll {RANDOM(1, 1000000)} count {count}
        + [again] -> loop
    """.trimIndent()

    private fun roll(manager: StoryManager, times: Int): List<String> {
        val rolled = ArrayList<String>()
        var step = manager.next()
        repeat(times) {
            rolled += step.texts()
            step = manager.choose(0)
        }
        return rolled
    }

    @Test
    fun `saveState and loadState restore variables and position`() {
        val manager = managerOf(loopInk)
        manager.next()
        manager.choose(0)
        val saved = manager.saveState()
        manager.choose(0)
        manager.choose(0)
        assertEquals(4, manager.getVariable("count"))
        manager.loadState(saved)
        assertEquals(2, manager.getVariable("count"))
        assertTrue(manager.next().texts().isEmpty(), "choices are pending, so no new lines")
        assertEquals(3, manager.choose(0).texts().single().substringAfter("count ").toInt())
    }

    @Test
    fun `state saved by one manager loads into another on the same story`() {
        val first = managerOf(loopInk)
        first.next()
        first.choose(0)
        val saved = first.saveState()
        val second = StoryManager(compiler.loadCompiled(first.story.toJson()))
        second.loadState(saved)
        assertEquals(2, second.getVariable("count"))
    }

    @Test
    fun `loadState rejects text that is not a state with a SaveStateException that wraps ink's error`() {
        val manager = managerOf(loopInk)
        val ex = assertFailsWith<SaveStateException> { manager.loadState("garbage") }
        assertTrue(ex.cause != null)
    }

    @Test
    fun `loadState rejects a damaged envelope`() {
        val manager = managerOf(loopInk)
        assertFailsWith<SaveStateException> { manager.loadState("{\"v\":1,\"ink\":5}") }
        assertFailsWith<SaveStateException> { manager.loadState("{\"v\":\"x\",\"ink\":\"{}\"}") }
        assertFailsWith<SaveStateException> { manager.loadState("{\"v\":1,\"ink\":\"{}\",\"styles\":[5]}") }
    }

    @Test
    fun `the same seed gives the same random sequence`() {
        val firstManager = managerOf(loopInk).also { it.seedRandom(42) }
        val secondManager = managerOf(loopInk).also { it.seedRandom(42) }
        assertEquals(roll(firstManager, 6), roll(secondManager, 6))
    }

    @Test
    fun `different seeds give different sequences`() {
        val firstManager = managerOf(loopInk).also { it.seedRandom(1) }
        val secondManager = managerOf(loopInk).also { it.seedRandom(2) }
        assertNotEquals(roll(firstManager, 6), roll(secondManager, 6))
    }

    @Test
    fun `a saved state carries the seed and the sequence continues exactly`() {
        val manager = managerOf(loopInk)
        manager.seedRandom(7)
        var step = manager.next()
        step = manager.choose(0)
        val saved = manager.saveState()
        val expected = ArrayList<String>()
        repeat(4) { step = manager.choose(0); expected += step.texts() }

        val other = StoryManager(compiler.loadCompiled(manager.story.toJson()))
        other.loadState(saved)
        val actual = ArrayList<String>()
        repeat(4) { actual += other.choose(0).texts() }
        assertEquals(expected, actual)
    }

    @Test
    fun `lastSeed reports the last seed and loadState clears it`() {
        val manager = managerOf(loopInk)
        assertNull(manager.lastSeed)
        manager.seedRandom(5)
        assertEquals(5, manager.lastSeed)
        manager.loadState(manager.saveState())
        assertNull(manager.lastSeed)
    }

    @Test
    fun `seedRandom throws without the library`() {
        val manager = managerOf("Hi", lib = false)
        assertFailsWith<IncompatibleLibraryException> { manager.seedRandom(1) }
        assertNull(manager.lastSeed)
    }

    @Test
    fun `reset restores variables position and speaker`() {
        val manager = managerOf(loopInk)
        manager.speaker = "guard"
        manager.next()
        manager.choose(0)
        manager.choose(0)
        manager.reset()
        assertEquals(0, manager.getVariable("count"))
        assertNull(manager.speaker)
        val step = manager.next()
        assertEquals(1, step.texts().single().substringAfter("count ").toInt())
    }

    @Test
    fun `reset keeps bindings and settings`() {
        val manager = managerOf("{twice(4)}\n-> END\nEXTERNAL twice(x)\n=== function twice(x) ===\n~ return 0")
        manager.bindFunction("twice") { it.int(0) * 2 }
        manager.next()
        manager.reset()
        assertEquals(listOf("8"), manager.next().texts())
    }

    @Test
    fun `reset without the library clears the fallback speaker`() {
        val manager = managerOf("Hi", lib = false)
        manager.speaker = "guard"
        assertEquals("guard", manager.speaker)
        manager.reset()
        assertNull(manager.speaker)
    }

    @Test
    fun `reset reseeds so the old seed no longer applies`() {
        val manager = managerOf(loopInk)
        manager.seedRandom(3)
        manager.reset()
        assertNull(manager.lastSeed)
    }

    @Test
    fun `a stat free story needs no provider and no library`() {
        val manager = managerOf("Hello\n* [Go] Gone -> END", lib = false)
        val step = manager.next()
        assertEquals(listOf("Hello"), step.texts())
        assertEquals(listOf("Gone"), manager.choose(0).texts())
    }

    @Test
    fun `a stat free story can save and load`() {
        val manager = managerOf("Hello\n* [Go] Gone -> END", lib = false)
        manager.next()
        val saved = manager.saveState()
        manager.choose(0)
        manager.loadState(saved)
        assertEquals(listOf("Gone"), manager.choose(0).texts())
    }

    @Test
    fun `a story with its own VAR n can include the library`() {
        val manager = managerOf("VAR n = 3\nVAR x = 1\nVAR name = \"Ann\"\nn {n} x {x} {name} {stat(\"hp\")}")
        assertEquals(listOf("n 3 x 1 Ann 0.0"), manager.next().texts())
        assertEquals(3, manager.getVariable("n"))
    }

    @Test
    fun `library parameter names do not clash with story variables`() {
        val manager = managerOf("INCLUDE hdgife_util.ink\nVAR hdg = 1\n{clamp(15, 0, 10)} {avg2(2, 4)} {fmt(2.5, 0, \"floor\")}")
        assertEquals(listOf("10 3.0 2"), manager.next().texts())
    }

    private val styledInk = "He said <color=red>stop\n* [Wait] and <b>go</b> now</color> then.\n-> END"

    @Test
    fun `a style open at save time continues after load`() {
        val manager = managerOf(styledInk)
        val first = manager.next()
        assertEquals(listOf(Style("color", "red")), first.openStyles)
        val saved = manager.saveState()
        val expected = manager.choose(0)

        val other = managerOf(styledInk)
        other.loadState(saved)
        val again = other.choose(0)
        assertEquals(expected.lines, again.lines)
        assertEquals(Style("color", "red"), again.lines.first().spans.first().styles.single())
    }

    @Test
    fun `saving and loading in the same manager restores open styles`() {
        val manager = managerOf(styledInk)
        manager.next()
        val saved = manager.saveState()
        val expected = manager.choose(0)
        manager.loadState(saved)
        assertEquals(expected, manager.choose(0))
    }

    @Test
    fun `style values and params survive a save`() {
        val ink = "<shake amp=2 speed=\"0.5 fast\">a\n<color=ff0000>b\n* [Go] c -> END"
        val manager = managerOf(ink)
        val open = manager.next().openStyles
        assertEquals(2, open.size)
        val other = managerOf(ink)
        other.loadState(manager.saveState())
        assertEquals(open, other.choose(0).lines.single().spans.single().styles)
    }

    @Test
    fun `load of an older save without styles closes open ones`() {
        val manager = managerOf(styledInk)
        manager.next()
        manager.loadState(manager.story.state.toJson())
        val line = manager.choose(0).lines.single()
        assertEquals("and go now then.", line.text)
        assertTrue(line.spans.none { span -> span.styles.any { it.name == "color" } })
    }

    @Test
    fun `the save is a versioned envelope around the ink state`() {
        val manager = managerOf(styledInk)
        manager.next()
        val parsed = SimpleJson.textToDictionary(manager.saveState())
        assertEquals(1, parsed["v"])
        assertTrue(parsed["ink"] is String)
        assertEquals(1, (parsed["styles"] as List<*>).size)
    }

    @Test
    fun `an unsupported save version is refused and nothing changes`() {
        val manager = managerOf(styledInk)
        manager.next()
        val saved = manager.saveState().replace("\"v\":1", "\"v\":99")
        assertFailsWith<SaveStateException> { manager.loadState(saved) }
        val line = manager.choose(0).lines.single()
        assertEquals("and go now then.", line.text)
        assertEquals(Style("color", "red"), line.spans.first().styles.single())
    }

    @Test
    fun `a plain ink state json still loads`() {
        val manager = managerOf("Hello\n* [Go] Gone -> END", lib = false)
        manager.next()
        val plain = manager.story.state.toJson()
        manager.choose(0)
        manager.loadState(plain)
        assertEquals(listOf("Gone"), manager.choose(0).texts())
    }

    @Test
    fun `the fallback speaker is saved without the library`() {
        val manager = managerOf("Hi\n* [Go] Bye -> END", lib = false)
        manager.speaker = "guard"
        manager.next()
        val saved = manager.saveState()
        manager.speaker = "other"
        manager.loadState(saved)
        assertEquals("guard", manager.speaker)
    }
}
