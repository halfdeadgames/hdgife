package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.graphics.Colors
import com.github.halfdeadgames.hdgife.story.Comparison
import com.github.halfdeadgames.hdgife.story.DialogChoice
import com.github.halfdeadgames.hdgife.story.DialogLine
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.RequirementResult
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.Tags
import com.github.halfdeadgames.hdgife.story.TextMarker
import com.github.halfdeadgames.hdgife.story.TextSpan

internal fun esc(text: String): String = text.replace("[", "[[")

private const val titleMarkup = "[#9ecbff]"
private const val dimMarkup = "[#8a8a8a]"

private val drawnStyleNames = setOf("em", "b", "color")

internal val shakeStyleNames = setOf("shake")
internal val bobStyleNames = setOf("wave", "bounce")

private val speakerColorNames = arrayOf("SKY", "SALMON", "LIME", "GOLD", "VIOLET", "TEAL", "CORAL", "CHARTREUSE")

internal class SpeakerColors {
    private val assigned = HashMap<String, String>()

    fun of(name: String): String = assigned.getOrPut(name) { speakerColorNames[assigned.size % speakerColorNames.size] }
}

internal fun styleColor(span: TextSpan): String? {
    var color: String? = null
    for (style in span.styles) {
        when (style.name) {
            "em" -> color = "#ffd27f"
            "b" -> color = "#ffffff"
            "color" -> {
                val hex = style.hexColor()
                if (hex != null) {
                    color = "#%08x".format(hex)
                } else {
                    val name = style.value?.uppercase()
                    if (name != null && Colors.get(name) != null) color = name
                }
            }
        }
    }
    return color
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

private fun effectsOf(span: TextSpan): List<Style> = span.styles.filter { it.name !in drawnStyleNames }

internal fun hasEffect(spans: List<TextSpan>, names: Set<String>): Boolean = spans.any { span -> span.styles.any { it.name in names } }

internal fun toMarkup(spans: List<TextSpan>, markers: List<TextMarker>): String = buildString {
    var written = 0
    var markerIndex = 0
    fun marker(marker: TextMarker) = append(dimMarkup).append('|').append(esc(describeMarker(marker))).append("|[]")
    for ((spanIndex, span) in spans.withIndex()) {
        val color = styleColor(span)
        val text = span.text
        var start = 0
        while (start < text.length) {
            while (markerIndex < markers.size && markers[markerIndex].offset <= written) marker(markers[markerIndex++])
            val untilMarker = if (markerIndex < markers.size) markers[markerIndex].offset - written else Int.MAX_VALUE
            val end = if (untilMarker >= text.length - start) text.length else start + untilMarker
            if (color != null) append('[').append(color).append(']')
            append(esc(text.substring(start, end)))
            if (color != null) append("[]")
            written += end - start
            start = end
        }
        val following = spans.getOrNull(spanIndex + 1)
        for (effect in effectsOf(span)) {
            if (following == null || effect !in following.styles) append(dimMarkup).append('~').append(esc(effect.name)).append("[]")
        }
    }
    while (markerIndex < markers.size) marker(markers[markerIndex++])
}

internal fun lineMarkup(line: DialogLine, colors: SpeakerColors): String {
    val prefix = line.speaker?.let { "[${colors.of(it)}]${esc(it)}:[] " } ?: ""
    return prefix + toMarkup(line.spans, line.markers)
}

internal fun describe(requirement: RequirementResult): String = when (requirement) {
    is RequirementResult.Unreadable -> "bad requirement '${requirement.raw}'"
    is RequirementResult.MissingStat -> "${requirement.entity ?: "?"}.${requirement.stat} ${requirement.op.symbol} ${show(requirement.target)} (stat missing)"
    is RequirementResult.Met -> "${requirement.entity}.${requirement.stat} ${requirement.op.symbol} ${show(requirement.target)} (have ${show(requirement.actual)})"
    is RequirementResult.Unmet -> "${requirement.entity}.${requirement.stat} ${requirement.op.symbol} ${show(requirement.target)} (have ${show(requirement.actual)})"
    is RequirementResult.Mismatch -> "${requirement.entity}.${requirement.stat} ${requirement.op.symbol} ${show(requirement.target)} (have ${show(requirement.actual)}, type mismatch)"
    is RequirementResult.AnyOf -> requirement.results.joinToString(" or ", transform = ::describe)
    is RequirementResult.Not -> "not (${describe(requirement.operand)})"
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
        val needs = choice.requirements.filterNot { it.isMet }.joinToString("; ", transform = ::describe)
        append("  [#ff8a80](needs ").append(esc(needs)).append(")[]")
    }
}

private fun typeName(value: Any?): String = when (value) {
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
