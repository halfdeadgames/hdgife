package com.github.halfdeadgames.hdgife.story

/**
 * Picks a [NumberFormat] for one stat, used by ink's `stat_text` and `stat_text_of`. Returns null to use
 * `StoryManager.numberFormat`. Called each time ink prints a stat, so keep it cheap. Set it with
 * `formatOverride = NumberFormatOverride { entity, stat -> ... }` or the helper `formatOverride { entity, stat -> ... }`.
 */
public fun interface NumberFormatOverride {
    public fun formatFor(entityId: String, statName: String): NumberFormat?
}

/**
 * Turns a value of a type the engine does not map itself into something ink can hold: an `Int`, `Float`, `Boolean`
 * or `String`. Returning null (or the same value) means `toString()`. Set it with
 * `valueConverter = ValueConverter { value -> ... }` or the helper `valueConverter { value -> ... }`.
 */
public fun interface ValueConverter {
    public fun convert(value: Any): Any?
}

/**
 * Receives each `emit` event, in order. Set it with `onEvent = StoryEventListener { event -> ... }` or the helper
 * `onEvent { event -> ... }`.
 */
public fun interface StoryEventListener {
    public fun onEvent(event: StoryEvent)
}
