package com.github.halfdeadgames.hdgife.compiler

import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.badlogic.gdx.files.FileHandle
import com.bladecoder.ink.runtime.HdgifeStory

public fun InkSource.Companion.of(file: FileHandle): InkSource = fileSource(file, normalizePath(file.path()))

public fun InkSource.Companion.of(path: String, resolver: FileHandleResolver): InkSource = resolvedSource(resolver, normalizePath(path))

private fun resolvedSource(resolver: FileHandleResolver, path: String): InkSource =
    InkSource.lazy(path, { relative ->
        val siblingPath = siblingPath(path, relative)
        val sibling = resolver.resolve(siblingPath)
        if (sibling.exists() && !sibling.isDirectory) resolvedSource(resolver, siblingPath) else null
    }) { resolver.resolve(path).readString("UTF-8") }

private fun fileSource(file: FileHandle, name: String): InkSource =
    InkSource.lazy(name, { relative ->
        val child = file.parent().child(relative)
        if (child.exists() && !child.isDirectory) {
            fileSource(child, siblingPath(name, relative))
        } else null
    }) { file.readString("UTF-8") }

public fun InkCompilerService.compile(main: FileHandle, extras: List<FileHandle> = emptyList()): HdgifeStory =
    compile((listOf(main) + extras).map { InkSource.of(it) })

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
