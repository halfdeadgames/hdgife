package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.StoryEventListener
import com.bladecoder.ink.runtime.HdgifeStory
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.story.SaveStateException
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.StoryStepException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** close(), a failed loadState, and story identity in saves. */
class LifecycleTest {
    private val usesFunction = "EXTERNAL twice(x)\nVAR n = 0\n~ n = twice(4)\nResult {n} {stat(\"hp\")}\n-> END\n=== function twice(x) ===\n~ return 0"

    // ---- close ----

    @Test
    fun `a closed manager gives its story to a new one`() {
        val story = compileLib(usesFunction)
        val first = MapStats().also { it["player", "hp"] = 1 }
        val m1 = StoryManager(story, first)
        m1.speaker = "player"
        m1.bindFunction("twice") { it.int(0) * 2 }
        val seen = ArrayList<Any?>()
        m1.observe("n") { _, new -> seen += new }
        m1.close()
        assertTrue(m1.isClosed)

        val second = MapStats().also { it["player", "hp"] = 7 }
        val m2 = StoryManager(story, second)
        m2.speaker = "player"
        m2.bindFunction("twice") { it.int(0) * 3 }
        assertEquals(listOf("Result 12 7"), m2.next().texts())
        assertTrue(seen.isEmpty(), "the old observer must not fire")
    }

    @Test
    fun `a closed manager unbinds its functions so the ink fallback runs`() {
        val story = compileLib(usesFunction)
        val m = StoryManager(story)
        m.bindFunction("twice") { 99 }
        m.close()
        assertNull(story.getExternalFunction("twice"))
        assertNull(story.getExternalFunction("stat"))
        assertEquals(listOf("Result 0 0.0"), StoryManager(story).next().texts())
    }

    @Test
    fun `calls on a closed manager throw IllegalStateException, close is repeatable`() {
        val m = managerOf("Hi\n* [Go] -> END")
        m.close()
        m.close()
        assertFailsWith<IllegalStateException> { m.next() }
        assertFailsWith<IllegalStateException> { m.choose(0) }
        assertFailsWith<IllegalStateException> { m.saveState() }
        assertFailsWith<IllegalStateException> { m.loadState("{}") }
        assertFailsWith<IllegalStateException> { m.reset() }
        assertFailsWith<IllegalStateException> { m.speaker }
        assertFailsWith<IllegalStateException> { m.speaker = "x" }
        assertFailsWith<IllegalStateException> { m.getVariable("hdgife_speaker") }
        assertFailsWith<IllegalStateException> { m.bindFunction("f") { 0 } }
        assertFailsWith<IllegalStateException> { m.beginScene("x") }
        assertFailsWith<IllegalStateException> { m.replaceStory(compileLib("Hi")) }
    }

    @Test
    fun `closing stops observations and drops pending events and open styles`() {
        val m = managerOf("VAR n = 0\n<em>Hi\n~ n = 3\n* [Go] -> END")
        val observation = m.observe("n") { _, _ -> }
        m.close()
        assertFalse(observation.isActive)
        observation.close() // a no-op, not an error
        m.unobserve("n")
    }

    @Test
    fun `use as AutoCloseable`() {
        val story = compileLib("Hi")
        StoryManager(story).use { assertEquals(listOf("Hi"), it.next().texts()) }
        StoryManager(story).use { assertEquals(false, it.isClosed) }
    }

    // ---- failed loadState ----

    @Test
    fun `a failed loadState still closes styles, drops pending events and leaves a consistent manager`() {
        val m = managerOf("<em>Hi\n~ emit(\"a\")\n~ emit(\"b\")\n* [Go] -> END")
        val log = LogSink()
        m.log = log
        val seen = ArrayList<String>()
        m.onEvent = StoryEventListener { seen += it.name; if (it.name == "a") error("boom") }
        assertFailsWith<StoryStepException> { m.next() }
        assertEquals(listOf("a"), seen)

        val ex = assertFailsWith<SaveStateException> { m.loadState("{\"v\":1,\"ink\":\"this is not ink\",\"styles\":[]}") }
        assertContains(ex.message!!, "Ink could not load the state")
        assertTrue(log.messages.any { "<em> never closed, closed because loading the state failed" in it })

        val step = m.next()
        assertTrue(step.events.isEmpty(), "the pending tail was dropped")
        assertEquals(emptyList(), step.openStyles)
        assertEquals(listOf("a"), seen)
        assertEquals(1, step.choices.size)
    }

