package com.github.halfdeadgames.hdgife.story

import java.math.BigDecimal
import java.math.RoundingMode

public enum class Rounding(internal val mode: RoundingMode) {
    Floor(RoundingMode.FLOOR),
    Ceil(RoundingMode.CEILING),
    Round(RoundingMode.HALF_UP),
    Truncate(RoundingMode.DOWN);

    public companion object {
        @JvmStatic
        public fun parse(name: String): Rounding? = when (name.trim().lowercase()) {
            "floor" -> Floor
            "ceil", "ceiling" -> Ceil
            "round" -> Round
            "trunc", "truncate" -> Truncate
            else -> null
        }
    }
}

public class NumberFormat @JvmOverloads public constructor(
    public val decimals: Int = 0,
    public val rounding: Rounding = Rounding.Round,
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

    public fun format(value: Float): String = formatDecimal(value.toString(), value.isNaN(), value.isInfinite(), value > 0f)

    public fun format(value: Double): String = formatDecimal(value.toString(), value.isNaN(), value.isInfinite(), value > 0.0)

    public fun format(value: Long): String = formatDecimal(value.toString(), false, false, true)

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
