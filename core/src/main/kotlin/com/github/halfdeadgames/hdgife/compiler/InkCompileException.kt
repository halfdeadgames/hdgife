package com.github.halfdeadgames.hdgife.compiler

import com.github.halfdeadgames.hdgife.HdgifeException

/**
 * Thrown when ink source fails to compile or load. [errors] holds the messages. [source] is the name of the
 * failing [InkSource] when it is known (a source that couldn't be read), otherwise null. Ink's own
 * messages already name the file and line. Only the compiler service creates it.
 */
public class InkCompileException internal constructor(
    public val errors: List<String>,
    public val source: String? = null,
    cause: Throwable? = null,
) : HdgifeException(
    "Ink compilation failed" + (source?.let { " in '$it'" } ?: "") + ":\n" + errors.joinToString("\n"),
    cause,
)
