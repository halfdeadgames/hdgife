package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Container
import com.bladecoder.ink.runtime.IntValue
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.HdgifeInk

private const val SPEAKER = "hdgife_speaker"
private const val VERSION_FUNCTION = "hdgife_lib_version"
private const val UTIL_MARKER = "hdgife_util_loaded"

/** True when [story] includes hdgife.ink (any version): it declares the speaker variable. */
internal fun includesCoreLibrary(story: Story): Boolean = story.variablesState.get(SPEAKER) != null

/** True when [story] includes hdgife_util.ink. */
internal fun includesUtilLibrary(story: Story): Boolean = story.hasFunction(UTIL_MARKER)

/**
 * The version of the hdgife.ink in [story], read from the compiled story without running it (the library's
 * `CONST hdgife_version` is inlined into the `hdgife_lib_version` function). 0 when the story has no version: an
 * old library, or none. Only meaningful when [includesCoreLibrary].
 */
internal fun coreLibraryVersion(story: Story): Int {
    val knot = story.mainContentContainer.namedContent[VERSION_FUNCTION] as? Container ?: return 0
    return firstInt(knot) ?: 0
}

private fun firstInt(container: Container): Int? {
    for (item in container.content) {
        if (item is IntValue) return item.value
        if (item is Container) firstInt(item)?.let { return it }
    }
    return null
}

/** The problem with the hdgife.ink in [story], or null when there is none or the story does not include the library. */
internal fun libraryProblem(story: Story): IncompatibleLibraryException? {
    if (!includesCoreLibrary(story)) return null
    val found = coreLibraryVersion(story)
    if (found == HdgifeInk.VERSION) return null
    val why = if (found == 0) "is out of date (it has no version)" else "is version $found"
    val advice = if (found < HdgifeInk.VERSION) "so emit events would be lost" else "which is newer than this engine understands"
    return IncompatibleLibraryException(
        "The story's hdgife.ink $why, but this engine needs version ${HdgifeInk.VERSION}, $advice. " +
            "Recompile the story with the hdgife.ink bundled with this version.",
        found,
    )
}
