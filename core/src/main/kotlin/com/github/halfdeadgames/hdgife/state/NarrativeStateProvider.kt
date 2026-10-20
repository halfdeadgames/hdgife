package com.github.halfdeadgames.hdgife.state

public fun interface NarrativeStateProvider {
    public fun getStat(entityId: String, statName: String): Any?
}

public fun mapStatProvider(stats: Map<String, Map<String, Any?>>): NarrativeStateProvider =
    NarrativeStateProvider { entityId, statName -> stats[entityId]?.get(statName) }
