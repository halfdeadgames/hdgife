package com.github.halfdeadgames.hdgife.state

/**
 * Implemented by the game to expose entity stats to the narrative layer.
 *
 * A stat can be any Kotlin value. The engine maps it to an ink value when ink reads it:
 *
 * | Kotlin | Ink |
 * | --- | --- |
 * | `Int`, `Short`, `Byte`, `Long` (in Int range) | int |
 * | `Float`, `Double`, a `Long` outside the Int range | float |
 * | `Boolean` | bool |
 * | `String`, `CharSequence`, `Char` | string |
 * | `Enum` | string, its `name` |
 * | `null` | the stat is missing |
 * | anything else | `toString()`, or the result of `StoryManager.valueConverter` |
 *
 * The return type is `Any?` so games can hand back plain values with no wrapper, at the cost of a compile-time type
 * check: comparisons that don't fit the type are reported as mismatches instead.
 */
public fun interface NarrativeStateProvider {
    /**
     * Current value of [statName] on [entityId], or null if the entity has no such stat. Called live from ink,
     * so keep it cheap. Null makes `stat`/`stat_of` give 0, `has_stat` false and requirements unmet.
     *
     * Don't throw: the engine does not catch it. A throw while ink runs (`stat`, `stat_text`, requirements)
     * comes out of `StoryManager.next`/`choose` as a `StoryStepException` that keeps the lines built so far. A throw in
     * `syncStats` comes out as it is.
     */
    public fun getStat(entityId: String, statName: String): Any?
}
