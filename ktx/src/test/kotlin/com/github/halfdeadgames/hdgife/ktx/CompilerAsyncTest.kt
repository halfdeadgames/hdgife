package com.github.halfdeadgames.hdgife.ktx

import com.github.halfdeadgames.hdgife.compiler.InkCompileException
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.compiler.InkSource
import com.github.halfdeadgames.hdgife.story.StoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompilerAsyncTest {
    private val compiler = InkCompilerService()

    @Test
    fun `compileAsync returns a story that plays`() = runBlocking {
        val story = compiler.compileAsync(listOf(InkSource("main.ink", eventStoryText)))
        assertEquals("Start", StoryManager(story).next().lines.single().text)
    }

    @Test
    fun `compileAsync reads a file on the given dispatcher`() = runBlocking {
        val story = compiler.compileAsync(inkFile("main.ink", eventStoryText), dispatcher = Dispatchers.IO)
        assertEquals("Start", StoryManager(story).next().lines.single().text)
    }

    @Test
    fun `compileAsync passes compile errors to the caller`() = runBlocking<Unit> {
        assertFailsWith<InkCompileException> { compiler.compileAsync(listOf(InkSource("main.ink", "-> nowhere"))) }
    }

    @Test
    fun `loadCompiledAsync loads precompiled json`() = runBlocking {
        val story = compiler.loadCompiledAsync(compileStory().toJson())
        assertEquals("Start", StoryManager(story).next().lines.single().text)
    }

    @Test
    fun `loadCompiledAsync rejects text that is not a story`() = runBlocking<Unit> {
        assertFailsWith<InkCompileException> { compiler.loadCompiledAsync("not json") }
    }
}