    @Test
    fun `a refused envelope changes nothing`() {
        val m = managerOf("<em>Hi\n* [Go] there -> END")
        m.next()
        assertFailsWith<SaveStateException> { m.loadState("{\"v\":99,\"ink\":\"{}\"}") }
        assertEquals(listOf(Style("em")), m.choose(0).lines.single().spans.first().styles)
    }

    // ---- story id ----

    private val text = "Hi\n* [Go] Gone -> END"

    private fun withId(id: String?, source: String = text) = managerOf(source).also { it.storyId = id }

    @Test
    fun `a save carries the story id and a different one is refused`() {
        val a = withId("chapter1")
        a.next()
        val save = a.saveState()
        assertContains(save, "\"storyId\":\"chapter1\"")
        assertContains(save, "\"v\":2")

        val b = withId("chapter2")
        b.next()
        val ex = assertFailsWith<SaveStateException> { b.loadState(save) }
        assertContains(ex.message!!, "chapter1")
        assertContains(ex.message!!, "chapter2")
        assertEquals(listOf("Gone"), b.choose(0).texts(), "the manager was not touched")

        val same = withId("chapter1")
        same.loadState(save)
        assertEquals(listOf("Gone"), same.choose(0).texts())
    }

    @Test
    fun `saves without an id stay version 1 and load everywhere`() {
        val plain = withId(null)
        plain.next()
        val save = plain.saveState()
        assertContains(save, "\"v\":1")
        assertFalse("storyId" in save)
        val named = withId("x")
        named.loadState(save)
        assertEquals(listOf("Gone"), named.choose(0).texts())
    }

    @Test
    fun `a save with an id loads into a manager without one`() {
        val named = withId("x")
        named.next()
        val other = withId(null)
        other.loadState(named.saveState())
        assertEquals(listOf("Gone"), other.choose(0).texts())
    }

    // ---- fingerprint ----

    private fun fingerprintOf(m: StoryManager): String =
        Regex("\"fingerprint\":\"([0-9a-f]{16})\"").find(m.saveState())!!.groupValues[1]

    @Test
    fun `the fingerprint is off by default and written when asked`() {
        val m = managerOf(text)
        assertFalse("fingerprint" in m.saveState())
        m.saveFingerprint = true
        assertContains(m.saveState(), "\"v\":2")
        assertEquals(fingerprintOf(m), fingerprintOf(m))
    }

    @Test
    fun `the same story gives the same fingerprint, also when rebuilt from its json`() {
        val a = managerOf(text).also { it.saveFingerprint = true }
        val b = managerOf(text).also { it.saveFingerprint = true }
        val rebuilt = StoryManager(HdgifeStory.from(Story(compileLib(text).toJson()))).also { it.saveFingerprint = true }
        assertEquals(fingerprintOf(a), fingerprintOf(b))
        assertEquals(fingerprintOf(a), fingerprintOf(rebuilt))
    }

    @Test
    fun `a changed structure gives another fingerprint and its saves are refused`() {
        val old = managerOf(text).also { it.saveFingerprint = true }
        old.next()
        val save = old.saveState()
        val changed = managerOf("$text\n=== extra ===\nMore").also { it.saveFingerprint = true }
        assertNotEquals(fingerprintOf(old), fingerprintOf(changed))
        val ex = assertFailsWith<SaveStateException> { changed.loadState(save) }
        assertContains(ex.message!!, "changed")
        // an edited line is not a structure change
        val edited = managerOf("Hello\n* [Go] Gone -> END").also { it.saveFingerprint = true }
        edited.loadState(save)
    }

    @Test
    fun `the fingerprint is only checked when the flag is on, and old saves pass`() {
        val old = managerOf(text).also { it.saveFingerprint = true }
        old.next()
        val save = old.saveState()
        val changed = managerOf("$text\n=== extra ===\nMore")
        changed.loadState(save) // flag off: not checked
        val strict = managerOf("$text\n=== extra ===\nMore").also { it.saveFingerprint = true }
        val v1 = managerOf(text).also { it.next() }.saveState()
        strict.loadState(v1) // a save with no fingerprint cannot be checked
    }

    @Test
    fun `a replaced story gets a new fingerprint`() {
        val m = managerOf(text).also { it.saveFingerprint = true }
        val first = fingerprintOf(m)
        m.replaceStory(compileLib("$text\n=== extra ===\nMore"))
        assertNotEquals(first, fingerprintOf(m))
    }
}
