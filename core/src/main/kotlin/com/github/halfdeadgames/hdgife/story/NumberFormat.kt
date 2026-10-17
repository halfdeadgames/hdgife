package com.github.halfdeadgames.hdgife.story

import java.math.BigDecimal
import java.math.RoundingMode

/** How a number is rounded to [NumberFormat.decimals] places. */
enum class Rounding(internal val mode: RoundingMode) {
    /** Toward negative infinity: 7.65 -> 7, -2.5 -> -3. */
    FLOOR(RoundingMode.FLOOR),

    /** Toward positive infinity: 7.01 -> 8, -2.5 -> -2. */
    CEIL(RoundingMode.CEILING),

    /** Nearest, ties away from zero (half-up): 2.5 -> 3, -2.5 -> -3. */
    ROUND(RoundingMode.HALF_UP),

    /** Toward zero, dropping the rest: 7.9 -> 7, -2.9 -> -2. */
    TRUNCATE(RoundingMode.DOWN);

    companion object {
        /**
         * Reads a mode name from ink: `floor`, `ceil` (or `ceiling`), `round`, `trunc` (or `truncate`),
         * ignoring case. Returns null for anything else.
         */
        fun parse(name: String): Rounding? = when (name.trim().lowercase()) {
            "floor" -> FLOOR
            "ceil", "ceiling" -> CEIL
            "round" -> ROUND
            "trunc", "truncate" -> TRUNCATE
            else -> null
        }
    }
}

/**
 * Turns a number into text. Locale-independent: the decimal separator is always `.` and there are no
 * grouping separators. Rounding is done on the shortest decimal form of the Float (`7.65f` is treated as
 * 7.65, not 7.6500001), so ties behave the way a player would expect.
 *
 * @property decimals digits after the point, 0 to 9.
 * @property trimTrailingZeros drop trailing zeros, so 7.50 prints as `7.5` and 7.00 as `7`.
 */
data class NumberFormat(
    val decimals: Int = 0,
    val rounding: Rounding = Rounding.ROUND,
    val trimTrailingZeros: Boolean = false,
) {
    init {
        require(decimals in 0..9) { "decimals must be 0..9, was $decimals" }
    }

    /** Formats [value]. NaN and infinities give `NaN`, `Infinity` and `-Infinity`. */
    fun format(value: Float): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0f) "Infinity" else "-Infinity"
        var number = BigDecimal(value.toString()).setScale(decimals, rounding.mode)
        if (trimTrailingZeros) {
            number = number.stripTrailingZeros()
            if (number.scale() < 0) number = number.setScale(0)
        }
        return number.toPlainString()
    }
}
