package com.github.halfdeadgames.hdgife.compiler

import com.badlogic.gdx.files.FileHandle
import com.bladecoder.ink.compiler.IFileHandler
import java.io.FileNotFoundException

/**
 * Resolves ink INCLUDE names through libGDX [FileHandle]s, so internal, local and external files work.
 *
 * Lookup order: an exact key in [known], then relative to [baseDir], then relative to [modDir].
 */
internal class GdxInkFileHandler(
    private val baseDir: FileHandle?,
    private val modDir: FileHandle?,
    private val known: Map<String, FileHandle>,
) : IFileHandler {
    private val resolved = HashMap<String, FileHandle>()

    override fun resolveInkFilename(includeName: String): String {
        val name = includeName.replace('\\', '/')
        known[name]?.let { resolved[name] = it; return name }
        baseDir?.child(name)?.takeIf { it.exists() && !it.isDirectory }?.let {
            val key = "base/$name"
            resolved[key] = it
            return key
        }
        modDir?.child(name)?.takeIf { it.exists() && !it.isDirectory }?.let {
            val key = "mod/$name"
            resolved[key] = it
            return key
        }
        throw FileNotFoundException("Cannot resolve ink include: $includeName")
    }

    override fun loadInkFileContents(fullFilename: String): String {
        val file = resolved[fullFilename] ?: throw FileNotFoundException("Unresolved ink file: $fullFilename")
        return file.readString("UTF-8")
    }
}
