package com.github.halfdeadgames.hdgife.state

public fun interface NarrativeStateProvider {
    public fun getStat(entityId: String, statName: String): Any?
}
