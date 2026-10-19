package com.github.halfdeadgames.hdgife

import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.story.StoryManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DialogLine.speaker is the speaker when the line's text was produced, not after ink ran ahead. */
class SpeakerTimingTest {
    private fun plainManager(text: String): StoryManager =
        StoryManager.forPlainStory(Story(compileLib(text).toJson()))

    private val beforeChoice = "~ speak(\"guard\")\nHalt.\n~ speak(\"\")\n+ [Go] -> END"
    private val beforeEnd = "~ speak(\"guard\")\nHalt.\n~ speak(\"\")\n-> END"

    @Test
    fun `a speak after the line and before a choice does not change that line`() {
        val m = managerOf(beforeChoice)
        val step = m.next()
        assertEquals(listOf("guard"), step.lines.map { it.speaker })
        assertNull(m.speaker)
    }

    @Test
    fun `a speak after the line and before the end does not change that line`() {
        val m = managerOf(beforeEnd)
        val step = m.next()
        assertEquals(listOf("guard"), step.lines.map { it.speaker })
        assertNull(m.speaker)
    }

    @Test
    fun `a speak between two lines changes only the second`() {
        val m = managerOf("~ speak(\"a\")\nOne\n~ speak(\"b\")\nTwo\n~ speak(\"c\")\n+ [Go] -> END")
        assertEquals(listOf("a" to "One", "b" to "Two"), m.next().lines.map { it.speaker to it.text })
        assertEquals("c", m.speaker)
    }

    @Test
    fun `a speak between glued lines counts for the joined line, the last one wins`() {
        val m = managerOf("~ speak(\"a\")\nOne\n~ speak(\"b\")\n<> Two\nThree\n~ speak(\"c\")\n+ [Go] -> END")
        val lines = m.next().lines
        assertEquals(listOf("One Two", "Three"), lines.map { it.text })
        assertEquals(listOf("b", "b"), lines.map { it.speaker })
        assertEquals("c", m.speaker)
    }

    @Test
    fun `a trailing speak after a line is for the next lines`() {
        val m = managerOf("~ speak(\"a\")\nOne <>\nTwo\n~ speak(\"b\")\n+ [Go]\n  ~ speak(\"c\")\n  Three\n  -> END")
        val first = m.next()
        assertEquals(listOf("a" to "One Two"), first.lines.map { it.speaker to it.text })
        assertEquals("b", m.speaker)
        assertEquals(listOf("c" to "Three"), m.choose(0).lines.map { it.speaker to it.text })
    }

    @Test
    fun `a tag speaker still wins`() {
        val m = managerOf("~ speak(\"a\")\nHalt. # speaker: crowd\n~ speak(\"\")\n+ [Go] -> END")
        assertEquals(listOf("crowd"), m.next().lines.map { it.speaker })
    }

    @Test
    fun `the persistent speaker after a trailing speak is saved and loaded`() {
        val m = managerOf(beforeChoice)
        m.next()
        val save = m.saveState()
        val other = managerOf(beforeChoice)
        other.loadState(save)
        assertNull(other.speaker)
        assertEquals(emptyList(), other.next().lines)
    }

    @Test
    fun `lines after a load report the speaker that was saved`() {
        val text = "~ speak(\"a\")\nOne\n+ [Go] Two -> END"
        val m = managerOf(text)
        m.next()
        val other = managerOf(text)
        other.loadState(m.saveState())
        assertEquals(listOf("a"), other.choose(0).lines.map { it.speaker })
    }

    // ---- plain Story ----

    @Test
    fun `a plain story also keeps a trailing speak off the previous line`() {
        assertEquals(listOf("guard"), plainManager(beforeChoice).next().lines.map { it.speaker })
        assertEquals(listOf("guard"), plainManager(beforeEnd).next().lines.map { it.speaker })
    }

    @Test
    fun `a plain story gives the right speaker between lines and for glue inside a line`() {
        val between = plainManager("~ speak(\"a\")\nOne\n~ speak(\"b\")\nTwo\n+ [Go] -> END").next()
        assertEquals(listOf("a", "b"), between.lines.map { it.speaker })
        val glued = plainManager("~ speak(\"a\")\nOne <>\n~ speak(\"b\")\nTwo\n+ [Go] -> END").next()
        assertEquals(listOf("One Two"), glued.texts())
        assertEquals(listOf("b"), glued.lines.map { it.speaker })
    }

    @Test
    fun `speak does nothing without a manager`() {
        val story = Story(compileLib("~ speak(\"a\")\nHi").toJson())
        story.setAllowExternalFunctionFallbacks(true)
        assertEquals("Hi\n", story.Continue())
    }
}
