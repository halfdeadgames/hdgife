package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.StoryEventListener
import com.github.halfdeadgames.hdgife.story.StoryStepException
import com.github.halfdeadgames.hdgife.story.events
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventListenerTest {
    private val three = "~ emit(\"a\")\n~ emit(\"b\")\n~ emit(\"c\")\nHi\n-> END"

    @Test
    fun `listeners run in order after the primary listener`() {
        val manager = managerOf("~ emit(\"a\")\nHi\n-> END")
        val order = ArrayList<String>()
        manager.onEvent = StoryEventListener { order += "primary" }
        manager.addEventListener { order += "first" }
        manager.addEventListener { order += "second" }
        manager.next()
        assertEquals(listOf("primary", "first", "second"), order)
    }

    @Test
    fun `a closed listener stops receiving events and closing twice is harmless`() {
        val manager = managerOf("~ emit(\"a\")\nHi\n* [Go]\n  ~ emit(\"b\")\n  -> END")
        val seen = ArrayList<String>()
        val kept = ArrayList<String>()
        val registration = manager.addEventListener { seen += it.name }
        manager.addEventListener { kept += it.name }
        manager.next()
        registration.close()
        registration.close()
        manager.choose(0)
        assertEquals(listOf("a"), seen)
        assertEquals(listOf("a", "b"), kept)
    }

    @Test
    fun `a throwing listener keeps the undelivered rest for the next call`() {
        val manager = managerOf(three)
        val seen = ArrayList<String>()
        var thrown = false
        manager.addEventListener { event ->
            seen += event.name
            if (event.name == "b" && !thrown) {
                thrown = true
                error("boom")
            }
        }
        assertFailsWith<StoryStepException> { manager.next() }
        assertEquals(listOf("a", "b"), seen)
        assertEquals(listOf("c"), manager.next().events.map { it.name })
        assertEquals(listOf("a", "b", "c"), seen)
    }

    @Test
    fun `two routers coexist and do not replace the primary listener`() {
        val manager = managerOf("~ emit(\"a\")\n~ emit1(\"b\", 1)\nHi\n-> END")
        val primarySeen = ArrayList<String>()
        manager.onEvent = StoryEventListener { primarySeen += it.name }
        val seen = ArrayList<String>()
        manager.events { on("a") { seen += "one a" } }
        manager.events { on("b") { amount: Int -> seen += "two b $amount" } }
        manager.next()
        assertEquals(listOf("a", "b"), primarySeen)
        assertEquals(listOf("one a", "two b 1"), seen)
    }

    @Test
    fun `a closed router stops handling events`() {
        val manager = managerOf("~ emit(\"a\")\nHi\n* [Go]\n  ~ emit(\"a\")\n  -> END")
        val seen = ArrayList<String>()
        val router = manager.events { on("a") { seen += "a" } }
        manager.next()
        router.close()
        manager.choose(0)
        assertEquals(listOf("a"), seen)
    }

    @Test
    fun `an unhandled event is logged through the manager log`() {
        val manager = managerOf("~ emit(\"stray\")\nHi\n-> END")
        val sink = LogSink()
        manager.log = sink
        manager.events { }
        manager.next()
        assertEquals(listOf("Unhandled story event 'stray'"), sink.messages)
    }

    @Test
    fun `a router refuses unsupported argument types at registration`() {
        val manager = managerOf(three)
        assertFailsWith<IllegalArgumentException> { manager.events { on("a") { _: Long -> } } }
        assertFailsWith<IllegalArgumentException> { manager.events { on("a") { _: String, _: List<String> -> } } }
    }
}
