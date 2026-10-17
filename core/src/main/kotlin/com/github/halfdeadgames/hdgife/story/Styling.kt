package com.github.halfdeadgames.hdgife.story

import com.github.halfdeadgames.hdgife.HdgifeLog
import java.util.regex.Pattern

/** A named style with an optional value: `em`, `color=red`, `shake`. The engine doesn't know what any name means. */
data class Style(val name: String, val value: String? = null)

/** A run of plain [text] with the [styles] active over it, outermost first. Empty list means unstyled. */
data class TextSpan(val text: String, val styles: List<Style> = emptyList())

/**
 * Reads the style markers in [source] and returns the plain text and the spans.
 *
 * Markers are `<name>` or `<name=value>` to open and `</name>` to close. `</>` closes the most recent one.
 * They nest. Write `<<` for a literal `<`. Anything that isn't a well-formed marker (`a < b`, `<3`, a lone `<`)
 * is plain text. Names are letters, digits, `_` and `-`, starting with a letter. A value may be wrapped in
 * quotes, which are removed. Unknown names are passed on as they are.
 *
 * Problems are reported to [log] and never thrown. An unclosed marker is closed at the end of the text.
 * A closing marker with no match is dropped. A closing marker for an outer style also closes the inner
 * ones. Markers don't carry from one line to the next.
 */
internal fun parseStyles(source: String, log: HdgifeLog? = null): Pair<String, List<TextSpan>> {
    if (source.isEmpty()) return "" to emptyList()
    if (source.indexOf('<') < 0) return source to listOf(TextSpan(source))

    val plain = StringBuilder(source.length)
    val spans = ArrayList<TextSpan>()
    val open = ArrayList<Style>()
    val run = StringBuilder()

    fun flush() {
        if (run.isEmpty()) return
        val text = run.toString()
        run.setLength(0)
        val last = spans.lastOrNull()
        if (last != null && last.styles == open) {
            spans[spans.size - 1] = TextSpan(last.text + text, last.styles)
        } else {
            spans += TextSpan(text, open.toList())
        }
    }

    val matcher = MARKER.matcher(source) // local: Matcher is not thread-safe
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (c != '<') {
            run.append(c)
            plain.append(c)
            i++
            continue
        }
        if (i + 1 < source.length && source[i + 1] == '<') {
            run.append('<')
            plain.append('<')
            i += 2
            continue
        }
        matcher.region(i, source.length)
        if (!matcher.lookingAt()) {
            run.append('<')
            plain.append('<')
            i++
            continue
        }
        val closing = matcher.group(1) != null
        val name = if (closing) matcher.group(2) ?: "" else matcher.group(3)
        val value = matcher.group(4)
        i = matcher.end()
        flush()
        if (!closing) {
            open += Style(name, value?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.ifEmpty { null })
            continue
        }
        if (name.isEmpty()) {
            if (open.isEmpty()) log?.log("Style: '</>' with nothing open, ignored in \"$source\"")
            else open.removeAt(open.size - 1)
            continue
        }
        val at = open.indexOfLast { it.name == name }
        if (at < 0) {
            log?.log("Style: '</$name>' was never opened, ignored in \"$source\"")
            continue
        }
        if (at < open.size - 1) {
            log?.log("Style: '</$name>' closed while <${open.last().name}> was open in \"$source\"")
        }
        while (open.size > at) open.removeAt(open.size - 1)
    }
    flush()
    if (open.isNotEmpty()) {
        log?.log("Style: <${open.joinToString("> <") { it.name }}> never closed in \"$source\"")
    }
    return plain.toString() to spans
}

// groups: 1 "/" for a closing marker, 2 closing name (optional), 3 opening name, 4 opening value
private val MARKER: Pattern =
    Pattern.compile("<(?:(/)([A-Za-z][A-Za-z0-9_-]*)?|([A-Za-z][A-Za-z0-9_-]*)(?:=([^<>]*))?)>")
