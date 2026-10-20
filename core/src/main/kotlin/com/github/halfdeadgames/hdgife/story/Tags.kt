package com.github.halfdeadgames.hdgife.story

public class Tags private constructor(public val raw: List<String>, public val map: Map<String, String>) {
    public operator fun get(key: String): String? = map[key]

    public fun has(key: String): Boolean = map.containsKey(key)

    public val isEmpty: Boolean get() = raw.isEmpty()

    public fun int(key: String): Int? = map[key]?.let(::parseScalar) as? Int

    public fun int(key: String, default: Int): Int = int(key) ?: default

    public fun float(key: String): Float? = (map[key]?.let(::parseScalar) as? Number)?.toFloat()

    public fun float(key: String, default: Float): Float = float(key) ?: default

    public fun bool(key: String): Boolean? {
        val text = map[key] ?: return null
        return when (text) {
            "1" -> true
            "0" -> false
            else -> parseScalar(text) as? Boolean
        }
    }

    public fun bool(key: String, default: Boolean): Boolean = bool(key) ?: default

    public fun all(key: String): List<String> = raw.mapNotNull { tag ->
        val (tagKey, tagValue) = splitTag(tag)
        if (tagKey == key) tagValue else null
    }

    override fun equals(other: Any?): Boolean = this === other || other is Tags && raw == other.raw && map == other.map

    override fun hashCode(): Int = 31 * raw.hashCode() + map.hashCode()

    override fun toString(): String = "Tags(raw=$raw, map=$map)"

    public companion object {
        @JvmField
        public val empty: Tags = Tags(emptyList(), emptyMap())

        @JvmStatic
        public fun parse(raw: List<String>): Tags {
            if (raw.isEmpty()) return empty
            val clean = ArrayList<String>(raw.size)
            val map = LinkedHashMap<String, String>()
            for (tag in raw) {
                val trimmed = tag.trim()
                if (trimmed.isEmpty()) continue
                clean += trimmed
                val (key, value) = splitTag(trimmed)
                map[key] = value
            }
            return if (clean.isEmpty()) empty else Tags(clean, map)
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
