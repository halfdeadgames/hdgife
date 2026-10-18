package com.github.halfdeadgames.hdgife.story

import com.github.halfdeadgames.hdgife.HdgifeException

/**
 * Thrown by `StoryManager.loadState` when the string is not a usable save: a broken envelope (no version, no ink
 * state, a damaged style entry), a save written by a newer version of the engine, or a state that ink itself
 * refuses to load (it is then the [cause]). The manager's story is left as ink left it, so after this the usual
 * move is to load a good save or `reset`.
 */
class SaveStateException internal constructor(message: String, cause: Throwable? = null) : HdgifeException(message, cause)

/**
 * Thrown when the `hdgife.ink` in a story is not the version this engine expects (an old one, or one from a newer
 * engine), and when an operation needs the library and the story does not include it (`StoryManager.seedRandom`).
 * [found] is the library version in the story (0 for an old one that has no version, or none), [expected] is
 * `HdgifeInk.VERSION`. For a missing include [found] is 0 too, and the message says so.
 */
class IncompatibleLibraryException internal constructor(
    message: String,
    val found: Int,
    val expected: Int = com.github.halfdeadgames.hdgife.HdgifeInk.VERSION,
) : HdgifeException(message)

/**
 * Thrown by `StoryManager.beginScene` when ink cannot find the knot or stitch path. Ink's own `StoryException` is
 * the [cause]. [path] is the path that was asked for.
 */
class StoryPathException internal constructor(val path: String, cause: Throwable) :
    HdgifeException("Cannot start the scene at '$path': ${cause.message}", cause)
