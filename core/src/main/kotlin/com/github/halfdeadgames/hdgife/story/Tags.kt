package com.github.halfdeadgames.hdgife.story

import java.util.Locale

/**
 * Ink tags, raw and parsed. [raw] is every tag as ink gave it, in order, trimmed.
 *
 * [map] holds the parsed form. `key: value` becomes key to value, split at the first colon, both trimmed.
 * A bare tag such as `shout` becomes key `shout` with an empty value. A tag whose part before the first colon
 * isn't a plain word (letters, digits, `_`, `-`, `.`, no spaces) is bare as a whole, so prose like
 * `Note to self: x` stays one key. If a key repeats, the last one wins in [map]. Read [raw] for all of them.
 * Keys are case-sensitive.
 */
data class Tags(val raw: List<String>, val map: Map<String, String>) {
    /** Value for [key], or null if there is no such tag. A bare tag gives an empty string. */
    operator fun get(key: String): String? = map[key]

    /** True if a tag with this key is present. */
    fun has(key: String): Boolean = map.containsKey(key)

    val isEmpty: Boolean get() = raw.isEmpty()

    /** The value for [key] as written (a bare tag gives an empty string), or null if there is no such tag. Same as [get]. */
    fun string(key: String): String? = map[key]

    fun string(key: String, default: String): String = map[key] ?: default

    /**
     * The value for [key] as an `Int`: optional sign and ASCII digits, nothing else. Null when the tag is missing,
     * empty or not a whole number that fits. Not affected by the default locale.
     */
    fun int(key: String): Int? = map[key]?.takeIf { INT.matches(it) }?.toIntOrNull()

    fun int(key: String, default: Int): Int = int(key) ?: default

    /**
     * The value for [key] as a `Float`: optional sign, ASCII digits with `.` as the decimal point, optional exponent
     * (`4`, `-0.5`, `.25`, `1e3`). Null when missing or anything else, including `1,5`, `NaN`, `Infinity`, `1f` and
     * a number too large for a `Float`. Not affected by the default locale.
     */
    fun float(key: String): Float? = map[key]?.takeIf { FLOAT.matches(it) }?.toFloatOrNull()?.takeIf { it.isFinite() }

    fun float(key: String, default: Float): Float = float(key) ?: default

    /**
     * The value for [key] as a `Boolean`: `true` or `false` in any case, or `1` / `0`. Null when missing or anything
     * else. A bare tag (`# shout`) has an empty value and gives null here, so ask [has] for flags.
     */
    fun bool(key: String): Boolean? = when (map[key]?.lowercase(Locale.ROOT)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    fun bool(key: String, default: Boolean): Boolean = bool(key) ?: default

    /** All values given for [key] in order, from [raw]. */
    fun all(key: String): List<String> = raw.mapNotNull { tag ->
        val (k, v) = splitTag(tag)
        if (k == key) v else null
    }

    companion object {
        private val INT = Regex("[+-]?[0-9]+")
        private val FLOAT = Regex("""[+-]?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][+-]?[0-9]+)?""")

        val EMPTY = Tags(emptyList(), emptyMap())

        /** Parses [raw] ink tags. Blank tags are dropped. */
        fun parse(raw: List<String>): Tags {
            if (raw.isEmpty()) return EMPTY
            val clean = ArrayList<String>(raw.size)
            val map = LinkedHashMap<String, String>()
            for (tag in raw) {
                val trimmed = tag.trim()
                if (trimmed.isEmpty()) continue
                clean += trimmed
                val (key, value) = splitTag(trimmed)
                map[key] = value
            }
            return if (clean.isEmpty()) EMPTY else Tags(clean, map)
        }

        internal fun splitTag(tag: String): Pair<String, String> {
            val colon = tag.indexOf(':')
            if (colon > 0) {
                val key = tag.substring(0, colon).trim()
                if (key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
                    return key to tag.substring(colon + 1).trim()
                }
            }
            return tag to ""
        }
    }
}
