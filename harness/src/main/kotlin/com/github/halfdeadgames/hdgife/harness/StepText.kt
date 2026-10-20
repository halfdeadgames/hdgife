package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.graphics.Colors
import com.github.halfdeadgames.hdgife.story.Comparison
import com.github.halfdeadgames.hdgife.story.DialogChoice
import com.github.halfdeadgames.hdgife.story.DialogLine
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.RequirementResult
import com.github.halfdeadgames.hdgife.story.StoryEvent
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.Tags
import com.github.halfdeadgames.hdgife.story.TextMarker
import com.github.halfdeadgames.hdgife.story.TextSpan

internal fun esc(text: String): String = text.replace("[", "[[")

private const val titleMarkup = "[#9ecbff]"
private const val dimMarkup = "[#8a8a8a]"

internal val animatedStyleNames = setOf("shake", "wave", "bounce")

internal val flashMarkerNames = setOf("slam", "flash")

private val speakerColorNames = arrayOf("SKY", "SALMON", "LIME", "GOLD", "VIOLET", "TEAL", "CORAL", "CHARTREUSE")

internal class SpeakerColors {
    private val assigned = HashMap<String, String>()

    fun of(name: String): String = assigned.getOrPut(name) { speakerColorNames[assigned.size % speakerColorNames.size] }
}

private fun colorOf(style: Style): String? {
    val hex = style.hexColor()
    if (hex != null) return "#%08x".format(hex)
    val name = style.value?.uppercase()
    return if (name != null && Colors.get(name) != null) name else null
}

private fun isDrawn(style: Style) = style.name == "color" && colorOf(style) != null

