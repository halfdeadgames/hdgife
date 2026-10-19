package com.github.halfdeadgames.hdgife.story

/**
 * Ink tags, raw and parsed. Make one with [Tags.parse]. [raw] is every tag as ink gave it, in order, trimmed.
 *
 * [map] holds the parsed form. `key: value` becomes key to value, split at the first colon, both trimmed.
 * A bare tag such as `shout` becomes key `shout` with an empty value. A tag whose part before the first colon
 * isn't a plain word (letters, digits, `_`, `-`, `.`, no spaces) is bare as a whole, so prose like
 * `Note to self: x` stays one key. If a key repeats, the last one wins in [map]. Read [raw] or [all] for all of them.
 * Keys are case-sensitive.
 *
 * Read a value as text with `tags[key]` (null when missing), or typed with [int], [float] and [bool].
 */
public class Tags private constructor(public val raw: List<String>, public val map: Map<String, String>) {
    /** Value for [key], or null if there is no such tag. A bare tag gives an empty string. */
    public operator fun get(key: String): String? = map[key]

    /** True if a tag with this key is present. */
    public fun has(key: String): Boolean = map.containsKey(key)

    public val isEmpty: Boolean get() = raw.isEmpty()

    /**
     * The value for [key] as an `Int`: optional sign and ASCII digits, nothing else. Null when the tag is missing,
     * empty or not a whole number that fits. Not affected by the default locale.
     */
    public fun int(key: String): Int? = map[key]?.let(::parseScalar) as? Int

    public fun int(key: String, default: Int): Int = int(key) ?: default

    /**
     * The value for [key] as a `Float`: optional sign, ASCII digits with `.` as the decimal point, optional exponent
     * (`4`, `-0.5`, `.25`, `1e3`). Null when missing or anything else, including `1,5`, `NaN`, `Infinity`, `1f` and
     * a number too large for a `Float`. Not affected by the default locale.
     */
    public fun float(key: String): Float? = (map[key]?.let(::parseScalar) as? Number)?.toFloat()

    public fun float(key: String, default: Float): Float = float(key) ?: default

    /**
     * The value for [key] as a `Boolean`: `true` or `false` in any case, or `1` / `0`. Null when missing or anything
     * else. A bare tag (`# shout`) has an empty value and gives null here, so ask [has] for flags.
     */
    public fun bool(key: String): Boolean? {
        val text = map[key] ?: return null
        return when (text) {
            "1" -> true
            "0" -> false
            else -> parseScalar(text) as? Boolean
        }
    }

    public fun bool(key: String, default: Boolean): Boolean = bool(key) ?: default

    /** All values given for [key] in order, from [raw]. */
    public fun all(key: String): List<String> = raw.mapNotNull { tag ->
        val (k, v) = splitTag(tag)
        if (k == key) v else null
    }

    override fun equals(other: Any?): Boolean = this === other || other is Tags && raw == other.raw && map == other.map

    override fun hashCode(): Int = 31 * raw.hashCode() + map.hashCode()

    override fun toString(): String = "Tags(raw=$raw, map=$map)"

    public companion object {
        /** No tags. */
        @JvmField
        public val EMPTY: Tags = Tags(emptyList(), emptyMap())

        /** Parses [raw] ink tags. Blank tags are dropped. */
        @JvmStatic
        public fun parse(raw: List<String>): Tags {
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
