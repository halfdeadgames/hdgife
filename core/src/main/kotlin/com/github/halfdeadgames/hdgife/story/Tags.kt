package com.github.halfdeadgames.hdgife.story

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

    /** All values given for [key] in order, from [raw]. */
    fun all(key: String): List<String> = raw.mapNotNull { tag ->
        val (k, v) = splitTag(tag)
        if (k == key) v else null
    }

    companion object {
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