internal fun styleColor(span: TextSpan): String? {
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

internal fun show(value: Any?): String = if (value is Float && value % 1f == 0f) value.toInt().toString() else value.toString()

internal fun describeStyle(style: Style) = buildString {
    append(style.name)
    style.value?.let { append('=').append(it) }
    for ((key, value) in style.params) append(' ').append(key).append('=').append(value)
}

internal fun describeMarker(marker: TextMarker) = buildString {
    append(marker.name)
    marker.value?.let { append('=').append(it) }
    for ((key, value) in marker.params) append(' ').append(key).append('=').append(value)
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

internal fun toMarkup(spans: List<TextSpan>, markers: List<TextMarker>, stillOpen: List<Style> = emptyList()): String = buildString {
    walkRuns(spans, markers, stillOpen) { kind, text, color, _, _ ->
        if (kind == RunKind.Text && color != null) {
            append('[').append(color).append(']').append(esc(text)).append("[]")
        } else if (kind == RunKind.Text) {
            append(esc(text))
        } else {
            append(dimMarkup).append(esc(text)).append("[]")
        }
    }
}

internal fun describe(requirement: RequirementResult, nested: Boolean = false): String {
    val verdict = if (nested) (if (requirement.isMet) " = ok" else " = no") else ""
    return when (requirement) {
        is RequirementResult.Unreadable -> "bad requirement '${requirement.raw}'$verdict"
        is RequirementResult.MissingStat -> "${requirement.entity ?: "?"}.${requirement.stat} ${requirement.op.symbol} ${show(requirement.target)} (stat missing)$verdict"
        is RequirementResult.Met -> "${requirement.entity}.${requirement.stat} ${requirement.op.symbol} ${show(requirement.target)} (have ${show(requirement.actual)})$verdict"
        is RequirementResult.Unmet -> "${requirement.entity}.${requirement.stat} ${requirement.op.symbol} ${show(requirement.target)} (have ${show(requirement.actual)})$verdict"
        is RequirementResult.Mismatch -> "${requirement.entity}.${requirement.stat} ${requirement.op.symbol} ${show(requirement.target)} (have ${show(requirement.actual)}, type mismatch)$verdict"
        is RequirementResult.AnyOf -> "any of [" + requirement.results.joinToString(" | ") { describe(it, true) } + "]$verdict"
        is RequirementResult.Not -> "not [" + describe(requirement.operand, true) + "]$verdict"
    }
}

private fun StringBuilder.requirementTree(requirement: RequirementResult, indent: Int) {
    when (requirement) {
        is RequirementResult.AnyOf -> {
            repeat(indent) { append(' ') }
            append("any of (isMet=").append(requirement.isMet).append(")\n")
            for (alternative in requirement.results) requirementTree(alternative, indent + 2)
        }
        is RequirementResult.Not -> {
            repeat(indent) { append(' ') }
            append("not (isMet=").append(requirement.isMet).append(")\n")
            requirementTree(requirement.operand, indent + 2)
        }
        else -> {
            repeat(indent) { append(' ') }
            append(esc(describe(requirement))).append(" -> ").append(requirement::class.simpleName).append('\n')
        }
    }
}

internal fun choiceMarkup(choice: DialogChoice): String = buildString {
    append(toMarkup(choice.spans, choice.markers))
    if (!choice.isAvailable) {
        val needs = choice.requirements.filterNot { it.isMet }.joinToString("; ") { describe(it) }
        append("  [#ff8a80](needs ").append(esc(needs)).append(")[]")
    } else if (choice.requirements.isNotEmpty()) {
        append("  [#7fbf7f](met ").append(esc(choice.requirements.joinToString("; ") { describe(it) })).append(")[]")
    }
}

internal fun describeTag(raw: String, all: Tags): String {
    val single = Tags.parse(listOf(raw))
    val key = single.map.keys.firstOrNull() ?: return raw
    val type = when {
        ':' !in raw -> "bare"
        single.int(key) != null -> "int"
        single.float(key) != null -> "float"
        single.bool(key) != null -> "bool"
        else -> "text"
    }
    val repeats = all.all(key).size
    val order = if (repeats > 1) ", ${all.raw.filter { it.substringBefore(':').trim() == key }.indexOf(raw) + 1} of $repeats" else ""
    return "# $raw ($type$order)"
}

internal fun describeEvent(event: StoryEvent): String =
    event.name + "(" + event.args.joinToString(", ") { "${it ?: "null"}: ${typeName(it)}" } + ")"

internal fun typeName(value: Any?): String = when (value) {
    null -> "null"
    is Int -> "Int"
    is Float -> "Float"
    is Boolean -> "Boolean"
    is String -> "String"
    else -> value.javaClass.simpleName
}

private fun typed(value: Any?): String = if (value == null) "null" else "${esc(value.toString())} (${typeName(value)})"

private fun quote(text: String) = "\"" + esc(text.replace("\n", "\\n")) + "\""

private fun styleChips(styles: List<Style>): String =
    if (styles.isEmpty()) "-" else styles.joinToString(" + ") { style ->
        val hex = style.hexColor()
        esc(describeStyle(style)) + if (hex != null) " (hex %08x)".format(hex) else ""
    }

private fun StringBuilder.title(text: String) = append(titleMarkup).append(text).append("[]\n")

private fun StringBuilder.field(indent: Int, key: String, value: String) {
    repeat(indent) { append(' ') }
    append(key.padEnd(10)).append(": ").append(value).append('\n')
}

private fun StringBuilder.requirementFields(
    entity: String?, stat: String, op: Comparison, target: Any, actual: Any?, hasActual: Boolean = true,
) {
    append("      entity=").append(entity?.let { esc(it) } ?: "null")
    append(" stat=").append(esc(stat)).append(" op=").append(op.symbol).append('\n')
    append("      target=").append(typed(target))
    if (hasActual) append(" actual=").append(typed(actual))
    append('\n')
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

fun formatRaw(step: DialogStep, number: Int, note: String? = null, linesFrom: DialogStep = step): String = buildString {
    title("STEP $number")
    field(0, "ended", step.isEnded.toString())
    note?.let { field(0, "note", esc(it)) }
    append('\n')
    title("LINES (${linesFrom.lines.size})")
    for ((i, line) in linesFrom.lines.withIndex()) {
        append(dimMarkup).append("[[").append(i).append("][]\n")
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
        append(dimMarkup).append("[[").append(choice.index).append("][]\n")
        field(2, "text", quote(choice.text))
        field(2, "available", choice.isAvailable.toString())
        tags(2, choice.tags)
        spansAndMarkers(2, choice.spans, choice.markers)
        field(2, "requires", choice.requirements.size.toString())
        for (requirement in choice.requirements) {
            append("    - ").append(quote(requirement.raw)).append('\n')
            when (requirement) {
                is RequirementResult.Met -> requirementFields(requirement.entity, requirement.stat, requirement.op, requirement.target, requirement.actual)
                is RequirementResult.Unmet -> requirementFields(requirement.entity, requirement.stat, requirement.op, requirement.target, requirement.actual)
                is RequirementResult.Mismatch -> requirementFields(requirement.entity, requirement.stat, requirement.op, requirement.target, requirement.actual)
                is RequirementResult.MissingStat -> requirementFields(requirement.entity, requirement.stat, requirement.op, requirement.target, null, hasActual = false)
                is RequirementResult.AnyOf, is RequirementResult.Not -> requirementTree(requirement, 6)
                is RequirementResult.Unreadable -> {}
            }
            append("      result=").append(requirement::class.simpleName).append(" isMet=").append(requirement.isMet).append('\n')
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
