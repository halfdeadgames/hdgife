package com.github.halfdeadgames.hdgife.story

import com.github.halfdeadgames.hdgife.HdgifeLog

/**
 * Evaluates the `requires` tags of a choice. [statLookup] gives the stat as an ink value (null if missing), [speaker] is
 * the entity used when a requirement names none. Problems are reported to [log], never thrown.
 */
internal class RequirementEvaluator(
    private val statLookup: (entity: String, stat: String) -> Any?,
    private val speaker: String?,
    private val log: HdgifeLog?,
) {
    /**
     * Checks the values of the requirement tags (each may hold several comma-separated clauses). Each term is
     * `[entity.]stat op value`; a clause is one term or several joined with `|` or `or`, and a term can start with
     * `!` or `not `. Without an entity the [speaker] is used.
     */
    fun evaluate(tagValues: List<String>): List<RequirementResult> {
        if (tagValues.isEmpty()) return emptyList()
        val result = ArrayList<RequirementResult>()
        for (value in tagValues) {
            for (part in splitClauses(value)) {
                val raw = part.trim()
                if (raw.isEmpty()) continue
                result += evaluateClause(raw)
            }
        }
        return result
    }

    private fun evaluateClause(raw: String): RequirementResult {
        val alternatives = splitAlternatives(raw)
        if (alternatives.size == 1) return evaluateTerm(raw)
        return RequirementResult.AnyOf(raw, alternatives.map { evaluateTerm(it.trim()) })
    }

    private fun evaluateTerm(raw: String): RequirementResult {
        val rest = negatedRest(raw) ?: return evaluateRequirement(raw)
        return RequirementResult.Not(raw, evaluateTerm(rest))
    }

    private fun evaluateRequirement(raw: String): RequirementResult {
        val match = REQUIREMENT.matchEntire(raw)
        val op = match?.let { Comparison.parse(it.groupValues[3]) }
        val text = match?.groupValues?.get(4)?.trim()?.let(::unquote)
        if (match == null || op == null || text.isNullOrEmpty()) {
            log?.log("Requirement: cannot read '$raw'")
            return RequirementResult.Unreadable(raw)
        }
        val entity = match.groupValues[1].ifEmpty { null }?.let(::unquote) ?: speaker
        val stat = unquote(match.groupValues[2])
        val parsed = parseScalar(text)
        val numberTarget: Number? = parsed as? Number
        if (entity == null) {
            log?.log("Requirement: '$raw' names no entity and there is no speaker")
            return RequirementResult.MissingStat(raw, null, stat, op, numberTarget ?: text)
        }
        val actual = statLookup(entity, stat)
            ?: return RequirementResult.MissingStat(raw, entity, stat, op, numberTarget ?: text)

        fun mismatch(why: String): RequirementResult {
            log?.log("Requirement: '$raw' $why")
            return RequirementResult.Mismatch(raw, entity, stat, op, numberTarget ?: text, actual)
        }
        fun outcome(met: Boolean, target: Any): RequirementResult =
            if (met) RequirementResult.Met(raw, entity, stat, op, target, actual)
            else RequirementResult.Unmet(raw, entity, stat, op, target, actual)
        return when (actual) {
            is Int, is Float -> {
                if (numberTarget == null) return mismatch("compares a number to '$text'")
                val have = if (actual is Float) actual.toCleanDouble() else (actual as Int).toDouble()
                val want = if (numberTarget is Float) numberTarget.toCleanDouble() else numberTarget.toDouble()
                outcome(op.test(have, want), numberTarget)
            }
            is Boolean -> {
                val want = parsed as? Boolean ?: return mismatch("compares a boolean to '$text'")
                if (!op.isEquality) return mismatch("uses ${op.symbol} on a boolean")
                outcome((actual == want) == (op == Comparison.EQUAL), want)
            }
            else -> {
                if (!op.isEquality) return mismatch("uses ${op.symbol} on a string")
                outcome((actual == text) == (op == Comparison.EQUAL), text)
            }
        }
    }
}

