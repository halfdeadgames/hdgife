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

class LifecycleTest {
    private val usesFunction = "EXTERNAL twice(x)\nVAR n = 0\n~ n = twice(4)\nResult {n} {stat(\"hp\")}\n-> END\n=== function twice(x) ===\n~ return 0"

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
        val manager = StoryManager(story)
        manager.bindFunction("twice") { 99 }
        manager.close()
        assertNull(story.getExternalFunction("twice"))
        assertNull(story.getExternalFunction("stat"))
        assertEquals(listOf("Result 0 0.0"), StoryManager(story).next().texts())
    }

    @Test
    fun `calls on a closed manager throw IllegalStateException, close is repeatable`() {
        val manager = managerOf("Hi\n* [Go] -> END")
        manager.close()
        manager.close()
        assertFailsWith<IllegalStateException> { manager.next() }
        assertFailsWith<IllegalStateException> { manager.choose(0) }
        assertFailsWith<IllegalStateException> { manager.saveState() }
        assertFailsWith<IllegalStateException> { manager.loadState("{}") }
        assertFailsWith<IllegalStateException> { manager.reset() }
        assertFailsWith<IllegalStateException> { manager.speaker }
        assertFailsWith<IllegalStateException> { manager.speaker = "x" }
        assertFailsWith<IllegalStateException> { manager.getVariable("hdgife_speaker") }
        assertFailsWith<IllegalStateException> { manager.bindFunction("f") { 0 } }
        assertFailsWith<IllegalStateException> { manager.beginScene("x") }
        assertFailsWith<IllegalStateException> { manager.replaceStory(compileLib("Hi")) }
    }

    @Test
    fun `closing stops observations and drops pending events and open styles`() {
        val manager = managerOf("VAR n = 0\n<em>Hi\n~ n = 3\n* [Go] -> END")
        val observation = manager.observe("n") { _, _ -> }
        manager.close()
        assertFalse(observation.isActive)
        observation.close()
        manager.unobserve("n")
    }

    @Test
    fun `use as AutoCloseable`() {
        val story = compileLib("Hi")
        StoryManager(story).use { assertEquals(listOf("Hi"), it.next().texts()) }
        StoryManager(story).use { assertEquals(false, it.isClosed) }
    }

    @Test
    fun `a failed loadState still closes styles, drops pending events and leaves a consistent manager`() {
        val manager = managerOf("<em>Hi\n~ emit(\"a\")\n~ emit(\"b\")\n* [Go] -> END")
        val log = LogSink()
        manager.log = log
        val seen = ArrayList<String>()
        manager.onEvent = StoryEventListener { seen += it.name; if (it.name == "a") error("boom") }
        assertFailsWith<StoryStepException> { manager.next() }
        assertEquals(listOf("a"), seen)

        val ex = assertFailsWith<SaveStateException> { manager.loadState("{\"v\":1,\"ink\":\"this is not ink\",\"styles\":[]}") }
        assertContains(ex.message!!, "Ink could not load the state")
        assertTrue(log.messages.any { "<em> never closed, closed because loading the state failed" in it })

        val step = manager.next()
        assertTrue(step.events.isEmpty(), "the pending tail was dropped")
        assertEquals(emptyList(), step.openStyles)
        assertEquals(listOf("a"), seen)
        assertEquals(1, step.choices.size)
    }

    @Test
    fun `a refused envelope changes nothing`() {
        val manager = managerOf("<em>Hi\n* [Go] there -> END")
        manager.next()
        assertFailsWith<SaveStateException> { manager.loadState("{\"v\":99,\"ink\":\"{}\"}") }
        assertEquals(listOf(Style("em")), manager.choose(0).lines.single().spans.first().styles)
    }

    private val text = "Hi\n* [Go] Gone -> END"

    private fun withId(id: String?, source: String = text) = managerOf(source).also { it.storyId = id }

    @Test
    fun `a save carries the story id and a different one is refused`() {
        val chapterOneManager = withId("chapter1")
        chapterOneManager.next()
        val save = chapterOneManager.saveState()
        assertContains(save, "\"storyId\":\"chapter1\"")
        assertContains(save, "\"v\":2")

        val chapterTwoManager = withId("chapter2")
        chapterTwoManager.next()
        val ex = assertFailsWith<SaveStateException> { chapterTwoManager.loadState(save) }
        assertContains(ex.message!!, "chapter1")
        assertContains(ex.message!!, "chapter2")
        assertEquals(listOf("Gone"), chapterTwoManager.choose(0).texts(), "the manager was not touched")

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

    private fun fingerprintOf(manager: StoryManager): String =
        Regex("\"fingerprint\":\"([0-9a-f]{16})\"").find(manager.saveState())!!.groupValues[1]

    @Test
    fun `the fingerprint is off by default and written when asked`() {
        val manager = managerOf(text)
        assertFalse("fingerprint" in manager.saveState())
        manager.saveFingerprint = true
        assertContains(manager.saveState(), "\"v\":2")
        assertEquals(fingerprintOf(manager), fingerprintOf(manager))
    }

    @Test
    fun `the same story gives the same fingerprint, also when rebuilt from its json`() {
        val firstManager = managerOf(text).also { it.saveFingerprint = true }
        val secondManager = managerOf(text).also { it.saveFingerprint = true }
        val rebuilt = StoryManager(HdgifeStory.from(Story(compileLib(text).toJson()))).also { it.saveFingerprint = true }
        assertEquals(fingerprintOf(firstManager), fingerprintOf(secondManager))
        assertEquals(fingerprintOf(firstManager), fingerprintOf(rebuilt))
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
        val edited = managerOf("Hello\n* [Go] Gone -> END").also { it.saveFingerprint = true }
        edited.loadState(save)
    }

    @Test
    fun `the fingerprint is only checked when the flag is on, and old saves pass`() {
        val old = managerOf(text).also { it.saveFingerprint = true }
        old.next()
        val save = old.saveState()
        val changed = managerOf("$text\n=== extra ===\nMore")
        changed.loadState(save)
        val strict = managerOf("$text\n=== extra ===\nMore").also { it.saveFingerprint = true }
        val v1 = managerOf(text).also { it.next() }.saveState()
        strict.loadState(v1)
    }

    @Test
    fun `a replaced story gets a new fingerprint`() {
        val manager = managerOf(text).also { it.saveFingerprint = true }
        val first = fingerprintOf(manager)
        manager.replaceStory(compileLib("$text\n=== extra ===\nMore"))
        assertNotEquals(first, fingerprintOf(manager))
    }
}
