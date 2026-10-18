package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.TimeUtils
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.compiler.compile
import com.bladecoder.ink.runtime.HdgifeStory
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
 *
 * One exception, for the Android build made with `-PprecompiledInk`: when a shipped ink/main.ink.json exists and there
 * are no extra files, that story is loaded without compiling. Any extra file makes the harness compile as usual.
 */
fun compileHarnessStory(compiler: InkCompilerService): HdgifeStory {
    val extra = Gdx.files.local("extra/")
    val extras = if (extra.isDirectory) inkFiles(extra).sortedBy { it.path() } else emptyList()
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
    dir.list().flatMap { if (it.isDirectory) inkFiles(it) else listOfNotNull(it.takeIf { f -> f.extension() == "ink" }) }

/**
 * The harness. [onReady] is the script mode hook: it is called on the render thread once the story is compiled
 * and the dialog screen is showing. The UI test uses it to drive the screen; a normal run leaves it null.
 * [onFailure] gets the error when the story fails to compile or the screen fails to start, so a test can fail fast.
 */
class TestGame(
    private val onReady: ((DialogScreen) -> Unit)? = null,
    private val onFailure: ((Throwable) -> Unit)? = null,
) : KtxGame<KtxScreen>() {
    /** Called after each frame is drawn, before the buffers swap. The only place a screenshot sees the frame. */
    var afterRender: (() -> Unit)? = null

    override fun render() {
        super.render()
        afterRender?.invoke()
    }

    override fun create() {
        KtxAsync.initiate()
        val compiler = InkCompilerService().apply { log = HdgifeLog { logger.info { it } } }
        val provider = MapStateProvider().apply { loadShowcaseStats() }
        KtxAsync.launch {
            try {
                val story = withContext(Dispatchers.IO) { compileHarnessStory(compiler) }
                val manager = StoryManager(story, provider) {
                    log = HdgifeLog { logger.info { it } }
                }
                val screen = DialogScreen(manager, provider, compiler)
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
