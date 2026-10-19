package com.github.halfdeadgames.hdgife

/**
 * Base class of every exception hdgife throws on purpose for a failure the game can meet at run time. Catch this one
 * to handle them all, or a subclass to tell them apart.
 *
 * Mistakes in how the API is called (a bad argument, an unknown choice index, an undeclared variable) are not
 * [HdgifeException]s. Those are plain `IllegalArgumentException`.
 */
public abstract class HdgifeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
