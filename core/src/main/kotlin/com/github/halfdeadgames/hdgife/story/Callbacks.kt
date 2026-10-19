package com.github.halfdeadgames.hdgife.story

/**
 * Picks a [NumberFormat] for one stat, used by ink's `stat_text` and `stat_text_of`. Returns null to use
 * `StoryManager.numberFormat`. Called each time ink prints a stat, so keep it cheap. Callbacks are set as
 * `StoryManager` properties: `formatOverride = NumberFormatOverride { entity, stat -> ... }`.
 */
public fun interface NumberFormatOverride {
    public fun formatFor(entityId: String, statName: String): NumberFormat?
}

/**
 * Turns a value of a type the engine does not map itself into something ink can hold: an `Int`, `Float`, `Boolean`
 * or `String`. Returning null (or the same value) means `toString()`.
 */
public fun interface ValueConverter {
    public fun convert(value: Any): Any?
}

/** Receives each `emit` event, in order. */
public fun interface StoryEventListener {
    public fun onEvent(event: StoryEvent)
}

/**
 * Builds the ink variable name that `StoryManager.syncStats` writes a stat to. Default `StoryManager.defaultVariableName`.
 */
public fun interface VariableNamer {
    public fun nameFor(entityId: String, statName: String): String
}

/**
 * Cleans the plain text of each output line and choice, after style markers are removed. Default [cleanLine].
 */
public fun interface TextCleaner {
    public fun clean(text: String): String
}
