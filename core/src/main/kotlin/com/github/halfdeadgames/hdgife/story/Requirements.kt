package com.github.halfdeadgames.hdgife.story

public enum class Comparison(public val symbol: String) {
    Greater(">"), GreaterEqual(">="), Less("<"), LessEqual("<="), Equal("=="), NotEqual("!=");

    public val isEquality: Boolean get() = this == Equal || this == NotEqual

    internal fun test(actual: Double, target: Double): Boolean = when (this) {
        Greater -> actual > target
        GreaterEqual -> actual >= target
        Less -> actual < target
        LessEqual -> actual <= target
        Equal -> actual == target
        NotEqual -> actual != target
    }

    public companion object {
        @JvmStatic
        public fun parse(symbol: String): Comparison? = entries.firstOrNull { it.symbol == symbol }
    }
}

public sealed interface RequirementResult {
    public val raw: String

    public val isMet: Boolean

    public class Met internal constructor(
        override val raw: String,
        public val entity: String,
        public val stat: String,
        public val op: Comparison,
        public val target: Any,
        public val actual: Any,
    ) : RequirementResult {
        override val isMet: Boolean get() = true

        override fun equals(other: Any?): Boolean = this === other || other is Met && raw == other.raw &&
            entity == other.entity && stat == other.stat && op == other.op && target == other.target && actual == other.actual

        override fun hashCode(): Int = listOf(raw, entity, stat, op, target, actual).hashCode()

        override fun toString(): String = "Met(raw=$raw, entity=$entity, stat=$stat, op=$op, target=$target, actual=$actual)"
    }

    public class Unmet internal constructor(
        override val raw: String,
        public val entity: String,
        public val stat: String,
        public val op: Comparison,
        public val target: Any,
        public val actual: Any,
    ) : RequirementResult {
        override val isMet: Boolean get() = false

        override fun equals(other: Any?): Boolean = this === other || other is Unmet && raw == other.raw &&
            entity == other.entity && stat == other.stat && op == other.op && target == other.target && actual == other.actual

        override fun hashCode(): Int = listOf(raw, entity, stat, op, target, actual).hashCode()

        override fun toString(): String = "Unmet(raw=$raw, entity=$entity, stat=$stat, op=$op, target=$target, actual=$actual)"
    }

    public class Mismatch internal constructor(
        override val raw: String,
        public val entity: String,
        public val stat: String,
        public val op: Comparison,
        public val target: Any,
        public val actual: Any,
    ) : RequirementResult {
        override val isMet: Boolean get() = false

        override fun equals(other: Any?): Boolean = this === other || other is Mismatch && raw == other.raw &&
            entity == other.entity && stat == other.stat && op == other.op && target == other.target && actual == other.actual

        override fun hashCode(): Int = listOf(raw, entity, stat, op, target, actual).hashCode()

        override fun toString(): String = "Mismatch(raw=$raw, entity=$entity, stat=$stat, op=$op, target=$target, actual=$actual)"
    }

    public class MissingStat internal constructor(
        override val raw: String,
        public val entity: String?,
        public val stat: String,
        public val op: Comparison,
        public val target: Any,
    ) : RequirementResult {
        override val isMet: Boolean get() = false

        override fun equals(other: Any?): Boolean = this === other || other is MissingStat && raw == other.raw &&
            entity == other.entity && stat == other.stat && op == other.op && target == other.target

        override fun hashCode(): Int = listOf(raw, entity, stat, op, target).hashCode()

        override fun toString(): String = "MissingStat(raw=$raw, entity=$entity, stat=$stat, op=$op, target=$target)"
    }

    public class Unreadable internal constructor(override val raw: String) : RequirementResult {
        override val isMet: Boolean get() = false

        override fun equals(other: Any?): Boolean = this === other || other is Unreadable && raw == other.raw

        override fun hashCode(): Int = raw.hashCode()

        override fun toString(): String = "Unreadable(raw=$raw)"
    }

    public class AnyOf internal constructor(override val raw: String, public val results: List<RequirementResult>) : RequirementResult {
        override val isMet: Boolean get() = results.any { it.isMet }

        override fun equals(other: Any?): Boolean = this === other || other is AnyOf && raw == other.raw && results == other.results

        override fun hashCode(): Int = listOf(raw, results).hashCode()

        override fun toString(): String = "AnyOf(raw=$raw, results=$results)"
    }

    public class Not internal constructor(override val raw: String, public val operand: RequirementResult) : RequirementResult {
        override val isMet: Boolean get() = !operand.isMet && operand.isDecided()

        override fun equals(other: Any?): Boolean = this === other || other is Not && raw == other.raw && operand == other.operand

        override fun hashCode(): Int = listOf(raw, operand).hashCode()

        override fun toString(): String = "Not(raw=$raw, operand=$operand)"
    }
}

internal fun RequirementResult.isDecided(): Boolean = when (this) {
    is RequirementResult.Met, is RequirementResult.Unmet -> true
    is RequirementResult.AnyOf -> results.any { it.isMet } || results.all { it.isDecided() }
    is RequirementResult.Not -> operand.isDecided()
    else -> false
}
