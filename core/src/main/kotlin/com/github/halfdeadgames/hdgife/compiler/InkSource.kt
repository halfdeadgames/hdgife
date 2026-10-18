package com.github.halfdeadgames.hdgife.compiler

import com.badlogic.gdx.files.FileHandle

/**
 * One piece of ink source. [name] identifies it in errors and is what other sources `INCLUDE`.
 * Use `/` as the separator and keep names unique within one compile. `hdgife.ink` is reserved.
 *
 * The text is read when the compiler needs it, so a lazy loader can read a file, a network
 * response or a packed archive. [sibling] finds other sources next to this one by relative path,
 * for `INCLUDE`s of files that aren't in the compile list. Leave it null if everything is listed.
 */
class InkSource(
    val name: String,
    private val loader: () -> String,
    internal val sibling: ((relativePath: String) -> InkSource?)? = null,
) {
    constructor(name: String, text: String) : this(name, { text })

    /** Reads the text. Called at most once per compile. */
    fun read(): String = loader()

    override fun toString() = "InkSource($name)"

    companion object {
        /**
         * Source backed by a [FileHandle], read as UTF-8. The name is the file's path.
         * `INCLUDE`s of unlisted files are looked up next to the file, using the same file type.
         */
        fun of(file: FileHandle): InkSource = fromFile(file, normalizePath(file.path()))

        private fun fromFile(file: FileHandle, name: String): InkSource =
            InkSource(name, { file.readString("UTF-8") }) { relative ->
                val child = file.parent().child(relative)
                if (child.exists() && !child.isDirectory) {
                    fromFile(child, normalizePath(name.substringBeforeLast('/', "") + "/" + relative))
                } else null
            }
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
