package com.github.halfdeadgames.hdgife.story

import java.util.Locale

private val intPattern = Regex("[+-]?[0-9]+")
private val floatPattern = Regex("""[+-]?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][+-]?[0-9]+)?""")

internal fun parseScalar(text: String): Any? {
    if (intPattern.matches(text)) text.toIntOrNull()?.let { return it }
    if (floatPattern.matches(text)) return text.toFloatOrNull()?.takeIf { it.isFinite() }
    return when (text.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

internal fun Float.toCleanDouble(): Double = toString().toDouble()
