package com.github.halfdeadgames.hdgife.story

/**
 * One stretch of story output, from the current position up to the next choice or the end.
 *
 * [text] is clean plain text. Paragraphs are separated by `\n`. [tags] are all ink tags seen in it.
 * [ended] is true when there are no choices and nothing more to continue.
 */
data class DialogStep(
    val text: String,
    val tags: List<String>,
    val choices: List<DialogChoice>,
    val ended: Boolean,
)

/** A choice offered by ink. [index] is what you pass to `StoryManager.choose`. */
data class DialogChoice(val index: Int, val text: String, val tags: List<String>)
