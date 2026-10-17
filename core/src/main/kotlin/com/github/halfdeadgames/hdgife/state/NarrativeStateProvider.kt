package com.github.halfdeadgames.hdgife.state

/** Implemented by the game to expose entity stats to the narrative layer. */
interface NarrativeStateProvider {
    /** Current value of [statName] on [entityId]. Called live from ink, so keep it cheap. */
    fun getStat(entityId: String, statName: String): Float

    /**
     * Whether [entityId] has [statName] at all. Ink's `has_stat` uses this, and `stat`/`stat_of`
     * return 0 when it is false. The default says yes, so simple implementers can ignore it.
     */
    fun hasStat(entityId: String, statName: String): Boolean = true
}
