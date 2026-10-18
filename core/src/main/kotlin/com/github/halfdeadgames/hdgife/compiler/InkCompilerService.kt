package com.github.halfdeadgames.hdgife.compiler

import com.badlogic.gdx.files.FileHandle
import com.bladecoder.ink.compiler.Compiler
import com.bladecoder.ink.compiler.IFileHandler
import com.bladecoder.ink.runtime.Error.ErrorType
import com.bladecoder.ink.runtime.HdgifeStory
import com.bladecoder.ink.runtime.InkNativePatch
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.HdgifeLog
import java.io.FileNotFoundException

/**
 * Compiles ink into a [Story]. It compiles what you give it, in the order you give it.
 * Choosing the sources, their order and the thread is the game's job. To run a different set of sources,
 * compile again and build a new `StoryManager` from the new story.
 *
 * The stories it returns are [HdgifeStory]s: plain Blade Ink stories with the lookahead-safe external function
 * fix. Everything else about them is the normal [Story] API.
 *
 * Every call is plain blocking. Run it on whatever thread or coroutine context you like, but don't use the
 * [Story] from two threads at once.
 *
 * How sources are combined: the first source is the root. Each later source ("extra") is appended to the
 * root as an `INCLUDE`, in list order. Ink cannot override a knot defined earlier, a duplicate is an error.
 *
 * `INCLUDE x.ink` inside a source is resolved relative to that source's own name, with `..` allowed.
 * It finds a listed source of that name first, then asks [InkSource.sibling]. A source is only ever
 * loaded once per compile, so listing a file that another source also includes is fine.
 * `INCLUDE hdgife.ink` gives the bundled helper library from the classpath. It is never added automatically.
 *
 * Errors are [InkCompileException]s. Ink's messages name the source and line.
 */
class InkCompilerService {
    /** Gets ink warnings and notes. Null means they are dropped. */
    var log: HdgifeLog? = null

    init {
        // FLOOR, CEILING and INT crash on floats in Blade Ink 1.3.2, see InkNativePatch
        InkNativePatch.apply()
    }

    /** Compiles [sources]. The first is the root and the others are included after it, in order. */
    fun compile(sources: List<InkSource>): Story {
        require(sources.isNotEmpty()) { "No ink sources given" }
        val byName = LinkedHashMap<String, InkSource>()
        for (source in sources) {
            require(source.name != BUILTIN_NAME) { "'$BUILTIN_NAME' is reserved for the bundled library" }
            require(byName.put(source.name, source) == null) { "Duplicate ink source name '${source.name}'" }
        }
        val handler = SourceResolver(byName)
        val root = sources[0]
        val story = try {
            val text = StringBuilder(handler.prepare(root, root.read()))
            text.append('\n')
            for (i in 1 until sources.size) text.append("INCLUDE ").append(sources[i].name).append('\n')
            run(root.name, text.toString(), handler)
        } catch (e: InkCompileException) {
            throw InkCompileException(e.errors + handler.failures, e.source, e.cause)
        } catch (e: SourceReadException) {
            throw InkCompileException(listOf(e.message ?: "Cannot read ink source"), e.sourceName, e.cause)
        }
        return finish(story)
    }

    /** Compiles [main] as the root, then [extras] in the order given. */
    fun compile(main: InkSource, extras: List<InkSource> = emptyList()): Story = compile(listOf(main) + extras)

    /** Same with files, read as UTF-8. Includes not in the list are looked up next to the including file. */
    fun compile(main: FileHandle, extras: List<FileHandle> = emptyList()): Story =
        compile(listOf(main).plus(extras).map { InkSource.of(it) })

