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

public class InkCompilerService {
    public var log: HdgifeLog? = null

    init {
        InkNativePatch.apply()
    }

    public fun compile(sources: List<InkSource>): HdgifeStory {
        require(sources.isNotEmpty()) { "No ink sources given" }
        val byName = LinkedHashMap<String, InkSource>()
        for (source in sources) {
            require(source.name !in bundledLibraryPaths) { "'${source.name}' is reserved for the bundled library" }
            require(byName.put(source.name, source) == null) { "Duplicate ink source name '${source.name}'" }
        }
        val resolver = SourceResolver(byName)
        val root = sources[0]
        val story = try {
            val text = buildString {
                append(resolver.prepare(root, resolver.read(root))).append('\n')
                for (extra in sources.drop(1)) append("INCLUDE ").append(extra.name).append('\n')
            }
            run(root.name, text, resolver)
        } catch (e: InkCompileException) {
            throw InkCompileException(e.errors + resolver.failures, e.source ?: resolver.failedSource, e.cause)
        } catch (e: SourceReadException) {
            throw InkCompileException(listOf(e.message.orEmpty()), e.sourceName, e.cause)
        }
        return finish(story)
    }

    @JvmOverloads
    public fun compile(main: InkSource, extras: List<InkSource> = emptyList()): HdgifeStory = compile(listOf(main) + extras)

    public fun loadCompiled(json: String): HdgifeStory {
        val story = try {
            HdgifeStory(json)
        } catch (e: Exception) {
            throw InkCompileException(listOf("Cannot load compiled story: ${e.message}"), null, e)
        }
        return finish(story)
    }

    private fun finish(story: Story): HdgifeStory = HdgifeStory.from(story).also { it.setAllowExternalFunctionFallbacks(true) }

    private fun run(rootName: String, text: String, resolver: IFileHandler): Story {
        val errors = ArrayList<String>()
        val options = Compiler.Options().apply {
            sourceFilename = rootName
            fileHandler = resolver
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

    private class SourceResolver(private val listed: MutableMap<String, InkSource>) : IFileHandler {
        private val loaded = HashSet<String>()

        val failures = ArrayList<String>()

        var failedSource: String? = null
            private set

        fun prepare(source: InkSource, text: String): String {
            loaded += source.name
            val directory = source.name.substringBeforeLast('/', "")
            val blockCommentRanges = blockComments(text)
            return includeLine.replace(text) { match ->
                if (blockCommentRanges.any { match.range.first in it }) return@replace match.value
                val target = match.groupValues[2].substringBefore("//").substringBefore("/*").trim()
                if (target.isEmpty()) return@replace match.value
                if (target in bundledLibraryPaths) return@replace match.value
                val fullPath = normalizePath("$directory/$target")
                if (fullPath !in listed) source.sibling?.invoke(target)?.let { listed[fullPath] = it }
                match.groupValues[1] + fullPath
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
            val bundledPath = bundledLibraryPaths[fullFilename]
            if (bundledPath != null) {
                if (!loaded.add(fullFilename)) return ""
                return readBundled(bundledPath) ?: throw FileNotFoundException("Bundled $bundledPath is missing")
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
        fun readBundled(path: String): String? {
            val classpathStream = InkCompilerService::class.java.classLoader?.getResourceAsStream(path)
            if (classpathStream != null) return classpathStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val gdxFiles = Gdx.files ?: return null
            val handle = gdxFiles.classpath(path)
            return if (handle.exists()) handle.readString("UTF-8") else null
        }

        fun blockComments(text: String): List<IntRange> {
            val ranges = ArrayList<IntRange>()
            var i = 0
            while (i < text.length) {
                if (text.startsWith("//", i)) {
                    val end = text.indexOf("\n", i)
                    if (end < 0) break
                    i = end + 1
                } else if (text.startsWith("/*", i)) {
                    val closeIndex = text.indexOf("*/", i + 2)
                    val end = if (closeIndex < 0) text.length else closeIndex + 2
                    ranges += i until end
                    i = end
                } else {
                    i++
                }
            }
            return ranges
        }

        val bundledLibraryPaths = mapOf(
            "hdgife.ink" to "hdgife/hdgife.ink",
            "hdgife_util.ink" to "hdgife/hdgife_util.ink",
        )
        val includeLine = Regex("(?m)^([ \\t]*INCLUDE[ \\t]+)([^\\r\\n]+)")
    }
}
