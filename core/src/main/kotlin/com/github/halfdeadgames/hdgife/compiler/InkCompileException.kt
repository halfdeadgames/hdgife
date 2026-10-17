package com.github.halfdeadgames.hdgife.compiler

/** Thrown when ink source fails to compile. [errors] holds the compiler messages. */
class InkCompileException(val errors: List<String>) :
    RuntimeException("Ink compilation failed:\n" + errors.joinToString("\n"))
