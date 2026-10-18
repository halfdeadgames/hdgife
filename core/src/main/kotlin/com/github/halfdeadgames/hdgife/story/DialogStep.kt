package com.github.halfdeadgames.hdgife.story

/**
 * One stretch of story output, from the current position up to the next choice or the end.
 *
 * [lines] are the non-empty output lines in order. [text] is their plain text joined with `\n`.
 * [tags] are all tags seen in the step (see [Tags] for repeated keys).
 * [events] are the `emit` calls ink made in this step, in order (see [StoryEvent]). The engine never acts on them.
 * [openStyles] are the styles still open at the end of the step. They stay open into the next step, so the
 * game can keep styling across a choice. Empty when [ended].
 * [ended] is true when there are no choices and nothing more to continue.
 * The engine reports. Whether to show, hide or grey out a choice is up to the game.
 */
data class DialogStep(
    val lines: List<DialogLine>,
    val tags: Tags,
    val choices: List<DialogChoice>,
    val ended: Boolean,
    val events: List<StoryEvent> = emptyList(),
    val openStyles: List<Style> = emptyList(),
) {
    val text: String = lines.joinToString("\n") { it.text }
}

/**
 * One output line. [text] is clean plain text with style markers removed, and it is exactly the [spans]' texts
 * joined. [spans] is the same text split into styled runs, including styles left open by earlier lines.
 * [markers] are the self-closing markers of the line (`<pause=0.5/>`), with offsets into [text].
 * [speaker] is the tag speaker for this line if it has one, otherwise the persistent speaker when the line was
 * produced, otherwise null. [tags] are the tags of this line, including tags written on their own lines just
 * above it (that is how ink attaches them).
 */
data class DialogLine(
    val text: String,
    val speaker: String?,
    val tags: Tags,
    val spans: List<TextSpan>,
    val markers: List<TextMarker> = emptyList(),
)

/**
 * A choice offered by ink. [index] is what you pass to `StoryManager.choose`. [text] is plain, [spans] styled.
 * Styles in choice text are read on their own: nothing carries in from the lines above, and what is left
 * open at the end of the text is dropped. [markers] are self-closing markers, offsets into [text].
 * [tags] are the tags written inside the choice text. [requirements] are the ones declared by the `requires`
 * tag, already evaluated. [available] is true when all of them are met, or there are none.
 * Choose never refuses an unavailable choice. That is a decision for the game.
 */
data class DialogChoice(
    val index: Int,
    val text: String,
    val spans: List<TextSpan>,
    val tags: Tags,
    val requirements: List<ChoiceRequirement>,
    val markers: List<TextMarker> = emptyList(),
) {
    val available: Boolean = requirements.all { it.met }
}

/**
 * Something ink told the game with `~ emit("name", arg)`. [args] are plain Kotlin values (`Int`, `Float`,
 * `Boolean`, `String`). [lineIndex] is the index in [DialogStep.lines] of the line that was produced right
 * after the call, so the event happened just before that line is shown. It equals `lines.size` when no
 * line follows in the step. The engine reports events and never acts on them.
 */
data class StoryEvent(val name: String, val args: List<Any?>, val lineIndex: Int)

/** Comparison used by a [ChoiceRequirement]. */
enum class Comparison(val symbol: String) {
    GREATER(">"), GREATER_EQUAL(">="), LESS("<"), LESS_EQUAL("<="), EQUAL("=="), NOT_EQUAL("!=");

    /** True for `==` and `!=`, the only ones that work on strings and booleans. */
    val isEquality: Boolean get() = this == EQUAL || this == NOT_EQUAL

    fun test(actual: Double, target: Double): Boolean = when (this) {
        GREATER -> actual > target
        GREATER_EQUAL -> actual >= target
        LESS -> actual < target
        LESS_EQUAL -> actual <= target
        EQUAL -> actual == target
        NOT_EQUAL -> actual != target
    }

    companion object {
        fun parse(symbol: String): Comparison? = entries.firstOrNull { it.symbol == symbol }
    }
}

/**
 * One evaluated requirement of a choice. [actual] is the stat value found, as an ink value (`Int`, `Float`,
 * `Boolean` or `String`), or null if the stat is missing, which always counts as not [met]. [target] is the
 * value written in the requirement, read as the type of [actual]: a number (`Int` or `Float`), a `Boolean` or a
 * `String`. When [actual] is missing it is a number if the text is one, else the text.
 * [mismatch] is true when the written value or the operator doesn't fit the type of [actual] (`strength >= high`,
 * `faction > 3`, `is_injured == 4`). That is logged and never [met].
 * [raw] is the text as written. When the text could not be read, [op] is null, [entity] may be null, [stat] is
 * empty, [target] is null and [met] is false. [valid] tells these apart.
 */
data class ChoiceRequirement(
    val entity: String?,
    val stat: String,
    val op: Comparison?,
    val target: Any?,
    val actual: Any?,
    val met: Boolean,
    val raw: String,
    val mismatch: Boolean = false,
) {
    val valid: Boolean get() = op != null
}
