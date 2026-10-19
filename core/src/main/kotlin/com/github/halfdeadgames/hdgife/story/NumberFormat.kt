package com.github.halfdeadgames.hdgife.story

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How a number is rounded to [NumberFormat.decimals] places. More modes may be added in a later version, so a `when`
 * over it should have an `else`.
 */
public enum class Rounding(internal val mode: RoundingMode) {
    /** Toward negative infinity: 7.65 -> 7, -2.5 -> -3. */
    FLOOR(RoundingMode.FLOOR),

    /** Toward positive infinity: 7.01 -> 8, -2.5 -> -2. */
    CEIL(RoundingMode.CEILING),

    /** Nearest, ties away from zero (half-up): 2.5 -> 3, -2.5 -> -3. */
    ROUND(RoundingMode.HALF_UP),

    /** Toward zero, dropping the rest: 7.9 -> 7, -2.9 -> -2. */
    TRUNCATE(RoundingMode.DOWN);

    public companion object {
        /**
         * Reads a mode name from ink: `floor`, `ceil` (or `ceiling`), `round`, `trunc` (or `truncate`),
         * ignoring case. Returns null for anything else.
         */
        @JvmStatic
        public fun parse(name: String): Rounding? = when (name.trim().lowercase()) {
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
 * Immutable. Change one setting with [withDecimals], [withRounding] or [withTrimTrailingZeros], which give a new instance.
 *
 * @property decimals digits after the point, 0 to 9.
 * @property rounding how the number is rounded to [decimals] places.
 * @property trimTrailingZeros drop trailing zeros, so 7.50 prints as `7.5` and 7.00 as `7`.
 */
public class NumberFormat @JvmOverloads public constructor(
    public val decimals: Int = 0,
    public val rounding: Rounding = Rounding.ROUND,
    public val trimTrailingZeros: Boolean = false,
) {
    init {
        require(decimals in 0..9) { "decimals must be 0..9, was $decimals" }
    }

    public fun withDecimals(decimals: Int): NumberFormat = NumberFormat(decimals, rounding, trimTrailingZeros)

    public fun withRounding(rounding: Rounding): NumberFormat = NumberFormat(decimals, rounding, trimTrailingZeros)

    public fun withTrimTrailingZeros(trimTrailingZeros: Boolean): NumberFormat = NumberFormat(decimals, rounding, trimTrailingZeros)

    override fun equals(other: Any?): Boolean = this === other || other is NumberFormat &&
        decimals == other.decimals && rounding == other.rounding && trimTrailingZeros == other.trimTrailingZeros

    override fun hashCode(): Int = listOf(decimals, rounding, trimTrailingZeros).hashCode()

    override fun toString(): String = "NumberFormat(decimals=$decimals, rounding=$rounding, trimTrailingZeros=$trimTrailingZeros)"

    /** Formats [value]. NaN and infinities give `NaN`, `Infinity` and `-Infinity`. */
    public fun format(value: Float): String = formatDecimal(value.toString(), value.isNaN(), value.isInfinite(), value > 0f)

    public fun format(value: Double): String = formatDecimal(value.toString(), value.isNaN(), value.isInfinite(), value > 0.0)

    public fun format(value: Long): String = formatDecimal(value.toString(), false, false, true)

    /** Formats any [Number]: Int, Short, Byte and Long exactly, the rest through their Double or Float form. */
    public fun format(value: Number): String = when (value) {
        is Float -> format(value)
        is Int, is Short, is Byte, is Long -> format(value.toLong())
        else -> format(value.toDouble())
    }

    private fun formatDecimal(text: String, nan: Boolean, infinite: Boolean, positive: Boolean): String {
        if (nan) return "NaN"
        if (infinite) return if (positive) "Infinity" else "-Infinity"
        var number = BigDecimal(text).setScale(decimals, rounding.mode)
        if (trimTrailingZeros) {
            number = number.stripTrailingZeros()
            if (number.scale() < 0) number = number.setScale(0)
        }
        return number.toPlainString()
    }
}