    /**
     * Compiles the `.ink` files of [folder]. Paths are compared as text, case-sensitive, with `/` as separator.
     * The root is `main.ink` in the folder if there is one, else the first file in that order.
     * The rest follow in that order. Control the order with file names (`000_base.ink`, `010_extra.ink`)
     * or use [compile] with your own list. [recursive] also takes files in subfolders. Throws if there are none.
     */
    fun compileFolder(folder: FileHandle, recursive: Boolean = true): Story {
        val files = ArrayList<FileHandle>()
        collectInk(folder, recursive, files)
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

    /** Loads a story from compiled JSON, as made by `story.toJson()`. No compiler work is done. */
    fun loadCompiled(json: String): Story {
        val story = try {
            HdgifeStory(json)
        } catch (e: Exception) {
            throw InkCompileException(listOf("Cannot load compiled story: ${e.message}"), null, e)
        }
        return finish(story)
    }

    /** Rebuilds [story] as an [HdgifeStory] (fresh state) with ink fallbacks for EXTERNAL functions allowed. */
    private fun finish(story: Story): Story = HdgifeStory.from(story).also { it.setAllowExternalFunctionFallbacks(true) }

    private fun run(rootName: String, text: String, handler: IFileHandler): Story {
        val errors = ArrayList<String>()
        val options = Compiler.Options().apply {
            sourceFilename = rootName
            fileHandler = handler
            errorHandler = com.bladecoder.ink.runtime.Error.ErrorHandler { message, type ->
                if (type == ErrorType.Error) errors += message else log?.log(message)
            }
        }
        val story = Compiler(text, options).compile()
        if (errors.isNotEmpty() || story == null) {
            if (errors.isEmpty()) errors += "Compiler returned no story"
            throw InkCompileException(errors)
        }
        return story
    }

    private fun collectInk(dir: FileHandle, recursive: Boolean, out: MutableList<FileHandle>) {
        for (child in dir.list()) {
            if (child.isDirectory) {
                if (recursive) collectInk(child, true, out)
            } else if (child.extension() == "ink") out += child
        }
    }

    private class SourceReadException(val sourceName: String, cause: Throwable) :
        RuntimeException("Cannot read ink source '$sourceName': ${cause.message}", cause)

    /**
     * Ink hands the file handler only the include text, not who included it. So each source's
     * `INCLUDE` lines are rewritten to full names before ink sees them, and resolved here.
     */
    private class SourceResolver(private val listed: MutableMap<String, InkSource>) : IFileHandler {
        private val loaded = HashSet<String>()

        /** Why includes failed to load. Ink only says "Failed to load", this keeps the reason. */
        val failures = ArrayList<String>()

        fun prepare(source: InkSource, text: String): String {
            loaded += source.name
            val dir = source.name.substringBeforeLast('/', "")
            return INCLUDE_LINE.replace(text) { match ->
                val target = match.groupValues[2].trim()
                if (target == BUILTIN_NAME) return@replace match.value
                val full = normalizePath("$dir/$target")
                if (full !in listed) source.sibling?.invoke(target)?.let { listed[full] = it }
                match.groupValues[1] + full
            }
        }

        private fun read(source: InkSource): String =
            try {
                source.read()
            } catch (e: Exception) {
                failures += "Cannot read ink source '${source.name}': ${e.message}"
                throw SourceReadException(source.name, e)
            }

        override fun resolveInkFilename(includeName: String): String {
            val name = normalizePath(includeName)
            if (name == BUILTIN_NAME) return BUILTIN_NAME
            return name
        }

        override fun loadInkFileContents(fullFilename: String): String {
            if (fullFilename == BUILTIN_NAME) {
                if (!loaded.add(BUILTIN_NAME)) return ""
                val stream = InkCompilerService::class.java.classLoader.getResourceAsStream(BUILTIN_PATH)
                    ?: throw FileNotFoundException("Bundled $BUILTIN_PATH is missing")
                return stream.use { String(it.readBytes(), Charsets.UTF_8) }
            }
            val source = listed[fullFilename]
                ?: throw FileNotFoundException("Unlisted ink source: $fullFilename").also { failures += it.message!! }
            if (!loaded.add(fullFilename)) return ""
            return prepare(source, read(source))
        }
    }

    private companion object {
        const val BUILTIN_NAME = "hdgife.ink"
        const val BUILTIN_PATH = "hdgife/hdgife.ink"
        val INCLUDE_LINE = Regex("(?m)^([ \\t]*INCLUDE[ \\t]+)([^\\r\\n]+)")
    }
}
