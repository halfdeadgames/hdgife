package com.github.halfdeadgames.hdgife.story

import com.github.halfdeadgames.hdgife.HdgifeLog

/**
 * A named style with optional parameters: `em`, `color=red`, `shake=2,0.5`, `shake amp=2 speed=0.5`.
 * The engine doesn't know what any name means, the game decides.
 *
 * [value] is the text after the first `=`, as written (quotes removed). [params] are the `key=value` pairs
 * that follow. Both are raw strings, the game reads them as it likes.
 */
data class Style(
    val name: String,
    val value: String? = null,
    val params: Map<String, String> = emptyMap(),
) {
    /** [value] split at commas and trimmed: `shake=2,0.5` gives `["2", "0.5"]`. Empty if there is no value. */
    val values: List<String> get() = value?.split(',')?.map { it.trim() } ?: emptyList()

    /** The named parameter [key], or null. */
    fun param(key: String): String? = params[key]

    /**
     * The color of this style as RGBA8888 if it is written as hex, else null (a name like `red`, which the
     * game resolves itself). Reads [value], or the parameter [key] when one is given. See [parseHexColor].
     */
    fun hexColor(key: String? = null): Int? = (if (key == null) value else params[key])?.let(::parseHexColor)
}

/**
 * A self-closing marker such as `<pause=0.5/>` or `<slam/>`. It takes no text. [offset] is the character
 * index in the line's plain text where it sits (the marker comes before the character at [offset], and
 * `offset == text.length` means after the last one). The engine doesn't interpret it.
 */
data class TextMarker(
    val name: String,
    val value: String? = null,
    val params: Map<String, String> = emptyMap(),
    val offset: Int,
) {
    /** [value] split at commas and trimmed. */
    val values: List<String> get() = value?.split(',')?.map { it.trim() } ?: emptyList()

    /** The named parameter [key], or null. */
    fun param(key: String): String? = params[key]

    /** Same as [Style.hexColor]. */
    fun hexColor(key: String? = null): Int? = (if (key == null) value else params[key])?.let(::parseHexColor)
}

/** A run of plain [text] with the [styles] active over it, outermost first. Empty list means unstyled. */
data class TextSpan(val text: String, val styles: List<Style> = emptyList())

/**
 * Reads [text] as a hex color and returns RGBA8888 (red in the top byte, alpha in the low byte), or null if
 * it isn't hex. Accepted: `rgb` (alpha ff), `rrggbb` (alpha ff) and `rrggbbaa`, each with an optional `#`
 * or `0x` in front, in any case. Anything else, including color names, gives null.
 *
 * A name made only of the letters a-f that is three letters long (`bad`, `ace`) reads as hex. Put `0x` in
 * front of real hex if you also use such names. A bare `#` in ink text starts a tag, so the form without it
 * is the normal one (`<color=ff0000>`). `#` in ink also lets a literal `#` through, if the compiler accepts it.
 */
