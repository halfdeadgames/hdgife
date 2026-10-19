package com.github.halfdeadgames.hdgife.story

import com.github.halfdeadgames.hdgife.HdgifeException

/**
 * One stretch of story output, from the current position up to the next choice or the end.
 *
 * [lines] are the non-empty output lines in order. [text] is their plain text joined with `\n`.
 * [tags] are all tags seen in the step (see [Tags] for repeated keys).
 * [events] are the `emit` calls ink made in this step, in order (see [StoryEvent]). The engine never acts on them.
 * [openStyles] are the styles still open at the end of the step. They stay open into the next step, so the
 * game can keep styling across a choice. Empty when [isEnded].
 * [isEnded] is true when there are no choices and nothing more to continue.
 * The engine reports. Whether to show, hide or grey out a choice is up to the game.
 */
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

/**
 * One output line. [text] is clean plain text with style markers removed, and it is exactly the [spans]' texts
 * joined. [spans] is the same text split into styled runs, including styles left open by earlier lines.
 * [markers] are the self-closing markers of the line (`<pause=0.5/>`), with offsets into [text].
 * [speaker] is the tag speaker for this line if it has one, otherwise the persistent speaker when the line was
 * produced, otherwise null. [tags] are the tags of this line, including tags written on their own lines just
 * above it (that is how ink attaches them).
 */
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

/**
 * A choice offered by ink. [index] is what you pass to `StoryManager.choose`. [text] is plain, [spans] styled.
 * Styles in choice text are read on their own: nothing carries in from the lines above, and what is left
 * open at the end of the text is dropped. [markers] are self-closing markers, offsets into [text].
 * [tags] are the tags written inside the choice text. [requirements] are the ones declared by the `requires`
 * tag, already evaluated. [isAvailable] is true when all of them are met, or there are none.
 * Choose never refuses an unavailable choice. That is a decision for the game.
 */
public class DialogChoice internal constructor(
    public val index: Int,
    public val text: String,
    public val spans: List<TextSpan>,
    public val tags: Tags,
    public val requirements: List<RequirementResult>,
    public val markers: List<TextMarker> = emptyList(),
) {
    /** True when every requirement is met, or there are none. */
    public val isAvailable: Boolean = requirements.all { it.isMet }

    override fun equals(other: Any?): Boolean = this === other || other is DialogChoice &&
        index == other.index && text == other.text && spans == other.spans && tags == other.tags &&
        requirements == other.requirements && markers == other.markers

    override fun hashCode(): Int = listOf(index, text, spans, tags, requirements, markers).hashCode()

    override fun toString(): String =
        "DialogChoice(index=$index, text=$text, spans=$spans, tags=$tags, requirements=$requirements, markers=$markers)"
}

/**
 * Something ink told the game with `~ emit("name", arg)`. [args] are plain Kotlin values (`Int`, `Float`,
 * `Boolean`, `String`). [lineIndex] is the index in [DialogStep.lines] of the line that was produced right
 * after the call, so the event happened just before that line is shown. It equals `lines.size` when no
 * line follows in the step. The engine reports events and never acts on them.
 *
 * The typed getters ([string], [int], [float], [bool], [size]) read [args] the way `FunctionArgs` does, with the same
 * coercion, and throw [IllegalArgumentException] naming the event and the position for a missing or wrong-typed
 * argument: `event.string(0)`, `event.int(1)`.
 *
 * [id] counts the `emit` calls of the story timeline: the first emit is 1, the next 2, and so on. It is the value of
 * the ink variable `hdgife_event_seq` right after the call, so it is saved with the ink state. It is the same for the
 * same emit however many times ink ran ahead for glue, and it survives [StoryManager.saveState] and
 * [StoryManager.loadState]: play on from an earlier save and the event that comes again has the id it had before.
 * Ids rise by one in the order events are reported. To not act twice on a replayed event, keep the highest id the game
 * handled together with the save it belongs to and skip events with an id at or below it. Ids name a position on the
 * timeline, not a story event: a different branch after a load can give another event the same id. [StoryManager.reset]
 * starts again at 1. [id] is 0 only for an event the game built itself.
 */
public class StoryEvent internal constructor(
    public val name: String,
    public val args: List<Any?>,
    public val lineIndex: Int,
    public val id: Int = 0,
) {
    private val reader by lazy(LazyThreadSafetyMode.NONE) { ArgReader("event '$name'", args) }

    /** How many arguments the event has. */
    public val size: Int get() = args.size

    /** Argument [index] as a string. Any value is accepted (`toString()`). Throws [IllegalArgumentException] if there is none. */
    public fun string(index: Int): String = reader.string(index)

    /** Argument [index] as an int. Only an ink int is accepted, else [IllegalArgumentException]. */
    public fun int(index: Int): Int = reader.int(index)

    /** Argument [index] as a float. An ink int is accepted. */
    public fun float(index: Int): Float = reader.float(index)

    /** Argument [index] as a bool. An ink int is accepted, nonzero is true. */
    public fun bool(index: Int): Boolean = reader.bool(index)

    internal fun atLine(lineIndex: Int) = StoryEvent(name, args, lineIndex, id)

    override fun equals(other: Any?): Boolean = this === other || other is StoryEvent &&
        name == other.name && args == other.args && lineIndex == other.lineIndex && id == other.id

    override fun hashCode(): Int = listOf(name, args, lineIndex, id).hashCode()

    override fun toString(): String = "StoryEvent(name=$name, args=$args, lineIndex=$lineIndex, id=$id)"
}

/**
 * Thrown by `StoryManager.next` (and `choose`) when ink fails while continuing: a bound function threw, or ink
 * hit a runtime error. [step] holds what was built before the failure (the lines so far, their events and the
 * open styles; no choices, not ended). The original exception is the [cause].
 */
public class StoryStepException internal constructor(message: String, cause: Throwable, public val step: DialogStep) :
    HdgifeException(message, cause)
