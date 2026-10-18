package com.github.halfdeadgames.hdgife.compiler

import com.bladecoder.ink.compiler.Compiler
import com.bladecoder.ink.compiler.IFileHandler
import com.bladecoder.ink.runtime.Error.ErrorHandler
import com.bladecoder.ink.runtime.Error.ErrorType
import com.bladecoder.ink.runtime.HdgifeStory
import com.bladecoder.ink.runtime.InkNativePatch
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.HdgifeLog
import com.badlogic.gdx.Gdx
import java.io.FileNotFoundException

/**
 * Compiles ink into an [HdgifeStory]. It compiles what you give it, in the order you give it.
 * Choosing the sources, their order and the thread is the game's job. To run a different set of sources,
 * compile again and build a new `StoryManager` from the new story.
 *
 * [HdgifeStory] is a normal Blade Ink [Story] with the lookahead-safe external function fix, and it is what
 * `StoryManager` takes. Everything else about it is the normal [Story] API.
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
 * `INCLUDE hdgife.ink` gives the bundled core library from the classpath, and `INCLUDE hdgife_util.ink` the optional
 * math helpers. They work together or alone, and are never added automatically.
 *
 * Errors are [InkCompileException]s. Ink's messages name the source and line.
 *
 * Sources from libGDX `FileHandle`s are compiled with the extension functions in GdxSources.kt (`compile(file)`,
 * `compileFolder(folder)`, `InkSource.of(file)`), which keep gdx types out of this class.
 */
public class InkCompilerService {
    /** Gets ink warnings and notes. Null means they are dropped. */
    public var log: HdgifeLog? = null

    init {
        // FLOOR, CEILING and INT crash on floats in Blade Ink 1.3.2, see InkNativePatch
        InkNativePatch.apply()
    }

    /** Compiles [sources]. The first is the root and the others are included after it, in order. */
    public fun compile(sources: List<InkSource>): HdgifeStory {
        require(sources.isNotEmpty()) { "No ink sources given" }
        val byName = LinkedHashMap<String, InkSource>()
        for (source in sources) {
            require(source.name !in BUILTIN_PATHS) { "'${source.name}' is reserved for the bundled library" }
            require(byName.put(source.name, source) == null) { "Duplicate ink source name '${source.name}'" }
        }
        val handler = SourceResolver(byName)
        val root = sources[0]
        val story = try {
            val text = buildString {
                append(handler.prepare(root, handler.read(root))).append('\n')
                for (extra in sources.drop(1)) append("INCLUDE ").append(extra.name).append('\n')
            }
            run(root.name, text, handler)
        } catch (e: InkCompileException) {
            throw InkCompileException(e.errors + handler.failures, e.source ?: handler.failedSource, e.cause)
        } catch (e: SourceReadException) {
            throw InkCompileException(listOf(e.message.orEmpty()), e.sourceName, e.cause)
        }
        return finish(story)
    }

    /** Compiles [main] as the root, then [extras] in the order given. */
    @JvmOverloads
    public fun compile(main: InkSource, extras: List<InkSource> = emptyList()): HdgifeStory = compile(listOf(main) + extras)

    /** Loads a story from compiled JSON, as made by `story.toJson()`. No compiler work is done. */
    public fun loadCompiled(json: String): HdgifeStory {
        val story = try {
            HdgifeStory(json)
        } catch (e: Exception) {
            throw InkCompileException(listOf("Cannot load compiled story: ${e.message}"), null, e)
        }
        return finish(story)
    }

    /** Rebuilds [story] as an [HdgifeStory] (fresh state) with ink fallbacks for EXTERNAL functions allowed. */
    private fun finish(story: Story): HdgifeStory = HdgifeStory.from(story).also { it.setAllowExternalFunctionFallbacks(true) }

    private fun run(rootName: String, text: String, handler: IFileHandler): Story {
        val errors = ArrayList<String>()
        val options = Compiler.Options().apply {
            sourceFilename = rootName
            fileHandler = handler
            errorHandler = ErrorHandler { message, type ->
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

        /** Name of the first source that could not be read, if any. */
        var failedSource: String? = null
            private set

        fun prepare(source: InkSource, text: String): String {
            loaded += source.name
            val dir = source.name.substringBeforeLast('/', "")
            val blocks = blockComments(text)
            return INCLUDE_LINE.replace(text) { match ->
                // an INCLUDE inside a /* */ block is text, and a trailing // or /* comment is not part of the name
                if (blocks.any { match.range.first in it }) return@replace match.value
                val target = match.groupValues[2].substringBefore("//").substringBefore("/*").trim()
                if (target.isEmpty()) return@replace match.value
                if (target in BUILTIN_PATHS) return@replace match.value
                val full = normalizePath("$dir/$target")
                if (full !in listed) source.sibling?.invoke(target)?.let { listed[full] = it }
                match.groupValues[1] + full
            }
        }

        fun read(source: InkSource): String =
            try {
                source.read()
            } catch (e: Exception) {
                failures += "Cannot read ink source '${source.name}': ${e.message}"
                if (failedSource == null) failedSource = source.name
                throw SourceReadException(source.name, e)
            }

        override fun resolveInkFilename(includeName: String): String = normalizePath(includeName)

        override fun loadInkFileContents(fullFilename: String): String {
            val builtinPath = BUILTIN_PATHS[fullFilename]
            if (builtinPath != null) {
                if (!loaded.add(fullFilename)) return ""
                return readBundled(builtinPath) ?: throw FileNotFoundException("Bundled $builtinPath is missing")
            }
            val source = listed[fullFilename] ?: run {
                failures += "Unlisted ink source: $fullFilename"
                throw FileNotFoundException("Unlisted ink source: $fullFilename")
            }
            if (!loaded.add(fullFilename)) return ""
            return prepare(source, read(source))
        }
    }

    private companion object {
        /**
         * The text of a bundled resource, or null when it is not there. The class loader serves it on the JVM and on
         * Android. A platform with no class loader resources (the browser, through TeaVM) can ship the folder as a
         * libGDX classpath asset instead, so that is the fallback.
         */
        fun readBundled(path: String): String? {
            val stream = InkCompilerService::class.java.classLoader?.getResourceAsStream(path)
            if (stream != null) return stream.use { String(it.readBytes(), Charsets.UTF_8) }
            val files = Gdx.files ?: return null
            val handle = files.classpath(path)
            return if (handle.exists()) handle.readString("UTF-8") else null
        }

        // Character ranges of the block comments in the text. Line comments are skipped so a block opener in one is ignored.
        fun blockComments(text: String): List<IntRange> {
            val ranges = ArrayList<IntRange>()
            var i = 0
            while (i < text.length) {
                if (text.startsWith("//", i)) {
                    val end = text.indexOf("\n", i)
                    if (end < 0) break
                    i = end + 1
                } else if (text.startsWith("/*", i)) {
                    val close = text.indexOf("*/", i + 2)
                    val end = if (close < 0) text.length else close + 2
                    ranges += i until end
                    i = end
                } else {
                    i++
                }
            }
            return ranges
        }

        /** The bundled libraries: the name ink sources `INCLUDE` them by, and where they sit on the classpath. */
        val BUILTIN_PATHS = mapOf(
            "hdgife.ink" to "hdgife/hdgife.ink",
            "hdgife_util.ink" to "hdgife/hdgife_util.ink",
        )
        val INCLUDE_LINE = Regex("(?m)^([ \\t]*INCLUDE[ \\t]+)([^\\r\\n]+)")
    }
}
