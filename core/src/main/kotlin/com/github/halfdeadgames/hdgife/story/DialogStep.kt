package com.github.halfdeadgames.hdgife.story

/**
 * One stretch of story output, from the current position up to the next choice or the end.
 *
 * [lines] are the non-empty output lines in order. [text] is their plain text joined with `\n`.
 * [tags] are all tags seen in the step (see [Tags] for repeated keys).
 * [ended] is true when there are no choices and nothing more to continue.
 * The engine reports. Whether to show, hide or grey out a choice is up to the game.
 */
data class DialogStep(
    val lines: List<DialogLine>,
    val tags: Tags,
    val choices: List<DialogChoice>,
    val ended: Boolean,
) {
    val text: String = lines.joinToString("\n") { it.text }
}

/**
 * One output line. [text] is clean plain text with style markers removed. [spans] is the same text split
 * into styled runs. [speaker] is the tag speaker for this line if it has one, otherwise the persistent
 * speaker when the line was produced, otherwise null. [tags] are the tags of this line, including tags
 * written on their own lines just above it (that is how ink attaches them).
 */
data class DialogLine(
    val text: String,
    val speaker: String?,
    val tags: Tags,
    val spans: List<TextSpan>,
)

/**
 * A choice offered by ink. [index] is what you pass to `StoryManager.choose`. [text] is plain, [spans] styled.
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
) {
    val available: Boolean = requirements.all { it.met }
}

/** Comparison used by a [ChoiceRequirement]. */
enum class Comparison(val symbol: String) {
    GREATER(">"), GREATER_EQUAL(">="), LESS("<"), LESS_EQUAL("<="), EQUAL("=="), NOT_EQUAL("!=");

    fun test(actual: Float, target: Float): Boolean = when (this) {
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
 * One evaluated requirement of a choice. [actual] is the stat value found, or null if the stat is missing
 * (no provider, no entity or `hasStat` false), which always counts as not [met]. [raw] is the text as written.
 * When the text could not be read, [op] is null, [entity] may be null, [stat] is empty, [target] is NaN and
 * [met] is false. [valid] tells these apart.
 */
data class ChoiceRequirement(
    val entity: String?,
    val stat: String,
    val op: Comparison?,
    val target: Float,
    val actual: Float?,
    val met: Boolean,
    val raw: String,
) {
    val valid: Boolean get() = op != null
}
