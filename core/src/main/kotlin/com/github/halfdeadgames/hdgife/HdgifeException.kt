package com.github.halfdeadgames.hdgife

/**
 * Base class of every exception hdgife throws on purpose for a failure the game can meet at run time. Catch this one
 * to handle them all, or a subclass to tell them apart:
 *
 * - `InkCompileException`: ink source did not compile, or compiled JSON could not be loaded.
 * - `StoryStepException`: ink failed while `StoryManager.next` or `choose` was continuing. Carries the partial step.
 * - `SaveStateException`: `StoryManager.loadState` got a broken or too new save, or ink refused the state.
 * - `IncompatibleLibraryException`: the story's `hdgife.ink` is the wrong version, or an operation needs the library
 *   and the story does not include it (`seedRandom`).
 * - `StoryPathException`: `StoryManager.beginScene` was given a path ink cannot find.
 *
 * Mistakes in how the API is called (a bad argument, an unknown choice index, an undeclared variable) are not
 * [HdgifeException]s. Those are plain `IllegalArgumentException`.
 */
public abstract class HdgifeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
