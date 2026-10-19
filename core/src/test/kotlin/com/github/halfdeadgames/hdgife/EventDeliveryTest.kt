package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.FunctionArgs
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.StoryStepException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A throwing onEvent keeps the undelivered tail, and events have typed argument getters. */
class EventDeliveryTest {
    private val three = "~ emit(\"a\")\n~ emit(\"b\")\n~ emit(\"c\")\nHi\n* [Go] -> END"

    /** A manager whose listener records every event and throws once for each name in [names]. */
    private fun managerThrowingOn(text: String, vararg names: String, seen: MutableList<String>): StoryManager {
        val remaining = names.toMutableList()
        return managerOf(text).also { m ->
            m.onEvent { event ->
                seen += event.name
                if (remaining.remove(event.name)) error("boom ${event.name}")
            }
        }
    }

    @Test
    fun `the tail after a throwing event is delivered first on the next call, once and in order`() {
        val seen = ArrayList<String>()
        val m = managerThrowingOn(three, "a", seen = seen)
        val ex = assertFailsWith<StoryStepException> { m.next() }
        assertEquals(listOf("a", "b", "c"), ex.step.events.map { it.name })
        assertEquals(listOf("a"), seen)

        val step = m.next()
        assertEquals(listOf("a", "b", "c"), seen)
        assertEquals(listOf("b", "c"), step.events.map { it.name })
        assertEquals(listOf(0, 0), step.events.map { it.lineIndex })
        assertEquals(listOf(2, 3), step.events.map { it.id })
        assertEquals(1, step.choices.size)

        assertTrue(m.next().events.isEmpty())
        assertEquals(listOf("a", "b", "c"), seen)
    }

    @Test
    fun `a throw in the middle leaves only the events after it`() {
        val seen = ArrayList<String>()
        val m = managerThrowingOn(three, "b", seen = seen)
        assertFailsWith<StoryStepException> { m.next() }
        assertEquals(listOf("a", "b"), seen)
        assertEquals(listOf("c"), m.next().events.map { it.name })
        assertEquals(listOf("a", "b", "c"), seen)
    }

    @Test
    fun `a second throw keeps what is still left`() {
        val seen = ArrayList<String>()
        val m = managerThrowingOn(three, "a", "b", seen = seen)
        assertFailsWith<StoryStepException> { m.next() }
        assertFailsWith<StoryStepException> { m.next() }
        assertEquals(listOf("a", "b"), seen)
        assertEquals(listOf("c"), m.next().events.map { it.name })
        assertEquals(listOf("a", "b", "c"), seen)
    }

    @Test
    fun `the leftover comes before the events of the next step`() {
        val seen = ArrayList<String>()
        val m = managerThrowingOn("~ emit(\"a\")\n~ emit(\"b\")\nHi\n* [Go]\n  ~ emit(\"c\")\n  Bye -> END", "a", seen = seen)
        assertFailsWith<StoryStepException> { m.next() }
        val step = m.choose(0)
        assertEquals(listOf("a", "b", "c"), seen)
        assertEquals(listOf("b", "c"), step.events.map { it.name })
        assertEquals(listOf("Bye"), step.texts())
    }

    @Test
    fun `reset drops the leftover`() {
        val seen = ArrayList<String>()
        val m = managerThrowingOn(three, "a", seen = seen)
        assertFailsWith<StoryStepException> { m.next() }
        m.reset()
        seen.clear()
        // the story runs again from the start: a, b and c come as new events, once each
        assertEquals(listOf("a", "b", "c"), m.next().events.map { it.name })
        assertEquals(listOf("a", "b", "c"), seen)
    }

    @Test
    fun `loadState drops the leftover`() {
        val seen = ArrayList<String>()
        val m = managerThrowingOn("~ emit(\"a\")\n~ emit(\"b\")\nHi\n* [Go] -> END", "a", seen = seen)
        val before = m.saveState()
        assertFailsWith<StoryStepException> { m.next() }
        m.loadState(before)
        seen.clear()
        assertEquals(listOf("a", "b"), m.next().events.map { it.name })
        assertEquals(listOf("a", "b"), seen)
    }

    // ---- typed getters ----

    @Test
    fun `event arguments have typed getters with the FunctionArgs coercion`() {
        val event = managerOf("~ emit3(\"x\", \"s\", 4, 1.5)\nHi").next().events.single()
        assertEquals(3, event.size)
        assertEquals("s", event.string(0))
        assertEquals("4", event.string(1))
        assertEquals(4, event.int(1))
        assertEquals(4f, event.float(1))
        assertEquals(1.5f, event.float(2))
        assertTrue(event.bool(1))
    }

    @Test
    fun `event getters throw naming the event and the position`() {
        val event = managerOf("~ emit2(\"x\", \"s\", 1.5)\nHi").next().events.single()
        assertContains(assertFailsWith<IllegalArgumentException> { event.int(1) }.message!!, "event 'x': argument 1")
        assertContains(assertFailsWith<IllegalArgumentException> { event.int(0) }.message!!, "expected an int")
        assertContains(assertFailsWith<IllegalArgumentException> { event.string(5) }.message!!, "argument 5 is missing")
        assertFailsWith<IllegalArgumentException> { event.bool(0) }
    }

    @Test
    fun `a bound function and an event read the same value the same way`() {
        val m = managerOf("EXTERNAL f(a, b)\n~ f(3, 2.5)\n~ emit2(\"e\", 3, 2.5)\nHi\n=== function f(a, b) ===\n~ return 0")
        var args: FunctionArgs? = null
        m.bindFunction("f") { args = it; 0 }
        val event = m.next().events.single()
        val a = args!!
        assertEquals(a.size, event.size)
        assertEquals(a.int(0), event.int(0))
        assertEquals(a.float(1), event.float(1))
        assertEquals(a.bool(0), event.bool(0))
        assertEquals(a.string(1), event.string(1))
    }
}
