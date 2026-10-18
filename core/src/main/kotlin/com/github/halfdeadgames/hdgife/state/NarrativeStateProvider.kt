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
 * Why `Any?` and not a sealed `StatValue` wrapper: games already hold plain Kotlin values, so a provider is
 * a one-liner with no wrapping (`NarrativeStateProvider { e, s -> hp[e] }`), a narrower return type is allowed
 * in an override (`override fun getStat(...): Float`), and one converter in the engine is the single place
 * where mapping rules live. The cost is no compile-time check on the type, so the mapping is documented and
 * lenient, and requirement comparisons that don't fit the type are reported as mismatches.
 *
 * This is a `fun interface`, so a simple numeric provider is a lambda.
 */
fun interface NarrativeStateProvider {
    /**
     * Current value of [statName] on [entityId], or null if the entity has no such stat. Called live from ink,
     * so keep it cheap. Null makes `stat`/`stat_of` give 0, `has_stat` false and requirements unmet.
     *
     * Don't throw: the engine does not catch it. A throw while ink runs (`stat`, `stat_text`, requirements)
     * comes out of `StoryManager.next`/`choose` as a `StoryStepException` that keeps the lines built so far. A throw in
     * `syncStatsToInk` comes out as it is.
     */
    fun getStat(entityId: String, statName: String): Any?
}