fun parseHexColor(text: String): Int? {
    var digits = text.trim()
    digits = when {
        digits.startsWith("#") -> digits.substring(1)
        digits.startsWith("0x") || digits.startsWith("0X") -> digits.substring(2)
        else -> digits
    }
    if (digits.isEmpty() || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return when (digits.length) {
        3 -> {
            val r = digits[0].digitToInt(16)
            val g = digits[1].digitToInt(16)
            val b = digits[2].digitToInt(16)
            ((r * 17) shl 24) or ((g * 17) shl 16) or ((b * 17) shl 8) or 0xff
        }
        6 -> (digits.toLong(16) shl 8).toInt() or 0xff
        8 -> digits.toLong(16).toInt()
        else -> null
    }
}

/** Result of reading the markers of one text: clean [text], its [spans] and [markers], and the styles still [open]. */
internal class StyledText(
    val text: String,
    val spans: List<TextSpan>,
    val markers: List<TextMarker>,
    val open: List<Style>,
)

private sealed interface Token {
    val end: Int
}

private class OpenToken(val style: Style, override val end: Int) : Token
private class SelfToken(val style: Style, override val end: Int) : Token
private class CloseToken(val name: String, override val end: Int) : Token

/**
 * Reads the style markers in [source]. Markers are
 * - `<name>`, `<name=value>`, `<name key=value key2="two words">` to open (value and params are optional),
 * - `</name>` to close and `</>` to close the most recent,
 * - `<name/>`, `<name=value/>`, `<name key=value/>` for a point marker with no text.
 *
 * Open ones nest. Write `<<` for a literal `<`. Anything that isn't a well-formed marker (`a < b`, `<3`, a lone
 * `<`) is plain text. Names are letters, digits, `_` and `-`, starting with a letter. Values end at a space or
 * `>`, unless wrapped in quotes (`"` or `'`, removed). Unknown names are passed on as they are.
 *
 * [carried] are the styles already open from earlier lines, outermost first. Spans start with them, and a closing
 * marker may close one of them. The styles still open at the end are returned in [StyledText.open] and are not
 * closed here: a style stays open until its closing marker, across lines. [cleaner] runs on the plain text
 * after the markers are removed. Spans and markers are then moved onto the cleaned text, so the spans' texts
 * always join to exactly [StyledText.text]. This works for any cleaner that only removes characters (the
 * default does). A cleaner that rewrites characters still gives consistent text, but the boundaries are placed
 * proportionally.
 *
 * Problems are reported to [log] and never thrown. A closing marker with no match is dropped. A closing marker for
 * an outer style also closes the inner ones.
 */
internal fun parseStyles(
    source: String,
    carried: List<Style> = emptyList(),
    cleaner: (String) -> String = ::cleanLine,
    log: HdgifeLog? = null,
): StyledText {
    if (source.indexOf('<') < 0) {
        val cleaned = cleaner(source)
        val spans = if (cleaned.isEmpty()) emptyList() else listOf(TextSpan(cleaned, carried.toList()))
        return StyledText(cleaned, spans, emptyList(), carried.toList())
    }

    val plain = StringBuilder(source.length)
    val open = ArrayList<Style>(carried)
    // runs in plain coordinates: [start, end) with styles
    val runStarts = ArrayList<Int>()
    val runEnds = ArrayList<Int>()
    val runStyles = ArrayList<List<Style>>()
    val rawMarkers = ArrayList<TextMarker>()
    var runStart = 0

    fun flush() {
        if (plain.length == runStart) return
        runStarts += runStart
        runEnds += plain.length
        runStyles += open.toList()
        runStart = plain.length
    }

    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (c != '<') {
            plain.append(c)
            i++
            continue
        }
        if (i + 1 < source.length && source[i + 1] == '<') {
            plain.append('<')
            i += 2
            continue
        }
        val token = readToken(source, i)
        if (token == null) {
            plain.append('<')
            i++
            continue
        }
        i = token.end
        when (token) {
            is OpenToken -> {
                flush()
                open += token.style
            }
            is SelfToken -> rawMarkers += TextMarker(
                token.style.name, token.style.value, token.style.params, plain.length,
            )
            is CloseToken -> {
                flush()
                closeStyle(open, token.name, source, log)
            }
        }
    }
    flush()

    val raw = plain.toString()
    val cleaned = cleaner(raw)
    val map = boundaryMap(raw, cleaned)

    val spans = ArrayList<TextSpan>()
    for (k in runStarts.indices) {
        val from = map[runStarts[k]]
        val to = map[runEnds[k]]
        if (to <= from) continue
        val text = cleaned.substring(from, to)
        val last = spans.lastOrNull()
        if (last != null && last.styles == runStyles[k]) {
            spans[spans.size - 1] = TextSpan(last.text + text, last.styles)
        } else {
            spans += TextSpan(text, runStyles[k])
        }
    }
    val markers = rawMarkers.map { it.copy(offset = map[it.offset]) }
    return StyledText(cleaned, spans, markers, open.toList())
}

