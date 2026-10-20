package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Container
import com.bladecoder.ink.runtime.ControlCommand
import com.bladecoder.ink.runtime.IntValue
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.HdgifeInk

private const val speakerVariable = "hdgife_speaker"
private const val versionFunction = "hdgife_lib_version"
private const val utilMarker = "hdgife_util_loaded"

internal fun includesCoreLibrary(story: Story): Boolean = story.variablesState.get(speakerVariable) != null

internal fun includesUtilLibrary(story: Story): Boolean = story.hasFunction(utilMarker)

// The compiled story keeps no constants, so the version is read from the function hdgife.ink inlines it into:
// the IntValue just before that function's return command, past an optional closing EvalEnd.
internal fun coreLibraryVersion(story: Story): Int {
    val knot = story.mainContentContainer.namedContent[versionFunction] as? Container ?: return 0
    return versionBeforeReturn(knot) ?: 0
}

private fun versionBeforeReturn(container: Container): Int? {
    val items = container.content
    for (i in items.indices) {
        val item = items[i]
        if (item is ControlCommand && item.commandType == ControlCommand.CommandType.PopFunction) {
            var previousIndex = i - 1
            val beforeReturn = items.getOrNull(previousIndex)
            if (beforeReturn is ControlCommand && beforeReturn.commandType == ControlCommand.CommandType.EvalEnd) previousIndex--
            val versionValue = items.getOrNull(previousIndex)
            if (versionValue is IntValue) return versionValue.value
        }
        if (item is Container) versionBeforeReturn(item)?.let { return it }
    }
    return null
}

internal fun libraryProblem(story: Story): IncompatibleLibraryException? {
    if (!includesCoreLibrary(story)) return null
    val foundVersion = coreLibraryVersion(story)
    if (foundVersion == HdgifeInk.version) return null
    val reason = if (foundVersion == 0) "is out of date (it has no version)" else "is version $foundVersion"
    val advice = if (foundVersion < HdgifeInk.version) "so emit events would be lost" else "which is newer than this engine understands"
    return IncompatibleLibraryException(
        "The story's hdgife.ink $reason, but this engine needs version ${HdgifeInk.version}, $advice. " +
            "Recompile the story with the hdgife.ink bundled with this version.",
        foundVersion,
    )
}
