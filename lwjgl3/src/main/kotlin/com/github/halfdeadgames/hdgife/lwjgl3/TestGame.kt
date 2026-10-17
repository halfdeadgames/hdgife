package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.HdgifeLog
import com.github.halfdeadgames.hdgife.story.StoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.async.KtxAsync
import ktx.log.logger

private val logger = logger<TestGame>()

/**
 * The harness's own load policy: ink/main.ink plus any .ink files under the local mods/ folder, sorted by
 * path. The engine just compiles the list in this order. Blocking, so call it off the render thread.
 */
fun compileHarnessStory(compiler: InkCompilerService): Story {
    val mods = Gdx.files.local("mods/")
    val extras = ArrayList<FileHandle>()
    if (mods.exists() && mods.isDirectory) collectInk(mods, extras)
    extras.sortBy { it.path() }
    return compiler.compile(Gdx.files.internal("ink/main.ink"), extras)
}

private fun collectInk(dir: FileHandle, out: MutableList<FileHandle>) {
    for (child in dir.list()) {
        if (child.isDirectory) collectInk(child, out) else if (child.extension() == "ink") out += child
    }
}

class TestGame : KtxGame<KtxScreen>() {
    override fun create() {
        KtxAsync.initiate()
        val compiler = InkCompilerService().apply { log = HdgifeLog { logger.info { it } } }
        val provider = MapStateProvider().apply {
            setStat("guard", "sanity", 80f)
            setStat("guard", "tiredness", 20f)
            setStat("guard", "mood", 60f)
            setStat("guard", "strength", 12f)
            setStat("merchant", "mood", 70f)
            setStat("merchant", "strength", 4f)
        }
        KtxAsync.launch {
            try {
                val story = withContext(Dispatchers.IO) { compileHarnessStory(compiler) }
                val manager = StoryManager(story, provider).apply {
                    speaker = "guard"
                    log = HdgifeLog { logger.info { it } }
                }
                addScreen(DialogScreen(manager, provider, compiler))
                setScreen<DialogScreen>()
            } catch (e: Exception) {
                logger.error(e) { "Ink compile failed" }
            }
        }
    }
}
