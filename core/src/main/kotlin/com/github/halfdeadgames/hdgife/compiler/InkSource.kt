package com.github.halfdeadgames.hdgife.compiler

/**
 * One piece of ink source. [name] identifies it in errors and is what other sources `INCLUDE`.
 * Use `/` as the separator and keep names unique within one compile. `hdgife.ink` and `hdgife_util.ink` are reserved.
 *
 * `InkSource(name, text)` holds the text. [lazy] reads it when the compiler needs it, so a loader can read a file, a
 * network response or a packed archive. Sources backed by a libGDX `FileHandle` are made by `InkSource.of(file)`
 * (see GdxSources.kt).
 */
public class InkSource private constructor(
    public val name: String,
    private val loader: () -> String,
    /** Finds other sources next to this one by relative path, for `INCLUDE`s of files that aren't in the compile list. */
    internal val sibling: ((relativePath: String) -> InkSource?)?,
) {
    /** A source with the given [text]. */
    public constructor(name: String, text: String) : this(name, { text }, null)

    /** Reads the text. Called at most once per compile. */
    public fun read(): String = loader()

    override fun toString(): String = "InkSource($name)"

    public companion object {
        /**
         * A source whose text comes from [text], called when the compiler needs it. [sibling] finds other sources next
         * to this one by relative path, for `INCLUDE`s of files that aren't in the compile list. Leave it null if
         * everything is listed.
         */
        @JvmStatic
        @JvmOverloads
        public fun lazy(name: String, sibling: ((relativePath: String) -> InkSource?)? = null, text: () -> String): InkSource =
            InkSource(name, text, sibling)
    }
}

/** Makes `\` into `/`, resolves `.` and `..` and drops empty parts. */
internal fun normalizePath(path: String): String {
    val parts = ArrayList<String>()
    for (part in path.replace('\\', '/').split('/')) {
        when {
            part.isEmpty() || part == "." -> {}
            part == ".." && parts.isNotEmpty() && parts.last() != ".." -> parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return parts.joinToString("/")
}
