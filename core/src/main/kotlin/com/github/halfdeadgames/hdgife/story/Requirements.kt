package com.github.halfdeadgames.hdgife.story

/**
 * Comparison used by a requirement such as `strength >= 12`. Stable API. It may gain entries in a later version, so a
 * `when` over it should have an `else`.
 */
public enum class Comparison(public val symbol: String) {
    GREATER(">"), GREATER_EQUAL(">="), LESS("<"), LESS_EQUAL("<="), EQUAL("=="), NOT_EQUAL("!=");

    /** True for `==` and `!=`, the only ones that work on strings and booleans. */
    public val isEquality: Boolean get() = this == EQUAL || this == NOT_EQUAL

    internal fun test(actual: Double, target: Double): Boolean = when (this) {
        GREATER -> actual > target
        GREATER_EQUAL -> actual >= target
        LESS -> actual < target
        LESS_EQUAL -> actual <= target
        EQUAL -> actual == target
        NOT_EQUAL -> actual != target
    }

    public companion object {
        /** The comparison written as [symbol] (`>=`), or null if it is none of them. */
        @JvmStatic
        public fun parse(symbol: String): Comparison? = entries.firstOrNull { it.symbol == symbol }
    }
}

/**
 * One evaluated requirement of a choice, from a `requires` tag. Every result has the [raw] text as written and says
 * whether it [isMet]. What else it carries depends on the case, so the type tells you why a requirement was not met:
 *
 * - [Met] and [Unmet]: the stat was found and the comparison was made.
 * - [Mismatch]: the stat was found but the written value or the operator does not fit its type
 *   (`strength >= high`, `faction > 3`, `is_injured == 4`). That is logged and never met.
 * - [MissingStat]: the stat does not exist, or there is no entity to look it up on. Never met.
 * - [Unreadable]: the text is not a requirement at all. Never met.
 * - [AnyOf]: alternatives joined with `|`, met when any of them is.
 * - [Not]: a requirement negated with `!` (or `not `), met when the inner one was checked and is not met.
 *
 * Every node, also inside [AnyOf] and [Not], has [raw] and [isMet].
 *
 * Values are ink values: [actual] is an `Int`, `Float`, `Boolean` or `String`, [target] the value written in the
 * requirement read as the type of [actual] (a number is an `Int` or `Float`). More cases may be added in a later
 * version, so a `when` over it should have an `else`.
 */
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

    /**
     * The stat was found, but the value or operator written does not fit its type. [target] is the value as written:
     * a number if the text is one, else the text.
     */
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

    /**
     * The state provider has no such stat ([actual] would be null), or the requirement names no entity and there is no
     * speaker, in which case [entity] is null. [target] is a number if the written text is one, else the text.
     */
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

    /** The text could not be read as `[entity.]stat op value`. */
    public class Unreadable internal constructor(override val raw: String) : RequirementResult {
        override val isMet: Boolean get() = false

        override fun equals(other: Any?): Boolean = this === other || other is Unreadable && raw == other.raw

        override fun hashCode(): Int = raw.hashCode()

        override fun toString(): String = "Unreadable(raw=$raw)"
    }

    /**
     * Alternatives written with `|` or `or` in one clause (`strength >= 12 or charm >= 8`). Met when any of [results] is met.
     * [results] are the evaluated alternatives in the order written, each of them any kind of result except [AnyOf].
     */
    public class AnyOf internal constructor(override val raw: String, public val results: List<RequirementResult>) : RequirementResult {
        override val isMet: Boolean get() = results.any { it.isMet }

        override fun equals(other: Any?): Boolean = this === other || other is AnyOf && raw == other.raw && results == other.results

        override fun hashCode(): Int = listOf(raw, results).hashCode()

        override fun toString(): String = "AnyOf(raw=$raw, results=$results)"
    }

    /**
     * A requirement negated with `!` or `not ` (`!is_injured == true`, `not guard.mood > 30`). Met only when [result] was
     * checked and is not met ([RequirementResult.Unmet]). If [result] is a [MissingStat], a [Mismatch] or an [Unreadable],
     * or an [AnyOf] with one of those and no met alternative, nothing was decided, so this is not met either.
     */
    public class Not internal constructor(override val raw: String, public val result: RequirementResult) : RequirementResult {
        override val isMet: Boolean get() = !result.isMet && result.isDecided()

        override fun equals(other: Any?): Boolean = this === other || other is Not && raw == other.raw && result == other.result

        override fun hashCode(): Int = listOf(raw, result).hashCode()

        override fun toString(): String = "Not(raw=$raw, result=$result)"
    }
}

/** True when the requirement was really checked: met or unmet, not a missing stat, a type mismatch or unreadable text. */
internal fun RequirementResult.isDecided(): Boolean = when (this) {
    is RequirementResult.Met, is RequirementResult.Unmet -> true
    is RequirementResult.AnyOf -> results.any { it.isMet } || results.all { it.isDecided() }
    is RequirementResult.Not -> result.isDecided()
    else -> false
}
