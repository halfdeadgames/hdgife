package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.state.NarrativeStateProvider
import ktx.log.logger

private val log = logger<StoryManager>()

/**
 * Wraps a [Story] and syncs game stats into ink variables.
 *
 * Story is not thread-safe. Call this from one thread, for example the render thread
 * inside `KtxAsync.launch`.
 */
class StoryManager(
    var story: Story,
    private val stateProvider: NarrativeStateProvider,
    private val variableNamer: (entityId: String, statName: String) -> String = ::defaultVariableName,
) {
    /**
     * Copies each stat into the ink variable named by [variableNamer]. Variables not declared
     * with `VAR` in the ink source are skipped.
     */
    fun syncStatsToInk(entityId: String, statNames: List<String>) {
        val variables = story.variablesState
        for (stat in statNames) {
            val name = variableNamer(entityId, stat)
            if (variables.get(name) == null) {
                log.debug { "Ink variable '$name' is not declared, skipping" }
                continue
            }
            variables.set(name, stateProvider.getStat(entityId, stat))
        }
    }

    companion object {
        /** Builds `entityId_statName`, replacing characters outside `[A-Za-z0-9_]` with `_`. */
        fun defaultVariableName(entityId: String, statName: String): String =
            "${entityId}_$statName".replace(Regex("[^A-Za-z0-9_]"), "_")
    }
}
