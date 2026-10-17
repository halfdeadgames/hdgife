package com.github.halfdeadgames.hdgife.state

/** Implemented by the game to expose entity stats to the narrative layer. */
interface NarrativeStateProvider {
    fun getStat(entityId: String, statName: String): Float
}
