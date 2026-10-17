package com.github.halfdeadgames.hdgife.compiler

/**
 * Thrown when ink source fails to compile or load. [errors] holds the messages. [source] is the name of the
 * failing [InkSource] when it is known (a source that couldn't be read), otherwise null. Ink's own
 * messages already name the file and line.
 */
class InkCompileException(
    val errors: List<String>,
    val source: String? = null,
    cause: Throwable? = null,
) : RuntimeException(
    "Ink compilation failed" + (source?.let { " in '$it'" } ?: "") + ":\n" + errors.joinToString("\n"),
    cause,
)
