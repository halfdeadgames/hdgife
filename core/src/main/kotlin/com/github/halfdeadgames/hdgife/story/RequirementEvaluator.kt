package com.github.halfdeadgames.hdgife.story

import com.github.halfdeadgames.hdgife.HdgifeLog

internal class RequirementEvaluator(
    private val statLookup: (entity: String, stat: String) -> Any?,
    private val speaker: String?,
    private val log: HdgifeLog?,
) {
    fun evaluate(tagValues: List<String>): List<RequirementResult> {
        if (tagValues.isEmpty()) return emptyList()
        val results = ArrayList<RequirementResult>()
        for (value in tagValues) {
            for (part in splitClauses(value)) {
                val raw = part.trim()
                if (raw.isEmpty()) continue
                results += evaluateClause(raw)
            }
        }
        return results
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
        val match = requirementPattern.matchEntire(raw)
        val op = match?.let { Comparison.parse(it.groupValues[3]) }
        val targetText = match?.groupValues?.get(4)?.trim()?.let(::unquote)
        if (match == null || op == null || targetText.isNullOrEmpty()) {
            log?.log("Requirement: cannot read '$raw'")
            return RequirementResult.Unreadable(raw)
        }
        val entity = match.groupValues[1].ifEmpty { null }?.let(::unquote) ?: speaker
        val stat = unquote(match.groupValues[2])
        val parsed = parseScalar(targetText)
        val numberTarget: Number? = parsed as? Number
        if (entity == null) {
            log?.log("Requirement: '$raw' names no entity and there is no speaker")
            return RequirementResult.MissingStat(raw, null, stat, op, numberTarget ?: targetText)
        }
        val actual = statLookup(entity, stat)
            ?: return RequirementResult.MissingStat(raw, entity, stat, op, numberTarget ?: targetText)

        fun mismatch(why: String): RequirementResult {
            log?.log("Requirement: '$raw' $why")
            return RequirementResult.Mismatch(raw, entity, stat, op, numberTarget ?: targetText, actual)
        }
        fun outcome(met: Boolean, target: Any): RequirementResult =
            if (met) RequirementResult.Met(raw, entity, stat, op, target, actual)
            else RequirementResult.Unmet(raw, entity, stat, op, target, actual)
        return when (actual) {
            is Int, is Float -> {
                if (numberTarget == null) return mismatch("compares a number to '$targetText'")
                val have = if (actual is Float) actual.toCleanDouble() else (actual as Int).toDouble()
                val want = if (numberTarget is Float) numberTarget.toCleanDouble() else numberTarget.toDouble()
                outcome(op.test(have, want), numberTarget)
            }
            is Boolean -> {
                val want = parsed as? Boolean ?: return mismatch("compares a boolean to '$targetText'")
                if (!op.isEquality) return mismatch("uses ${op.symbol} on a boolean")
                outcome((actual == want) == (op == Comparison.Equal), want)
            }
            else -> {
                if (!op.isEquality) return mismatch("uses ${op.symbol} on a string")
                outcome((actual == targetText) == (op == Comparison.Equal), targetText)
            }
        }
    }
}

private const val namePattern = """\x22[^\x22]+\x22|'[^']+'|[\w-]+"""
private val requirementPattern = Regex("""(?:($namePattern)\.)?($namePattern)\s*(>=|<=|==|!=|>|<)\s*(.+)""")

private const val noChar = '\u0000'

internal fun isReadableRequirement(clause: String): Boolean =
    splitAlternatives(clause).all { isReadableTerm(it.trim()) }

private fun isReadableTerm(term: String): Boolean {
    val rest = negatedRest(term) ?: return isSimpleRequirement(term)
    return isReadableTerm(rest)
}

private fun isSimpleRequirement(term: String): Boolean {
    val match = requirementPattern.matchEntire(term) ?: return false
    return Comparison.parse(match.groupValues[3]) != null && match.groupValues[4].trim().let(::unquote).isNotEmpty()
}

private fun negatedRest(term: String): String? {
    if (term.startsWith('!')) return term.substring(1).trim().ifEmpty { null }
    if (term.length > 4 && term.startsWith("not") && term[3].isWhitespace() && !isSimpleRequirement(term)) {
        return term.substring(4).trim().ifEmpty { null }
    }
    return null
}

internal fun unquote(text: String): String =
    if (text.length >= 2 && (text.first() == '"' || text.first() == '\'') && text.last() == text.first()) text.substring(1, text.length - 1) else text

internal fun splitClauses(value: String): List<String> = splitOutsideQuotes(value) { text, index -> if (text[index] == ',') 1 else 0 }

internal fun splitAlternatives(clause: String): List<String> = splitOutsideQuotes(clause) { text, index ->
    when {
        text[index] == '|' -> 1
        text[index] == 'o' && text.startsWith("or", index) && index > 0 && text[index - 1].isWhitespace() &&
            index + 2 < text.length && text[index + 2].isWhitespace() -> 2
        else -> 0
    }
}

internal fun splitOutsideQuotes(value: String, separator: (String, Int) -> Int): List<String> {
    val parts = ArrayList<String>()
    var start = 0
    var quote = noChar
    var previous = noChar
    var i = 0
    while (i < value.length) {
        val char = value[i]
        var step = 1
        if (quote != noChar) {
            if (char == quote) quote = noChar
        } else if ((char == '"' || char == '\'') && (previous == noChar || previous in "=<>!.,|")) {
            quote = char
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
        if (!char.isWhitespace()) previous = char
        i += step
    }
    parts += value.substring(start)
    return parts
}