// A name is a plain word (letters, digits, _ and -) or quoted ('...' or "..."), so it can hold spaces, dots and commas.
private const val NAME = """\x22[^\x22]+\x22|'[^']+'|[\w-]+"""
private val REQUIREMENT = Regex("""(?:($NAME)\.)?($NAME)\s*(>=|<=|==|!=|>|<)\s*(.+)""")

private const val NO_CHAR = '\u0000'

/**
 * True when [clause] (one comma-separated part of a requirement tag) can be read: terms of the shape
 * `[entity.]stat op value`, joined with `|`, each optionally negated with `!` or `not `.
 */
internal fun isReadableRequirement(clause: String): Boolean =
    splitAlternatives(clause).all { isReadableTerm(it.trim()) }

private fun isReadableTerm(term: String): Boolean {
    val rest = negatedRest(term) ?: return isSimpleRequirement(term)
    return isReadableTerm(rest)
}

private fun isSimpleRequirement(term: String): Boolean {
    val match = REQUIREMENT.matchEntire(term) ?: return false
    return Comparison.parse(match.groupValues[3]) != null && match.groupValues[4].trim().let(::unquote).isNotEmpty()
}

/**
 * The term without its negation, or null when [term] is not negated. `!x` and `not x` negate; `not` alone as a
 * stat name (`not >= 3`, a readable term by itself) does not. Nothing after the mark is not a negation either.
 */
private fun negatedRest(term: String): String? {
    if (term.startsWith('!')) return term.substring(1).trim().ifEmpty { null }
    if (term.length > 4 && term.startsWith("not") && term[3].isWhitespace() && !isSimpleRequirement(term)) {
        return term.substring(4).trim().ifEmpty { null }
    }
    return null
}

internal fun unquote(text: String): String =
    if (text.length >= 2 && (text.first() == '"' || text.first() == '\'') && text.last() == text.first()) text.substring(1, text.length - 1) else text

/** Splits a requirement tag on commas that are outside quotes, see [splitOutsideQuotes]. */
internal fun splitClauses(value: String): List<String> = splitOutsideQuotes(value) { v, i -> if (v[i] == ',') 1 else 0 }

/**
 * Splits a clause into alternatives at a `|` or at the word `or` (with whitespace on both sides), outside quotes.
 * Ink reads a bare `|` inside a choice's `[ ]` as its own separator, so there write `\|` or use `or`.
 */
internal fun splitAlternatives(clause: String): List<String> = splitOutsideQuotes(clause) { v, i ->
    when {
        v[i] == '|' -> 1
        v[i] == 'o' && v.startsWith("or", i) && i > 0 && v[i - 1].isWhitespace() &&
            i + 2 < v.length && v[i + 2].isWhitespace() -> 2
        else -> 0
    }
}

/**
 * Splits [value] where [separator] says a separator starts (it returns its length at index i, or 0), outside
 * quotes. A quote opens at the start or after one of `= < > ! . , |` or a separator (so an apostrophe inside a
 * word, `O'Brien`, is not a quote) and ends at the same quote character. An unterminated quote runs to the end.
 */
internal fun splitOutsideQuotes(value: String, separator: (String, Int) -> Int): List<String> {
    val parts = ArrayList<String>()
    var start = 0
    var quote = NO_CHAR
    var previous = NO_CHAR
    var i = 0
    while (i < value.length) {
        val c = value[i]
        var step = 1
        if (quote != NO_CHAR) {
            if (c == quote) quote = NO_CHAR
        } else if ((c == '"' || c == '\'') && (previous == NO_CHAR || previous in "=<>!.,|")) {
            quote = c
        } else {
            val length = separator(value, i)
            if (length > 0) {
                parts += value.substring(start, i)
                start = i + length
                step = length
                previous = ','
                i += step
                continue
            }
        }
        if (!c.isWhitespace()) previous = c
        i += step
    }
    parts += value.substring(start)
    return parts
}
