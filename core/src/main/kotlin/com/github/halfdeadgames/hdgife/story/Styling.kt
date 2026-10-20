package com.github.halfdeadgames.hdgife.story

import com.github.halfdeadgames.hdgife.HdgifeLog

public class Style internal constructor(
    public val name: String,
    public val value: String? = null,
    public val params: Map<String, String> = emptyMap(),
) {
    public val values: List<String> get() = splitValues(value)

    public fun param(key: String): String? = params[key]

    public fun hexColor(key: String? = null): Int? = (if (key == null) value else params[key])?.let(::parseHexColor)

    override fun equals(other: Any?): Boolean =
        this === other || other is Style && name == other.name && value == other.value && params == other.params

    override fun hashCode(): Int = listOf(name, value, params).hashCode()

    override fun toString(): String = "Style(name=$name, value=$value, params=$params)"
}

public class TextMarker internal constructor(
    public val name: String,
    public val offset: Int,
    public val value: String? = null,
    public val params: Map<String, String> = emptyMap(),
) {
    public val values: List<String> get() = splitValues(value)

    public fun param(key: String): String? = params[key]

    public fun hexColor(key: String? = null): Int? = (if (key == null) value else params[key])?.let(::parseHexColor)

    internal fun atOffset(offset: Int) = TextMarker(name, offset, value, params)

    override fun equals(other: Any?): Boolean = this === other || other is TextMarker &&
        name == other.name && offset == other.offset && value == other.value && params == other.params

    override fun hashCode(): Int = listOf(name, offset, value, params).hashCode()

    override fun toString(): String = "TextMarker(name=$name, offset=$offset, value=$value, params=$params)"
}

private fun splitValues(value: String?): List<String> = value?.split(',')?.map(String::trim).orEmpty()

public class TextSpan internal constructor(public val text: String, public val styles: List<Style> = emptyList()) {
    override fun equals(other: Any?): Boolean = this === other || other is TextSpan && text == other.text && styles == other.styles

    override fun hashCode(): Int = listOf(text, styles).hashCode()

    override fun toString(): String = "TextSpan(text=$text, styles=$styles)"
}

public fun parseHexColor(text: String): Int? {
    val trimmed = text.trim()
    val digits = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed.substring(2) else trimmed.removePrefix("#")
    if (digits.isEmpty() || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return when (digits.length) {
        3 -> {
            val red = digits[0].digitToInt(16)
            val green = digits[1].digitToInt(16)
            val blue = digits[2].digitToInt(16)
            ((red * 17) shl 24) or ((green * 17) shl 16) or ((blue * 17) shl 8) or 0xff
        }
        6 -> (digits.toLong(16) shl 8).toInt() or 0xff
        8 -> digits.toLong(16).toInt()
        else -> null
    }
}

internal class StyledText(
    val text: String,
    val spans: List<TextSpan>,
    val markers: List<TextMarker>,
    val open: List<Style>,
)

private sealed interface Token {
    val end: Int
}

private class Run(val start: Int, val end: Int, val styles: List<Style>)

private class OpenToken(val style: Style, override val end: Int) : Token
private class SelfToken(val style: Style, override val end: Int) : Token
private class CloseToken(val name: String, override val end: Int) : Token

internal fun parseStyles(
    source: String,
    carried: List<Style> = emptyList(),
    cleaner: TextCleaner = TextCleaner(::cleanLine),
    log: HdgifeLog? = null,
    maxOpen: Int = Int.MAX_VALUE,
): StyledText {
    if (source.indexOf('<') < 0) {
        val cleaned = cleaner.clean(source)
        val spans = if (cleaned.isEmpty()) emptyList() else listOf(TextSpan(cleaned, carried.toList()))
        return StyledText(cleaned, spans, emptyList(), carried.toList())
    }

    val plain = StringBuilder(source.length)
    val open = ArrayList<Style>(carried)
    val runs = ArrayList<Run>()
    val rawMarkers = ArrayList<TextMarker>()
    var runStart = 0

    fun flush() {
        if (plain.length == runStart) return
        runs += Run(runStart, plain.length, open.toList())
        runStart = plain.length
    }

    var i = 0
    while (i < source.length) {
        val char = source[i]
        if (char != '<') {
            plain.append(char)
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
                if (open.size > maxOpen) {
                    log?.log("Style: more than $maxOpen styles open, dropped the oldest <${open[0].name}> in \"$source\"")
                    open.removeAt(0)
                }
            }
            is SelfToken -> rawMarkers += TextMarker(
                token.style.name, plain.length, token.style.value, token.style.params,
            )
            is CloseToken -> {
                flush()
                closeStyle(open, token.name, source, log)
            }
        }
    }
    flush()

    val raw = plain.toString()
    val cleaned = cleaner.clean(raw)
    val offsets = cleanedOffsets(raw, cleaned)

    val spans = ArrayList<TextSpan>()
    for (run in runs) {
        val from = offsets[run.start]
        val to = offsets[run.end]
        if (to <= from) continue
        val text = cleaned.substring(from, to)
        val last = spans.lastOrNull()
        if (last != null && last.styles == run.styles) {
            spans[spans.lastIndex] = TextSpan(last.text + text, last.styles)
        } else {
            spans += TextSpan(text, run.styles)
        }
    }
    val markers = rawMarkers.map { it.atOffset(offsets[it.offset]) }
    return StyledText(cleaned, spans, markers, open.toList())
}

