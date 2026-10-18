package com.github.halfdeadgames.hdgife

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

    private fun roll(m: StoryManager, times: Int): List<String> {
        val out = ArrayList<String>()
        var step = m.next()
        repeat(times) {
            out += step.texts()
            step = m.choose(0)
        }
        return out
    }

    @Test
    fun `saveState and loadState restore variables and position`() {
        val m = managerOf(loopInk)
        m.next()
        m.choose(0)
        val saved = m.saveState()
        m.choose(0)
        m.choose(0)
        assertEquals(4, m.variable("count"))
        m.loadState(saved)
        assertEquals(2, m.variable("count"))
        assertTrue(m.next().texts().isEmpty(), "choices are pending, so no new lines")
        assertEquals(3, m.choose(0).texts().single().substringAfter("count ").toInt())
    }

    @Test
    fun `state saved by one manager loads into another on the same story`() {
        val first = managerOf(loopInk)
        first.next()
        first.choose(0)
        val saved = first.saveState()
        val second = StoryManager(compiler.loadCompiled(first.story.toJson()))
        second.loadState(saved)
        assertEquals(2, second.variable("count"))
    }

    @Test
    fun `loadState rejects text that is not a state`() {
        val m = managerOf(loopInk)
        assertFailsWith<Exception> { m.loadState("garbage") }
    }

    @Test
    fun `the same seed gives the same random sequence`() {
        val a = managerOf(loopInk).also { assertTrue(it.seedRandom(42)) }
        val b = managerOf(loopInk).also { assertTrue(it.seedRandom(42)) }
        assertEquals(roll(a, 6), roll(b, 6))
    }

    @Test
    fun `different seeds give different sequences`() {
        val a = managerOf(loopInk).also { it.seedRandom(1) }
        val b = managerOf(loopInk).also { it.seedRandom(2) }
        assertNotEquals(roll(a, 6), roll(b, 6))
    }

    @Test
    fun `a saved state carries the seed and the sequence continues exactly`() {
        val m = managerOf(loopInk)
        m.seedRandom(7)
        var step = m.next()
        step = m.choose(0)
        val saved = m.saveState()
        val expected = ArrayList<String>()
        repeat(4) { step = m.choose(0); expected += step.texts() }

        val other = StoryManager(compiler.loadCompiled(m.story.toJson()))
        other.loadState(saved)
        val actual = ArrayList<String>()
        repeat(4) { actual += other.choose(0).texts() }
        assertEquals(expected, actual)
    }

    @Test
    fun `seed property reports the last seed and loadState clears it`() {
        val m = managerOf(loopInk)
        assertNull(m.seed)
        m.seed = 5
        assertEquals(5, m.seed)
        m.loadState(m.saveState())
        assertNull(m.seed)
    }

    @Test
    fun `seedRandom returns false without the library`() {
        val m = managerOf("Hi", lib = false)
        assertFalse(m.seedRandom(1))
        assertFailsWith<IllegalStateException> { m.seed = 1 }
    }

    @Test
    fun `reset restores variables position and speaker`() {
        val m = managerOf(loopInk)
        m.speaker = "guard"
        m.next()
        m.choose(0)
        m.choose(0)
        m.reset()
        assertEquals(0, m.variable("count"))
        assertNull(m.speaker)
        val step = m.next()
        assertEquals(1, step.texts().single().substringAfter("count ").toInt())
    }

    @Test
    fun `reset keeps bindings and settings`() {
        val m = managerOf("{twice(4)}\n-> END\nEXTERNAL twice(x)\n=== function twice(x) ===\n~ return 0")
        m.bindFunction("twice") { it.int(0) * 2 }
        m.next()
        m.reset()
        assertEquals(listOf("8"), m.next().texts())
    }

    @Test
    fun `reset without the library clears the fallback speaker`() {
        val m = managerOf("Hi", lib = false)
        m.speaker = "guard"
        assertEquals("guard", m.speaker)
        m.reset()
        assertNull(m.speaker)
    }

    @Test
    fun `reset reseeds so the old seed no longer applies`() {
        val m = managerOf(loopInk)
        m.seedRandom(3)
        m.reset()
        assertNull(m.seed)
    }

    @Test
    fun `a stat free story needs no provider and no library`() {
        val m = managerOf("Hello\n* [Go] Gone -> END", lib = false)
        val step = m.next()
        assertEquals(listOf("Hello"), step.texts())
        assertEquals(listOf("Gone"), m.choose(0).texts())
    }

    @Test
    fun `a stat free story can save and load`() {
        val m = managerOf("Hello\n* [Go] Gone -> END", lib = false)
        m.next()
        val saved = m.saveState()
        m.choose(0)
        m.loadState(saved)
        assertEquals(listOf("Gone"), m.choose(0).texts())
    }

    @Test
    fun `a story with its own VAR n can include the library`() {
        val m = managerOf("VAR n = 3\nVAR x = 1\nVAR name = \"Ann\"\nn {n} x {x} {name} {stat(\"hp\")}")
        assertEquals(listOf("n 3 x 1 Ann 0.0"), m.next().texts())
        assertEquals(3, m.variable("n"))
    }

    @Test
    fun `library parameter names do not clash with story variables`() {
        val m = managerOf("VAR hdg = 1\n{clamp(15, 0, 10)} {avg2(2, 4)} {fmt(2.5, 0, \"floor\")}")
        assertEquals(listOf("10 3.0 2"), m.next().texts())
    }
}
