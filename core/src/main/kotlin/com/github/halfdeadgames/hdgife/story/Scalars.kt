package com.github.halfdeadgames.hdgife.story

import java.util.Locale

private val INT = Regex("[+-]?[0-9]+")
private val FLOAT = Regex("""[+-]?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][+-]?[0-9]+)?""")

/**
 * Reads [text] as an ink scalar: an `Int` (optional sign and ASCII digits that fit), else a finite `Float` (optional
 * sign, ASCII digits with `.`, optional exponent), else a `Boolean` (`true` or `false` in any case), else null.
 * Not affected by the default locale. Shared by [Tags] and the requirement evaluator.
 */
internal fun parseScalar(text: String): Any? {
    if (INT.matches(text)) text.toIntOrNull()?.let { return it }
    if (FLOAT.matches(text)) return text.toFloatOrNull()?.takeIf { it.isFinite() }
    return when (text.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** The Float widened through its shortest decimal form, so `0.1f` becomes `0.1` and not `0.10000000149011612`. */
internal fun Float.toCleanDouble(): Double = toString().toDouble()
