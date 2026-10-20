package com.github.halfdeadgames.hdgife.story

import com.github.halfdeadgames.hdgife.HdgifeException

public class DialogStep internal constructor(
    public val lines: List<DialogLine>,
    public val tags: Tags,
    public val choices: List<DialogChoice>,
    public val isEnded: Boolean,
    public val events: List<StoryEvent> = emptyList(),
    public val openStyles: List<Style> = emptyList(),
) {
    public val text: String = lines.joinToString("\n") { it.text }

    override fun equals(other: Any?): Boolean = this === other || other is DialogStep &&
        lines == other.lines && tags == other.tags && choices == other.choices && isEnded == other.isEnded &&
        events == other.events && openStyles == other.openStyles

    override fun hashCode(): Int = listOf(lines, tags, choices, isEnded, events, openStyles).hashCode()

    override fun toString(): String =
        "DialogStep(lines=$lines, tags=$tags, choices=$choices, isEnded=$isEnded, events=$events, openStyles=$openStyles)"
}

public class DialogLine internal constructor(
    public val text: String,
    public val speaker: String?,
    public val tags: Tags,
    public val spans: List<TextSpan>,
    public val markers: List<TextMarker> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = this === other || other is DialogLine &&
        text == other.text && speaker == other.speaker && tags == other.tags && spans == other.spans && markers == other.markers

    override fun hashCode(): Int = listOf(text, speaker, tags, spans, markers).hashCode()

    override fun toString(): String = "DialogLine(text=$text, speaker=$speaker, tags=$tags, spans=$spans, markers=$markers)"
}

public class DialogChoice internal constructor(
    public val index: Int,
    public val text: String,
    public val spans: List<TextSpan>,
    public val tags: Tags,
    public val requirements: List<RequirementResult>,
    public val markers: List<TextMarker> = emptyList(),
) {
    public val isAvailable: Boolean = requirements.all { it.isMet }

    override fun equals(other: Any?): Boolean = this === other || other is DialogChoice &&
        index == other.index && text == other.text && spans == other.spans && tags == other.tags &&
        requirements == other.requirements && markers == other.markers

    override fun hashCode(): Int = listOf(index, text, spans, tags, requirements, markers).hashCode()

    override fun toString(): String =
        "DialogChoice(index=$index, text=$text, spans=$spans, tags=$tags, requirements=$requirements, markers=$markers)"
}

public class StoryEvent internal constructor(
    public val name: String,
    public val args: List<Any?>,
    public val lineIndex: Int,
    public val id: Int = 0,
) {
    private val reader by lazy(LazyThreadSafetyMode.NONE) { ArgReader("event '$name'", args) }

    public val size: Int get() = args.size

    public fun string(index: Int): String = reader.string(index)

    public fun int(index: Int): Int = reader.int(index)

    public fun float(index: Int): Float = reader.float(index)

    public fun bool(index: Int): Boolean = reader.bool(index)

    internal fun atLine(lineIndex: Int) = StoryEvent(name, args, lineIndex, id)

    override fun equals(other: Any?): Boolean = this === other || other is StoryEvent &&
        name == other.name && args == other.args && lineIndex == other.lineIndex && id == other.id

    override fun hashCode(): Int = listOf(name, args, lineIndex, id).hashCode()

    override fun toString(): String = "StoryEvent(name=$name, args=$args, lineIndex=$lineIndex, id=$id)"
}

public class StoryStepException internal constructor(message: String, cause: Throwable, public val step: DialogStep) :
    HdgifeException(message, cause)
