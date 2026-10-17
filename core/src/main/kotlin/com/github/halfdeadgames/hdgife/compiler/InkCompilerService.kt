package com.github.halfdeadgames.hdgife.compiler

import com.badlogic.gdx.files.FileHandle
import com.bladecoder.ink.compiler.Compiler
import com.bladecoder.ink.runtime.Error.ErrorType
import com.bladecoder.ink.runtime.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ktx.log.logger
import kotlin.coroutines.CoroutineContext

private val log = logger<InkCompilerService>()

/**
 * Compiles ink source at runtime into a single [Story].
 *
 * The compile runs on [context]. Pass a ktx-async `newAsyncContext(...)` if you prefer it over
 * the default [Dispatchers.IO]. Story is not thread-safe, so use the result from one thread.
 *
 * Stories can `INCLUDE hdgife.ink` to get the bundled helper library (stat access and small math helpers).
 * It is never added automatically, so stories that don't include it are untouched.
 *
 * Mods add content such as new knots and stitches. Ink cannot override a knot the base already
 * defines, and a duplicate name is a compile error.
 */
class InkCompilerService(private val context: CoroutineContext = Dispatchers.IO) {

    /**
     * Compiles [baseMain], plus an INCLUDE for every `.ink` file found recursively under [modDirectory].
     * Throws [InkCompileException] on any error.
     */
    suspend fun compile(baseMain: FileHandle, modDirectory: FileHandle? = null): Story =
        withContext(context) { compileBlocking(baseMain, modDirectory) }

    private fun compileBlocking(baseMain: FileHandle, modDirectory: FileHandle?): Story {
        val known = HashMap<String, FileHandle>()
        val source = StringBuilder(baseMain.readString("UTF-8"))
        if (modDirectory != null) {
            val root = modDirectory.path().replace('\\', '/').trimEnd('/')
            val files = ArrayList<FileHandle>()
            collectInk(modDirectory, files)
            val entries = files.map { file ->
                val path = file.path().replace('\\', '/')
                val relative = if (path.startsWith("$root/")) path.substring(root.length + 1) else file.name()
                relative to file
            }.sortedBy { it.first }
            source.append('\n')
            for ((relative, file) in entries) {
                val key = MOD_PREFIX + relative
                known[key] = file
                source.append("INCLUDE ").append(key).append('\n')
            }
        }

        val errors = ArrayList<String>()
        val options = Compiler.Options().apply {
            sourceFilename = baseMain.name()
            fileHandler = GdxInkFileHandler(baseMain.parent(), modDirectory, known)
            errorHandler = com.bladecoder.ink.runtime.Error.ErrorHandler { message, type ->
                if (type == ErrorType.Error) errors += message else log.info { message }
            }
        }
        val story = Compiler(source.toString(), options).compile()
        // lets the ink fallback bodies in hdgife.ink run when nothing is bound
        story?.setAllowExternalFunctionFallbacks(true)
        if (errors.isNotEmpty() || story == null) {
            if (errors.isEmpty()) errors += "Compiler returned no story"
            throw InkCompileException(errors)
        }
        return story
    }

    private fun collectInk(dir: FileHandle, out: MutableList<FileHandle>) {
        for (child in dir.list()) {
            if (child.isDirectory) collectInk(child, out)
            else if (child.extension() == "ink") out += child
        }
    }

    private companion object {
        const val MOD_PREFIX = "__mod__/"
    }
}
