package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.DialogLine
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.TextSpan
import com.github.halfdeadgames.hdgife.story.cleanLine
import com.github.halfdeadgames.hdgife.story.parseHexColor
import com.github.halfdeadgames.hdgife.story.parseStyles
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StyleTest {
    private val b = Style("b")
    private val em = Style("em")

    private fun plain(source: String, carried: List<Style> = emptyList(), log: LogSink? = null) =
        parseStyles(source, carried, { it }, log)

    @Test
    fun `text without markers is one unstyled span`() {
        val styled = plain("Hello")
        assertEquals("Hello", styled.text)
        assertEquals(listOf(TextSpan("Hello")), styled.spans)
    }

    @Test
    fun `a style wraps the text between its markers`() {
        val styled = plain("a <b>bold</b> c")
        assertEquals("a bold c", styled.text)
        assertEquals(listOf(TextSpan("a "), TextSpan("bold", listOf(b)), TextSpan(" c")), styled.spans)
    }

    @Test
    fun `styles nest outermost first`() {
        val styled = plain("<b>a<em>b</em>c</b>")
        assertEquals(
            listOf(TextSpan("a", listOf(b)), TextSpan("b", listOf(b, em)), TextSpan("c", listOf(b))),
            styled.spans,
        )
    }

    @Test
    fun `a closing marker for an outer style closes the inner ones`() {
        val sink = LogSink()
        val styled = plain("<b><em>x</b>y", log = sink)
        assertEquals(listOf(TextSpan("x", listOf(b, em)), TextSpan("y")), styled.spans)
        assertTrue(styled.open.isEmpty())
        assertContains(sink.messages.joinToString(), "em")
    }

    @Test
    fun `a bare close marker closes the most recent style`() {
        val styled = plain("<b><em>x</>y</>z")
        assertEquals(listOf(TextSpan("x", listOf(b, em)), TextSpan("y", listOf(b)), TextSpan("z")), styled.spans)
    }

    @Test
    fun `a closing marker with nothing open is dropped and logged`() {
        val sink = LogSink()
        val styled = plain("a</b>b", log = sink)
        assertEquals("ab", styled.text)
        assertEquals(1, sink.messages.size)
    }

    @Test
    fun `double angle bracket is a literal angle bracket`() {
        val styled = plain("a << b <b>c</b>")
        assertEquals("a < b c", styled.text)
        assertEquals(TextSpan("c", listOf(b)), styled.spans.last())
    }

    @Test
    fun `things that are not markers stay plain text`() {
        assertEquals("a < b and 3 <3 and <", plain("a < b and 3 <3 and <").text)
        assertEquals("<b", plain("<b").text)
        assertEquals("<b c>", plain("<b c>").text)
    }

    @Test
    fun `a style takes a value`() {
        val style = plain("<color=red>x</color>").spans.single().styles.single()
        assertEquals("color", style.name)
        assertEquals("red", style.value)
        assertEquals(listOf("red"), style.values)
    }

    @Test
    fun `a style takes several comma separated values`() {
        val style = plain("<shake=2,0.5>x</shake>").spans.single().styles.single()
        assertEquals("2,0.5", style.value)
        assertEquals(listOf("2", "0.5"), style.values)
    }

    @Test
    fun `a style takes named parameters`() {
        val style = plain("<shake amp=2 speed=0.5>x</shake>").spans.single().styles.single()
        assertNull(style.value)
        assertEquals(mapOf("amp" to "2", "speed" to "0.5"), style.params)
        assertEquals("2", style.param("amp"))
        assertNull(style.param("nope"))
    }

    @Test
    fun `parameters can be quoted and hold spaces`() {
        val style = plain("<tip text=\"two words\" id='x y'>t</tip>").spans.single().styles.single()
        assertEquals("two words", style.param("text"))
        assertEquals("x y", style.param("id"))
    }

    @Test
    fun `a value and parameters can be combined`() {
        val style = plain("<wave=3 amp=2>x</wave>").spans.single().styles.single()
        assertEquals("3", style.value)
        assertEquals("2", style.param("amp"))
    }

    @Test
    fun `a point marker takes no text and records its offset`() {
        val styled = plain("Hello<pause=0.5/> world")
        assertEquals("Hello world", styled.text)
        val marker = styled.markers.single()
        assertEquals("pause", marker.name)
        assertEquals("0.5", marker.value)
        assertEquals(5, marker.offset)
    }

    @Test
    fun `point markers at the start and the end have the edge offsets`() {
        val styled = plain("<slam/>Bang<end/>")
        assertEquals(listOf(0, 4), styled.markers.map { it.offset })
        assertEquals(styled.text.length, styled.markers.last().offset)
    }

    @Test
    fun `a point marker can have parameters`() {
        val marker = plain("x<shake amp=2/>").markers.single()
        assertEquals("2", marker.param("amp"))
    }

    @Test
    fun `a point marker does not split the styled span`() {
        val styled = plain("<b>ab<pause/>cd</b>")
        assertEquals(listOf(TextSpan("abcd", listOf(b))), styled.spans)
        assertEquals(2, styled.markers.single().offset)
    }

    @Test
    fun `offsets follow the cleaned text`() {
        val styled = parseStyles("Hi  there <pause/> you", emptyList(), ::cleanLine, null)
        assertEquals("Hi there you", styled.text)
        assertEquals("Hi there ", styled.text.substring(0, styled.markers.single().offset))
    }

    @Test
    fun `hex colors read in all lengths and prefixes`() {
        assertEquals(0xff0000ff.toInt(), parseHexColor("ff0000"))
        assertEquals(0xff0000ff.toInt(), parseHexColor("#ff0000"))
        assertEquals(0xff0000ff.toInt(), parseHexColor("0xFF0000"))
        assertEquals(0xff0000ff.toInt(), parseHexColor("f00"))
        assertEquals(0x11223344, parseHexColor("11223344"))
        assertEquals(0x00ff0080, parseHexColor("#00ff0080"))
    }

    @Test
    fun `names and junk are not hex colors`() {
        assertNull(parseHexColor("red"))
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#"))
        assertNull(parseHexColor("12345"))
        assertNull(parseHexColor("gg0000"))
    }

    @Test
    fun `a style reports its hex color from the value or a parameter`() {
        assertEquals(0xff0000ff.toInt(), Style("color", "ff0000").hexColor())
        assertNull(Style("color", "red").hexColor())
        assertEquals(0x00ff00ff, Style("glow", null, mapOf("color" to "#0f0")).hexColor("color"))
        assertNull(Style("color").hexColor())
    }

    @Test
    fun `a hex color written with an ink escape arrives intact`() {
        val manager = managerOf("<color=\\#ff8800>warm</color> and <color=\\#fff>white</color>")
        val line = manager.next().lines.single()
        assertEquals("warm and white", line.text)
        assertTrue(line.tags.raw.isEmpty())
        assertEquals(0xff8800ff.toInt(), line.spans[0].styles.single().hexColor())
        assertEquals(0xffffffff.toInt(), line.spans[2].styles.single().hexColor())
    }

    @Test
    fun `a hex color without a hash needs no escape`() {
        val line = managerOf("<color=00ff00>go</color>").next().lines.single()
        assertEquals(0x00ff00ff, line.spans.single().styles.single().hexColor())
    }

    private fun assertJoined(line: DialogLine) {
        assertEquals(line.text, line.spans.joinToString("") { it.text })
    }

    @Test
    fun `line text always equals the joined spans`() {
        val sources = listOf(
            "Hello <b>big</b> , world !",
            "<b>  padded  </b>  text  ",
            "a <em>b</em>   <b>c</b> , d",
            "  <b> </b>x",
            "plain",
            "<b>one</b><em>two</em>",
        )
        for (source in sources) {
            val styled = parseStyles(source)
            assertEquals(styled.text, styled.spans.joinToString("") { it.text }, source)
        }
    }

    @Test
    fun `line text equals the joined spans for a real story`() {
        val manager = managerOf("Wait <b>for</b> it , friend !\n<em>Yes</em>   indeed .")
        for (line in manager.next().lines) assertJoined(line)
    }

    @Test
    fun `a cleaner that rewrites characters still keeps spans and text consistent`() {
        val styled = parseStyles("<b>hello</b> world", emptyList(), { it.uppercase().replace('O', '0') }, null)
        assertEquals("HELL0 W0RLD", styled.text)
        assertEquals(styled.text, styled.spans.joinToString("") { it.text })
    }

    @Test
    fun `an open style carries onto the next line`() {
        val manager = managerOf("<em>First\nSecond</em> third")
        val lines = manager.next().lines
        assertEquals(listOf(TextSpan("First", listOf(em))), lines[0].spans)
        assertEquals(listOf(TextSpan("Second", listOf(em)), TextSpan(" third")), lines[1].spans)
        lines.forEach(::assertJoined)
    }

    @Test
    fun `an open style is reported at the end of a step and carries over a choice`() {
        val manager = managerOf("<b>Open\n* [Next] more</b> done\n-> END")
        val first = manager.next()
        assertEquals(listOf(b), first.openStyles)
        val second = manager.choose(0)
        assertEquals(listOf(TextSpan("more", listOf(b)), TextSpan(" done")), second.lines.single().spans)
    }

    @Test
    fun `an open style carries across next calls`() {
        val manager = managerOf("<b>Open\n* [Stay] Still bold\n  * * [Go] Done</b> now -> END")
        manager.next()
        val second = manager.choose(0)
        assertEquals(listOf(b), second.lines.single().spans.single().styles)
        assertEquals(listOf(b), second.openStyles)
    }

    @Test
    fun `styles still open are closed when the story ends`() {
        val sink = LogSink()
        val manager = managerOf("<b>Never closed\n-> END")
        manager.log = sink
        val step = manager.next()
        assertTrue(step.isEnded)
        assertTrue(step.openStyles.isEmpty())
        assertContains(sink.messages.joinToString(), "never closed")
    }

    @Test
    fun `styles still open are closed on reset and not stacked on replay`() {
        val sink = LogSink()
        val manager = managerOf("<b>Open\n* [Go] Next -> END")
        manager.log = sink
        manager.next()
        manager.reset()
        assertContains(sink.messages.joinToString(), "reset")
        assertEquals(listOf(b), manager.next().lines.single().spans.single().styles)
    }

    @Test
    fun `loadState closes carried styles`() {
        val manager = managerOf("Plain\n* [Go] <b>Open\n* [More] x -> END")
        val saved = manager.saveState()
        manager.next()
        manager.choose(0)
        manager.loadState(saved)
        assertTrue(manager.next().openStyles.isEmpty())
    }

    @Test
    fun `choice text styles are read on their own`() {
        val manager = managerOf("<b>Open\n* [<em>Pick</em> me] Done -> END")
        val choice = manager.next().choices.single()
        assertEquals(listOf(TextSpan("Pick", listOf(em)), TextSpan(" me")), choice.spans)
        assertEquals("Pick me", choice.text)
    }

    @Test
    fun `a style left open in choice text is dropped and logged`() {
        val sink = LogSink()
        val manager = managerOf("* [<b>Pick] Done -> END")
        manager.log = sink
        val step = manager.next()
        assertEquals("Pick", step.choices.single().text)
        assertTrue(step.openStyles.isEmpty())
        assertContains(sink.messages.joinToString(), "never closed in choice")
    }

    @Test
    fun `choices can hold point markers`() {
        val choice = managerOf("* [Go<pause=1/> now] -> END").next().choices.single()
        assertEquals("Go now", choice.text)
        assertEquals(2, choice.markers.single().offset)
    }

    @Test
    fun `a line with only a marker and no text produces no line`() {
        val lines = managerOf("<pause=1/>\nReal").next().lines
        assertEquals(listOf("Real"), lines.map { it.text })
    }
}
