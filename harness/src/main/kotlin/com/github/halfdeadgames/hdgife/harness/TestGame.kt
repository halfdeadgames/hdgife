package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.TimeUtils
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.compiler.compile
import com.bladecoder.ink.runtime.HdgifeStory
import com.github.halfdeadgames.hdgife.HdgifeLog
import com.github.halfdeadgames.hdgife.story.StoryManager
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.log.logger

private val logger = logger<TestGame>()

fun <T> runInBackground(work: () -> T, onDone: (Result<T>) -> Unit) {
    if (Gdx.app.type == Application.ApplicationType.WebGL) {
        onDone(runCatching(work))
        return
    }
    Thread {
        val outcome = runCatching(work)
        Gdx.app.postRunnable { onDone(outcome) }
    }.apply { isDaemon = true }.start()
}

fun compileHarnessStory(compiler: InkCompilerService): HdgifeStory {
    val web = Gdx.app.type == Application.ApplicationType.WebGL
    val extra = if (web) null else Gdx.files.local("extra/")
    val extras = if (extra != null && extra.isDirectory) inkFiles(extra).sortedBy { it.path() } else emptyList()
    val precompiled = Gdx.files.internal("ink/main.ink.json")
    val usePrecompiled = extras.isEmpty() && precompiled.exists()
    val started = TimeUtils.millis()
    val story = if (usePrecompiled) {
        compiler.loadCompiled(precompiled.readString("UTF-8"))
    } else {
        compiler.compile(Gdx.files.internal("ink/main.ink"), extras)
    }
    logger.info { "Story ready in ${TimeUtils.timeSinceMillis(started)} ms (${if (usePrecompiled) "loaded precompiled json" else "compiled ink"})" }
    return story
}

private fun inkFiles(dir: FileHandle): List<FileHandle> =
    dir.list().flatMap { if (it.isDirectory) inkFiles(it) else listOfNotNull(it.takeIf { file -> file.extension() == "ink" }) }

class TestGame(
    private val onReady: ((DialogScreen) -> Unit)? = null,
    private val onFailure: ((Throwable) -> Unit)? = null,
    private val fonts: FontSource? = null,
) : KtxGame<KtxScreen>() {
    var afterRender: (() -> Unit)? = null

    override fun render() {
        super.render()
        afterRender?.invoke()
    }

    override fun create() {
        val compiler = InkCompilerService().apply { log = HdgifeLog { logger.info { it } } }
        val provider = MapStateProvider().apply { loadShowcaseStats() }
        runInBackground({ compileHarnessStory(compiler) }) { outcome ->
            try {
                val story = outcome.getOrThrow()
                val manager = StoryManager(story, provider) {
                    log = HdgifeLog { logger.info { it } }
                }
                val screen = DialogScreen(manager, provider, compiler, fonts)
                addScreen(screen)
                setScreen<DialogScreen>()
                onReady?.invoke(screen)
            } catch (e: Exception) {
                logger.error(e) { "Ink compile failed" }
                onFailure?.invoke(e)
            }
        }
    }
}