private fun closeStyle(open: ArrayList<Style>, name: String, source: String, log: HdgifeLog?) {
    if (name.isEmpty()) {
        if (open.isEmpty()) log?.log("Style: '</>' with nothing open, ignored in \"$source\"")
        else open.removeAt(open.size - 1)
        return
    }
    val at = open.indexOfLast { it.name == name }
    if (at < 0) {
        log?.log("Style: '</$name>' was never opened, ignored in \"$source\"")
        return
    }
    if (at < open.size - 1) {
        log?.log("Style: '</$name>' closed while <${open.last().name}> was open in \"$source\"")
    }
    while (open.size > at) open.removeAt(open.size - 1)
}

/**
 * For each position 0..raw.length in [raw], the matching position in [cleaned]. Greedy subsequence match, so
 * removed characters collapse onto the next kept one. Falls back to proportional when [cleaned] is not a
 * subsequence of [raw].
 */
private fun boundaryMap(raw: String, cleaned: String): IntArray {
    val map = IntArray(raw.length + 1)
    if (raw == cleaned) {
        for (k in map.indices) map[k] = k
        return map
    }
    var j = 0
    for (k in raw.indices) {
        map[k] = j
        if (j < cleaned.length && raw[k] == cleaned[j]) j++
    }
    map[raw.length] = j
    if (j == cleaned.length) return map
    // cleaner rewrote characters: spread proportionally, keeping the ends fixed
    for (k in map.indices) {
        map[k] = if (raw.isEmpty()) 0 else (k.toLong() * cleaned.length / raw.length).toInt()
    }
    return map
}

private fun isNameStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
private fun isNameChar(c: Char) = isNameStart(c) || c in '0'..'9' || c == '_' || c == '-'

/** Reads a marker starting at the `<` at [start], or null when it isn't a well-formed one. */
private fun readToken(s: String, start: Int): Token? {
    var i = start + 1
    if (i >= s.length) return null
    if (s[i] == '/') {
        i++
        val nameStart = i
        if (i < s.length && isNameStart(s[i])) {
            while (i < s.length && isNameChar(s[i])) i++
        }
        val name = s.substring(nameStart, i)
        return if (i < s.length && s[i] == '>') CloseToken(name, i + 1) else null
    }
    if (!isNameStart(s[i])) return null
    val nameStart = i
    while (i < s.length && isNameChar(s[i])) i++
    val name = s.substring(nameStart, i)

    var value: String? = null
    val params = LinkedHashMap<String, String>()
    if (i < s.length && s[i] == '=') {
        val (v, next) = readValue(s, i + 1) ?: return null
        value = v.ifEmpty { null }
        i = next
    }
    while (true) {
        if (i >= s.length) return null
        when {
            s[i] == '>' -> return OpenToken(Style(name, value, params), i + 1)
            s[i] == '/' && i + 1 < s.length && s[i + 1] == '>' -> return SelfToken(Style(name, value, params), i + 2)
            s[i] == ' ' || s[i] == '\t' -> {
                while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
                if (i >= s.length || s[i] == '>' || s[i] == '/') continue
                if (!isNameStart(s[i])) return null
                val keyStart = i
                while (i < s.length && isNameChar(s[i])) i++
                if (i >= s.length || s[i] != '=') return null
                val key = s.substring(keyStart, i)
                val (v, next) = readValue(s, i + 1) ?: return null
                params[key] = v
                i = next
            }
            else -> return null
        }
    }
}

/** Reads a value at [from]: quoted, or up to a space, `>`, `<` or a `/` right before `>`. Returns text and next index. */
private fun readValue(s: String, from: Int): Pair<String, Int>? {
    if (from >= s.length) return null
    val q = s[from]
    if (q == '"' || q == '\'') {
        val close = s.indexOf(q, from + 1)
        return if (close < 0) null else s.substring(from + 1, close) to close + 1
    }
    var i = from
    while (i < s.length) {
        val c = s[i]
        if (c == ' ' || c == '\t' || c == '>' || c == '<') break
        if (c == '/' && i + 1 < s.length && s[i + 1] == '>') break
        i++
    }
    return s.substring(from, i) to i
}
