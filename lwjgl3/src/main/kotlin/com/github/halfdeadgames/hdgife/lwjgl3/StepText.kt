package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.graphics.Colors
import com.github.halfdeadgames.hdgife.story.ChoiceRequirement
import com.github.halfdeadgames.hdgife.story.DialogChoice
import com.github.halfdeadgames.hdgife.story.DialogLine
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.Tags
import com.github.halfdeadgames.hdgife.story.TextMarker
import com.github.halfdeadgames.hdgife.story.TextSpan

/*
 * Text building for the two panels. Everything here runs once per step, never per frame.
 * Both panels draw with libGDX color markup (`[#rrggbbaa]text[]`), so a literal `[` is written `[[`.
 */

/** Escapes engine text for color markup. */
internal fun esc(text: String): String = text.replace("[", "[[")

/** Section titles in the raw panel. */
private const val TITLE = "[#9ecbff]"
private const val DIM = "[#8a8a8a]"

/** Style names this harness draws itself. Everything else is an effect: annotated, and shaken or bobbed. */
private val DRAWN = setOf("em", "b", "color")

/** Effect names that make the rendered line move for a moment. */
internal val SHAKES = setOf("shake")
internal val BOBS = setOf("wave", "bounce")

/** Speaker name colors, given out in order of first appearance. All are in libGDX's Colors table. */
private val SPEAKER_COLORS = arrayOf("SKY", "SALMON", "LIME", "GOLD", "VIOLET", "TEAL", "CORAL", "CHARTREUSE")

/** Gives each speaker a color the first time it is seen and keeps it. */
internal class SpeakerColors {
    private val assigned = HashMap<String, String>()

    fun of(name: String): String = assigned.getOrPut(name) { SPEAKER_COLORS[assigned.size % SPEAKER_COLORS.size] }
}

/**
 * Harness-only mapping from engine style names to libGDX color markup. The engine just reports names.
 * `em` and `b` get fixed colors. `color` is resolved here: hex (`Style.hexColor()` is not null) becomes
 * `#rrggbbaa`, anything else is looked up by name in libGDX's `Colors` table. The innermost style that sets a color wins.
 */
internal fun styleColor(span: TextSpan): String? {
    var result: String? = null
    for (style in span.styles) {
        when (style.name) {
            "em" -> result = "#ffd27f"
            "b" -> result = "#ffffff"
            "color" -> {
                val hex = style.hexColor()
                if (hex != null) {
                    result = "#%08x".format(hex)
                } else {
                    val name = style.value?.uppercase()
                    if (name != null && Colors.get(name) != null) result = name
                }
            }
        }
    }
    return result
}

internal fun show(value: Any?): String = if (value is Float && value % 1f == 0f) value.toInt().toString() else value.toString()

internal fun describeStyle(style: Style) = buildString {
    append(style.name)
    style.value?.let { append('=').append(it) }
    for ((k, v) in style.params) append(' ').append(k).append('=').append(v)
}

internal fun describeMarker(marker: TextMarker) = buildString {
    append(marker.name)
    marker.value?.let { append('=').append(it) }
    for ((k, v) in marker.params) append(' ').append(k).append('=').append(v)
}

/** Effect styles of a span (everything the harness does not draw as a color). */
private fun effectsOf(span: TextSpan): List<Style> = span.styles.filter { it.name !in DRAWN }

/** True when any span of the line carries a style whose name is in [names]. */
internal fun hasEffect(spans: List<TextSpan>, names: Set<String>): Boolean = spans.any { s -> s.styles.any { it.name in names } }

/**
 * Spans and markers to libGDX markup. Colored spans get a color tag. An effect span is followed by a dim `~name`
 * note. A point marker is written as a dim `|name=value|` at its offset into the plain text.
 */
internal fun toMarkup(spans: List<TextSpan>, markers: List<TextMarker>): String = buildString {
    var written = 0 // plain characters written so far, to compare with marker offsets
    var m = 0
    fun marker(marker: TextMarker) = append(DIM).append('|').append(esc(describeMarker(marker))).append("|[]")
    for ((spanIndex, span) in spans.withIndex()) {
        val color = styleColor(span)
        val text = span.text
        var start = 0
        while (start < text.length) {
            while (m < markers.size && markers[m].offset <= written) marker(markers[m++])
            val untilMarker = if (m < markers.size) markers[m].offset - written else Int.MAX_VALUE
            val end = if (untilMarker >= text.length - start) text.length else start + untilMarker
            if (color != null) append('[').append(color).append(']')
            append(esc(text.substring(start, end)))
            if (color != null) append("[]")
            written += end - start
            start = end
        }
        // one note per effect, where its run of spans ends
        val following = spans.getOrNull(spanIndex + 1)
        for (effect in effectsOf(span)) {
            if (following == null || effect !in following.styles) append(DIM).append('~').append(esc(effect.name)).append("[]")
        }
    }
    while (m < markers.size) marker(markers[m++])
}

/** A line as the rendered panel shows it: the speaker in a name color, then the text. */
internal fun lineMarkup(line: DialogLine, colors: SpeakerColors): String {
    val prefix = line.speaker?.let { "[${colors.of(it)}]${esc(it)}:[] " } ?: ""
    return prefix + toMarkup(line.spans, line.markers)
}

