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
 * The harness's own load policy: ink/main.ink plus any .ink files under the local extra/ folder, sorted by
 * path. The game (here, the harness) decides which sources to feed and in what order; the engine just compiles
 * the list as given. Blocking, so call it off the render thread.
 */
fun compileHarnessStory(compiler: InkCompilerService): Story {
    val extra = Gdx.files.local("extra/")
    val extras = if (extra.isDirectory) inkFiles(extra).sortedBy { it.path() } else emptyList()
    return compiler.compile(Gdx.files.internal("ink/main.ink"), extras)
}

private fun inkFiles(dir: FileHandle): List<FileHandle> =
    dir.list().flatMap { if (it.isDirectory) inkFiles(it) else listOfNotNull(it.takeIf { f -> f.extension() == "ink" }) }

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
            setStat("guard", "faction", "crown")          // a string stat
            setStat("merchant", "faction", "guild")
            setStat("player", "faction", "rebels")
            setStat("player", "is_injured", false)        // a boolean stat
            setStat("player", "rank", 2)                  // an int stat
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
