package com.github.halfdeadgames.hdgife.compiler

public class InkSource private constructor(
    public val name: String,
    private val loader: () -> String,
    internal val sibling: ((relativePath: String) -> InkSource?)?,
) {
    public constructor(name: String, text: String) : this(name, { text }, null)

    public fun read(): String = loader()

    override fun toString(): String = "InkSource($name)"

    public companion object {
        @JvmStatic
        @JvmOverloads
        public fun lazy(name: String, sibling: ((relativePath: String) -> InkSource?)? = null, text: () -> String): InkSource =
            InkSource(name, text, sibling)
    }
}

internal fun normalizePath(path: String): String {
    val unified = path.replace('\\', '/')
    val parts = ArrayList<String>()
    for (part in unified.split('/')) {
        when {
            part.isEmpty() || part == "." -> {}
            part == ".." && parts.isNotEmpty() && parts.last() != ".." -> parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    val joined = parts.joinToString("/")
    return if (unified.startsWith("/")) "/$joined" else joined
}

internal fun siblingPath(path: String, relative: String): String {
    val slash = path.lastIndexOf('/')
    val directory = if (slash < 0) "" else path.substring(0, slash + 1)
    return normalizePath(directory + relative)
}
