package com.github.halfdeadgames.hdgife.compiler

import com.github.halfdeadgames.hdgife.HdgifeException

public class InkCompileException internal constructor(
    public val errors: List<String>,
    public val source: String? = null,
    cause: Throwable? = null,
) : HdgifeException(
    "Ink compilation failed" + (source?.let { " in '$it'" } ?: "") + ":\n" + errors.joinToString("\n"),
    cause,
)
