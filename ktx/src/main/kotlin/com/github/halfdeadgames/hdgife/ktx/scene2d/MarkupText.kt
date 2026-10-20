package com.github.halfdeadgames.hdgife.ktx.scene2d

import com.badlogic.gdx.graphics.Colors
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.TextMarker
import com.github.halfdeadgames.hdgife.story.TextSpan

private const val dimMarkup = "[#8a8a8a]"

internal val animatedStyleNames = setOf("shake", "wave", "bounce")

internal val flashMarkerNames = setOf("slam", "flash")

public fun escapeMarkup(text: String): String = text.replace("[", "[[")

public fun describeStyle(style: Style): String = buildString {
    append(style.name)
    style.value?.let { append('=').append(it) }
    for ((key, value) in style.params) append(' ').append(key).append('=').append(value)
}

public fun describeMarker(marker: TextMarker): String = buildString {
    append(marker.name)
    marker.value?.let { append('=').append(it) }
    for ((key, value) in marker.params) append(' ').append(key).append('=').append(value)
}

public fun toMarkup(spans: List<TextSpan>, markers: List<TextMarker>, stillOpen: List<Style> = emptyList()): String = buildString {
    walkRuns(spans, markers, stillOpen) { kind, text, color, _, _ -> appendRunMarkup(kind, text, color) }
}

internal fun StringBuilder.appendRunMarkup(kind: RunKind, text: String, color: String?) {
    if (kind == RunKind.Text && color != null) {
        append('[').append(color).append(']').append(escapeMarkup(text)).append("[]")
    } else if (kind == RunKind.Text) {
        append(escapeMarkup(text))
    } else {
        append(dimMarkup).append(escapeMarkup(text)).append("[]")
    }
}

internal fun speakerPrefixMarkup(speaker: String?, colorName: String?): String =
    if (speaker != null && colorName != null) "[$colorName]${escapeMarkup(speaker)}:[] " else ""

private fun colorOf(style: Style): String? {
    val hex = style.hexColor()
    if (hex != null) return "#%08x".format(hex)
    val name = style.value?.uppercase()
    return if (name != null && Colors.get(name) != null) name else null
}

private fun isDrawn(style: Style) = style.name == "color" && colorOf(style) != null

private fun styleColor(span: TextSpan): String? {
    var emphasis: String? = null
    var explicit: String? = null
    for (style in span.styles) {
        when (style.name) {
            "em" -> emphasis = "#ffd27f"
            "b" -> emphasis = "#ffffff"
            "color" -> colorOf(style)?.let { explicit = it }
        }
    }
    return explicit ?: emphasis
}

internal enum class RunKind { Text, Marker, Note }

internal fun interface RunSink {
    fun run(kind: RunKind, text: String, color: String?, effect: Style?, marker: TextMarker?)
}

internal fun walkRuns(spans: List<TextSpan>, markers: List<TextMarker>, stillOpen: List<Style>, sink: RunSink) {
    var written = 0
    var markerIndex = 0
    fun emitMarker(marker: TextMarker) = sink.run(RunKind.Marker, "|" + describeMarker(marker) + "|", null, null, marker)
    for ((spanIndex, span) in spans.withIndex()) {
        val color = styleColor(span)
        val effect = span.styles.firstOrNull { it.name in animatedStyleNames }
        val text = span.text
        var start = 0
        while (start < text.length) {
            while (markerIndex < markers.size && markers[markerIndex].offset <= written) emitMarker(markers[markerIndex++])
            val untilMarker = if (markerIndex < markers.size) markers[markerIndex].offset - written else Int.MAX_VALUE
            val end = if (untilMarker >= text.length - start) text.length else start + untilMarker
            sink.run(RunKind.Text, text.substring(start, end), color, effect, null)
            written += end - start
            start = end
        }
        val continuing = spans.getOrNull(spanIndex + 1)?.styles ?: stillOpen
        for (style in span.styles) {
            if (!isDrawn(style) && style !in continuing) {
                sink.run(RunKind.Note, "[" + describeStyle(style) + "]", null, null, null)
            }
        }
    }
    while (markerIndex < markers.size) emitMarker(markers[markerIndex++])
}
