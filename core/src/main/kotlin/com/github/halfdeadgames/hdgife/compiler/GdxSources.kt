package com.github.halfdeadgames.hdgife.compiler

import com.badlogic.gdx.files.FileHandle
import com.bladecoder.ink.runtime.HdgifeStory

// libGDX conveniences, kept out of InkSource and InkCompilerService so those mention no gdx types.

/**
 * Source backed by a [FileHandle], read as UTF-8. The name is the file's path.
 * `INCLUDE`s of unlisted files are looked up next to the file, using the same file type.
 */
public fun InkSource.Companion.of(file: FileHandle): InkSource = fileSource(file, normalizePath(file.path()))

private fun fileSource(file: FileHandle, name: String): InkSource =
    InkSource.lazy(name, { relative ->
        val child = file.parent().child(relative)
        if (child.exists() && !child.isDirectory) {
            fileSource(child, normalizePath(name.substringBeforeLast('/', "") + "/" + relative))
        } else null
    }) { file.readString("UTF-8") }

/** Compiles [main] as the root, then [extras], with files read as UTF-8. Includes not in the list are looked up next to the including file. */
public fun InkCompilerService.compile(main: FileHandle, extras: List<FileHandle> = emptyList()): HdgifeStory =
    compile((listOf(main) + extras).map { InkSource.of(it) })

/**
 * Compiles the `.ink` files of [folder]. Paths are compared as text, case-sensitive, with `/` as separator.
 * The root is `main.ink` in the folder if there is one, else the first file in that order.
 * The rest follow in that order. Control the order with file names (`000_base.ink`, `010_extra.ink`)
 * or use `compile` with your own list. [recursive] also takes files in subfolders. Throws if there are none.
 */
public fun InkCompilerService.compileFolder(folder: FileHandle, recursive: Boolean = true): HdgifeStory {
    val files = inkFiles(folder, recursive)
    val root = folder.path().replace('\\', '/').trimEnd('/')
    fun relative(file: FileHandle): String {
        val path = file.path().replace('\\', '/')
        return if (path.startsWith("$root/")) path.substring(root.length + 1) else file.name()
    }
    val sorted = files.sortedBy { relative(it) }
    if (sorted.isEmpty()) throw InkCompileException(listOf("No .ink files in '${folder.path()}'"))
    val main = sorted.firstOrNull { relative(it) == "main.ink" } ?: sorted[0]
    return compile(main, sorted.filter { it !== main })
}

private fun inkFiles(dir: FileHandle, recursive: Boolean): List<FileHandle> =
    dir.list().flatMap { child ->
        when {
            child.isDirectory -> if (recursive) inkFiles(child, true) else emptyList()
            child.extension() == "ink" -> listOf(child)
            else -> emptyList()
        }
    }
