package com.github.halfdeadgames.hdgife.ktx

import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.badlogic.gdx.files.FileHandle
import com.bladecoder.ink.runtime.HdgifeStory
import com.github.halfdeadgames.hdgife.compiler.InkCompileException
import com.github.halfdeadgames.hdgife.story.StoryManager
import kotlinx.coroutines.runBlocking
import ktx.assets.async.AssetStorage
import ktx.async.KtxAsync
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class InkStoryLoaderTest {
    private val application = RecordedApplication()

    @BeforeTest
    fun installApplication() {
        application.install()
        KtxAsync.initiate()
    }

    @AfterTest
    fun uninstallApplication() = application.uninstall()

    private fun loadWithManager(path: String): HdgifeStory {
        val manager = AssetManager(pathResolver)
        manager.registerInkLoaders()
        manager.load(path, HdgifeStory::class.java)
        manager.finishLoading()
        val story = manager.get(path, HdgifeStory::class.java)
        manager.dispose()
        return story
    }

    @Test
    fun `an asset manager compiles a dot ink file`() {
        val story = loadWithManager(inkFile("scene.ink", eventStoryText).path())
        assertEquals("Start", StoryManager(story).next().lines.single().text)
    }

    @Test
    fun `an asset manager loads a dot ink dot json file`() {
        val story = loadWithManager(inkFile("scene.ink.json", compileStory().toJson()).path())
        assertEquals("Start", StoryManager(story).next().lines.single().text)
    }

    @Test
    fun `a dot ink file includes its sibling files`() {
        val directory = Files.createTempDirectory("hdgife-ktx-include")
        Files.writeString(directory.resolve("other.ink"), "=== other_knot ===\nOther line\n-> END\n")
        Files.writeString(directory.resolve("main.ink"), "INCLUDE other.ink\n-> other_knot\n")
        val story = loadWithManager(directory.resolve("main.ink").toString())
        assertEquals("Other line", StoryManager.forPlainStory(story).next().lines.single().text)
    }

    @Test
    fun `an asset storage loads a story and shares it between loads`() = runBlocking {
        val storage = AssetStorage(fileResolver = pathResolver)
        val path = inkFile("scene.ink", eventStoryText).path()
        val first = storage.loadInkStory(path)
        val second = storage.loadInkStory(path)
        assertSame(first, second)
        assertEquals("Start", StoryManager(first).next().lines.single().text)
        storage.dispose()
    }

    @Test
    fun `an asset storage loads precompiled json`() = runBlocking {
        val storage = AssetStorage(fileResolver = pathResolver)
        val path = inkFile("scene.ink.json", compileStory().toJson()).path()
        assertEquals("Start", StoryManager(storage.loadInkStory(path)).next().lines.single().text)
        storage.dispose()
    }

    private fun rootedResolver(): FileHandleResolver {
        val root = Files.createTempDirectory("hdgife-ktx-root")
        Files.createDirectories(root.resolve("stories"))
        Files.writeString(root.resolve("stories/other.ink"), "=== other_knot ===\nRooted line\n-> END\n")
        Files.writeString(root.resolve("stories/main.ink"), "INCLUDE other.ink\n-> other_knot\n")
        return FileHandleResolver { path -> FileHandle(root.resolve(path).toFile()) }
    }

    @Test
    fun `an include resolves through the resolver of an asset manager`() {
        val manager = AssetManager(rootedResolver())
        manager.registerInkLoaders()
        manager.load("stories/main.ink", HdgifeStory::class.java)
        manager.finishLoading()
        val story = manager.get("stories/main.ink", HdgifeStory::class.java)
        assertEquals("Rooted line", StoryManager.forPlainStory(story).next().lines.single().text)
        manager.dispose()
    }

    @Test
    fun `an include resolves through the resolver of an asset storage`() = runBlocking {
        val storage = AssetStorage(fileResolver = rootedResolver())
        val story = storage.loadInkStory("stories/main.ink")
        assertEquals("Rooted line", StoryManager.forPlainStory(story).next().lines.single().text)
        storage.dispose()
    }

    @Test
    fun `a story unloaded before loadSync is forgotten`() {
        val manager = AssetManager(pathResolver)
        val loader = InkStoryLoader(pathResolver)
        val path = inkFile("scene.ink", eventStoryText).path()
        loader.loadAsync(manager, path, FileHandle(path), null)
        loader.unloadAsync(manager, path, FileHandle(path), null)
        assertFailsWith<IllegalStateException> { loader.loadSync(manager, path, FileHandle(path), null) }
        manager.dispose()
    }

    @Test
    fun `a failed compile leaves nothing behind for loadSync`() {
        val manager = AssetManager(pathResolver)
        val loader = InkStoryLoader(pathResolver)
        val path = inkFile("broken.ink", "-> nowhere").path()
        assertFailsWith<InkCompileException> { loader.loadAsync(manager, path, FileHandle(path), null) }
        assertFailsWith<IllegalStateException> { loader.loadSync(manager, path, FileHandle(path), null) }
        manager.dispose()
    }
}