private fun closeStyle(open: ArrayList<Style>, name: String, source: String, log: HdgifeLog?) {
    if (name.isEmpty()) {
        if (open.isEmpty()) log?.log("Style: '</>' with nothing open, ignored in \"$source\"")
        else open.removeAt(open.lastIndex)
        return
    }
    val openIndex = open.indexOfLast { it.name == name }
    if (openIndex < 0) {
        log?.log("Style: '</$name>' was never opened, ignored in \"$source\"")
        return
    }
    if (openIndex < open.size - 1) {
        log?.log("Style: '</$name>' closed while <${open.last().name}> was open in \"$source\"")
    }
    while (open.size > openIndex) open.removeAt(open.lastIndex)
}

private fun cleanedOffsets(raw: String, cleaned: String): IntArray {
    val offsets = IntArray(raw.length + 1)
    if (raw == cleaned) {
        for (index in offsets.indices) offsets[index] = index
        return offsets
    }
    var cleanedIndex = 0
    for (rawIndex in raw.indices) {
        offsets[rawIndex] = cleanedIndex
        if (cleanedIndex < cleaned.length && raw[rawIndex] == cleaned[cleanedIndex]) cleanedIndex++
    }
    offsets[raw.length] = cleanedIndex
    if (cleanedIndex == cleaned.length) return offsets
    for (index in offsets.indices) {
        offsets[index] = if (raw.isEmpty()) 0 else (index.toLong() * cleaned.length / raw.length).toInt()
    }
    return offsets
}

private fun isNameStart(char: Char) = char in 'a'..'z' || char in 'A'..'Z'
private fun isNameChar(char: Char) = isNameStart(char) || char in '0'..'9' || char == '_' || char == '-'

private fun readToken(text: String, start: Int): Token? {
    var i = start + 1
    if (i >= text.length) return null
    if (text[i] == '/') {
        i++
        val nameStart = i
        if (i < text.length && isNameStart(text[i])) {
            while (i < text.length && isNameChar(text[i])) i++
        }
        val name = text.substring(nameStart, i)
        return if (i < text.length && text[i] == '>') CloseToken(name, i + 1) else null
    }
    if (!isNameStart(text[i])) return null
    val nameStart = i
    while (i < text.length && isNameChar(text[i])) i++
    val name = text.substring(nameStart, i)

    var value: String? = null
    val params = LinkedHashMap<String, String>()
    if (i < text.length && text[i] == '=') {
        val (firstValue, next) = readValue(text, i + 1) ?: return null
        value = firstValue.ifEmpty { null }
        i = next
    }
    while (true) {
        if (i >= text.length) return null
        when {
            text[i] == '>' -> return OpenToken(Style(name, value, params), i + 1)
            text[i] == '/' && i + 1 < text.length && text[i + 1] == '>' -> return SelfToken(Style(name, value, params), i + 2)
            text[i] == ' ' || text[i] == '\t' -> {
                while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
                if (i >= text.length || text[i] == '>' || text[i] == '/') continue
                if (!isNameStart(text[i])) return null
                val keyStart = i
                while (i < text.length && isNameChar(text[i])) i++
                if (i >= text.length || text[i] != '=') return null
                val key = text.substring(keyStart, i)
                val (paramValue, next) = readValue(text, i + 1) ?: return null
                params[key] = paramValue
                i = next
            }
            else -> return null
        }
    }
}

private fun readValue(text: String, from: Int): Pair<String, Int>? {
    if (from >= text.length) return null
    val quote = text[from]
    if (quote == '"' || quote == '\'') {
        val close = text.indexOf(quote, from + 1)
        return if (close < 0) null else text.substring(from + 1, close) to close + 1
    }
    var i = from
    while (i < text.length) {
        val char = text[i]
        if (char == ' ' || char == '\t' || char == '>' || char == '<') break
        if (char == '/' && i + 1 < text.length && text[i + 1] == '>') break
        i++
    }
    return text.substring(from, i) to i
}
