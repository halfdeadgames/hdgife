package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.parseStyles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StyleLimitTest {
    @Test
    fun `parseStyles drops the oldest style beyond the limit and logs it`() {
        val log = LogSink()
        val styled = parseStyles("<a><b><c>x", emptyList(), { it }, log, 2)
        assertEquals(listOf("b", "c"), styled.open.map { it.name })
        assertEquals(listOf(listOf(Style("b"), Style("c"))), styled.spans.map { it.styles })
        assertTrue(log.messages.single().contains("dropped the oldest <a>"))
    }

    @Test
    fun `a stray tag in a looping knot cannot grow the open styles`() {
        val manager = managerOf("-> again\n=== again ===\nPress <Enter> to go on.\n+ [Again] -> again")
        val log = LogSink()
        manager.log = log
        var step = manager.next()
        repeat(60) { step = manager.choose(0) }
        assertEquals(16, step.openStyles.size)
        assertEquals(16, step.lines.single().spans.first().styles.size)
        assertEquals(16, manager.saveState().split("\"name\":\"Enter\"").size - 1)
        assertTrue(log.messages.any { "dropped the oldest <Enter>" in it })
    }

    @Test
    fun `the limit is a setting that must be at least one`() {
        val manager = managerOf("<a>One\n<b>Two\n<c>Three\n+ [Go] -> END")
        assertEquals(16, manager.maxOpenStyles)
        manager.maxOpenStyles = 2
        assertEquals(listOf("b", "c"), manager.next().openStyles.map { it.name })
        assertFailsWith<IllegalArgumentException> { manager.maxOpenStyles = 0 }
        assertEquals(2, manager.maxOpenStyles)
    }

    @Test
    fun `by default styles stay open over a choice`() {
        val manager = managerOf("<em>Hi\n+ [Go] there</em> -> END")
        assertEquals(listOf("em"), manager.next().openStyles.map { it.name })
        val step = manager.choose(0)
        assertEquals(listOf(Style("em")), step.lines.single().spans.first().styles)
    }

    @Test
    fun `closeStylesAtChoice closes them at the choice and logs it`() {
        val manager = managerOf("<em>Hi\n+ [Go] there -> END")
        val log = LogSink()
        manager.log = log
        manager.closeStylesAtChoice = true
        val step = manager.next()
        assertEquals(emptyList(), step.openStyles)
        assertTrue(log.messages.any { "<em> never closed, closed because a choice was reached" in it })
        assertEquals(emptyList(), manager.choose(0).lines.single().spans.first().styles)
        assertTrue(manager.saveState().contains("\"styles\":[]"))
    }

    @Test
    fun `a style opened in a step and left open is logged once`() {
        val manager = managerOf("<em>Hi\n+ [Go] there -> loop\n=== loop ===\nAgain\n+ [Go] -> END")
        val log = LogSink()
        manager.log = log
        manager.next()
        assertEquals(1, log.messages.count { "<em> opened and not closed by the end of the step" in it })
        log.messages.clear()
        manager.choose(0)
        assertTrue(log.messages.none { "opened and not closed" in it })
    }

    @Test
    fun `a balanced step logs nothing about open styles`() {
        val manager = managerOf("<em>Hi</em>\n+ [Go] -> END")
        val log = LogSink()
        manager.log = log
        manager.next()
        assertTrue(log.messages.isEmpty())
    }
}