/** What a requirement says, in a short form: `player.wits >= 7 (have 6)`. */
internal fun describe(r: ChoiceRequirement): String {
    if (!r.valid) return "bad requirement '${r.raw}'"
    val have = r.actual?.let { "have ${show(it)}" } ?: "stat missing"
    val why = if (r.mismatch) ", type mismatch" else ""
    return "${r.entity ?: "?"}.${r.stat} ${r.op?.symbol} ${show(r.target)} ($have$why)"
}

/** Markup of a choice button: its text, and for an unavailable one what it needs. */
internal fun choiceMarkup(choice: DialogChoice): String = buildString {
    append(toMarkup(choice.spans, choice.markers))
    if (!choice.available) {
        val needs = choice.requirements.filterNot { it.met }.joinToString("; ", transform = ::describe)
        append("  [#ff8a80](needs ").append(esc(needs)).append(")[]")
    }
}

// ---- raw panel ------------------------------------------------------------------------------------------------

private fun typeName(value: Any?): String = when (value) {
    null -> "null"
    is Int -> "Int"
    is Float -> "Float"
    is Boolean -> "Boolean"
    is String -> "String"
    else -> value.javaClass.simpleName
}

/** A value with its type, so an Int target and a Float actual can be told apart: `7 (Int)`. */
private fun typed(value: Any?): String = if (value == null) "null" else "${esc(value.toString())} (${typeName(value)})"

private fun quote(text: String) = "\"" + esc(text.replace("\n", "\\n")) + "\""

private fun styleChips(styles: List<Style>): String =
    if (styles.isEmpty()) "-" else styles.joinToString(" + ") { style ->
        val hex = style.hexColor()
        esc(describeStyle(style)) + if (hex != null) " (hex %08x)".format(hex) else ""
    }

private fun StringBuilder.title(text: String) = append(TITLE).append(text).append("[]\n")

private fun StringBuilder.field(indent: Int, key: String, value: String) {
    repeat(indent) { append(' ') }
    append(key.padEnd(10)).append(": ").append(value).append('\n')
}

private fun StringBuilder.tags(indent: Int, tags: Tags) {
    field(indent, "tags.raw", tags.raw.joinToString(", ", "[[", "]") { quote(it) })
    field(indent, "tags.map", esc(tags.map.toString()))
}

private fun StringBuilder.spansAndMarkers(indent: Int, spans: List<TextSpan>, markers: List<TextMarker>) {
    field(indent, "spans", spans.size.toString())
    for ((i, span) in spans.withIndex()) {
        repeat(indent + 2) { append(' ') }
        append('#').append(i).append(' ').append(quote(span.text)).append("  styles: ").append(styleChips(span.styles)).append('\n')
    }
    field(indent, "markers", markers.size.toString())
    for (marker in markers) {
        repeat(indent + 2) { append(' ') }
        append(esc(describeMarker(marker))).append(" @").append(marker.offset).append('\n')
    }
}

/**
 * The exact [DialogStep] the engine gave, as readable text: every field, nothing left out. [number] counts the
 * steps shown. [note] says when the harness changed anything before showing it (a restored save, refreshed choices).
 * [linesFrom] is the step whose lines and step tags are listed, [step] itself unless the harness kept the lines of an
 * earlier step (after a load).
 */
fun formatRaw(step: DialogStep, number: Int, note: String? = null, linesFrom: DialogStep = step): String = buildString {
    title("STEP $number")
    field(0, "ended", step.ended.toString())
    note?.let { field(0, "note", esc(it)) }
    append('\n')
    title("LINES (${linesFrom.lines.size})")
    for ((i, line) in linesFrom.lines.withIndex()) {
        append(DIM).append("[[").append(i).append("][]\n")
        field(2, "text", quote(line.text))
        field(2, "speaker", line.speaker?.let { esc(it) } ?: "null")
        tags(2, line.tags)
        spansAndMarkers(2, line.spans, line.markers)
    }
    append('\n')
    title("STEP TAGS")
    tags(0, linesFrom.tags)
    append('\n')
    title("CHOICES (${step.choices.size})")
    for (choice in step.choices) {
        append(DIM).append("[[").append(choice.index).append("][]\n")
        field(2, "text", quote(choice.text))
        field(2, "available", choice.available.toString())
        tags(2, choice.tags)
        spansAndMarkers(2, choice.spans, choice.markers)
        field(2, "requires", choice.requirements.size.toString())
        for (r in choice.requirements) {
            append("    - ").append(quote(r.raw)).append('\n')
            append("      entity=").append(r.entity?.let { esc(it) } ?: "null")
            append(" stat=").append(esc(r.stat.ifEmpty { "-" }))
            append(" op=").append(r.op?.symbol ?: "null").append('\n')
            append("      target=").append(typed(r.target)).append(" actual=").append(typed(r.actual)).append('\n')
            append("      met=").append(r.met).append(" mismatch=").append(r.mismatch).append(" valid=").append(r.valid).append('\n')
        }
    }
    append('\n')
    title("EVENTS (${step.events.size})")
    for (event in step.events) {
        append("  ").append(esc(event.name)).append('(')
        event.args.joinTo(this) { typed(it) }
        append(")  lineIndex=").append(event.lineIndex).append("  id=").append(event.id).append('\n')
    }
    append('\n')
    title("OPEN STYLES (${step.openStyles.size})")
    field(0, "open", styleChips(step.openStyles))
}
