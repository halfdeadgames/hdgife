@file:JvmName("UiTest")

package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.ScreenUtils
import com.github.halfdeadgames.hdgife.harness.*
import kotlinx.coroutines.launch
import ktx.async.KtxAsync
import ktx.async.skipFrame
import java.io.File
import kotlin.system.exitProcess

/*
 * Automated UI test for the harness. It starts the real TestGame in a real window and plays it like a person:
 * it reads the raw panel and the rendered rows, clicks the choice buttons with real touch events and uses the console
 * to set things up. The scenarios are in Scenarios.kt.
 * Run it with `./gradlew :lwjgl3:uiTest`. It needs a display (the window is hidden unless you pass -PuiShow).
 * Exit code 0 means all scenarios passed.
 */

private const val TIMEOUT_SECONDS = 180L
private const val SHOT_FRAME_BUDGET = 300

internal class Check(message: String) : AssertionError(message)

internal val TAG = Regex("\\[[^\\[\\]]*]")

/** Label markup to the text a player reads: tags removed, `[[` turned back into `[`. */
internal fun plain(markup: String): String = markup.replace("[[", "\u0000").replace(TAG, "").replace("\u0000", "[")

internal fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw Check(message())
}

internal fun <T> expectEquals(expected: T, actual: T, what: String) =
    expect(expected == actual) { "$what: expected <$expected> but was <$actual>" }

/** What the test does and sees. All reads are of the real widgets on the stage. */
internal class Ui(private val game: TestGame, val screen: DialogScreen, private val shots: File, private val hidden: Boolean) {
    private var pendingShot: String? = null
    val shotNames = ArrayList<String>()

    init {
        game.afterRender = { pendingShot?.let { grab(it); pendingShot = null } }
    }

    /** Lets a few frames pass so layout and rebuilt widgets settle. */
    suspend fun settle() = repeat(3) { skipFrame() }

    suspend fun command(line: String) {
        screen.runCommand(line)
        settle()
    }

    /** Plain text of every rendered row, top to bottom. */
    val log: List<String> get() = screen.renderedLabels.map { plain(it.text.toString()) }

    /** Markup (colour tags included) of every rendered row. */
    val markup: List<String> get() = screen.renderedLabels.map { it.text.toString() }

    /** Plain text of the raw panel. */
    val raw: String get() = plain(screen.rawText)

    fun dump(name: String) {
        val nl = System.lineSeparator()
        val out = File(shots, "dump-$name.txt")
        out.writeText(
            "=== RAW ===" + nl + raw + nl + "=== RENDERED ===" + nl + markup.joinToString(nl) + nl +
                "=== BUTTONS ===" + nl + buttons.joinToString(nl) { buttonText(it) + if (it.isDisabled) "  [disabled]" else "" } + nl +
                "=== INFO ===" + nl + screen.infoLabel.text + nl + screen.statusLabel.text + nl,
        )
    }

    val buttons: List<TextButton> get() = screen.choiceButtons

    fun buttonText(button: TextButton) = plain(button.label.text.toString())

    fun button(prefix: String): TextButton = buttons.firstOrNull { buttonText(it).startsWith(prefix) }
        ?: throw Check("No choice button starting with \"$prefix\". On screen: ${buttons.map(::buttonText)}")

    /** Presses and releases at the centre of [button] in stage coordinates, through the stage's own input handling. */
    suspend fun click(button: TextButton) {
        val stage = screen.stage
        val centre = button.localToStageCoordinates(Vector2(button.width / 2f, button.height / 2f))
        val screenPoint = stage.stageToScreenCoordinates(Vector2(centre))
        val x = screenPoint.x.toInt()
        val y = screenPoint.y.toInt()
        expect(button.stage === stage && centre.y in 0f..stage.height) { "Button is not on the visible stage: $centre" }
        val hit = stage.hit(centre.x, centre.y, true)
        expect(hit != null && (hit === button || hit.isDescendantOf(button))) {
            "Click at $centre would hit ${hit?.javaClass?.simpleName ?: "nothing"}, not \"${buttonText(button)}\" (covered or off screen?)"
        }
        stage.touchDown(x, y, 0, Input.Buttons.LEFT)
        stage.touchUp(x, y, 0, Input.Buttons.LEFT)
        settle()
    }

    suspend fun click(prefix: String) = click(button(prefix))

    /** Saves a screenshot of the next drawn frame and checks it is not empty. */
    suspend fun shot(name: String) {
        settle()
        pendingShot = name
        var frames = 0
        while (pendingShot != null) {
            if (++frames > SHOT_FRAME_BUDGET) {
                pendingShot = null
                throw Check("Screenshot \"$name\" was not taken within $SHOT_FRAME_BUDGET frames (is the game rendering?)")
            }
            skipFrame()
        }
    }

    private fun grab(name: String) {
        val width = Gdx.graphics.backBufferWidth
        val height = Gdx.graphics.backBufferHeight
        val pixels = ScreenUtils.getFrameBufferPixels(0, 0, width, height, true)
        // the buffer has no meaningful alpha, make every pixel opaque
        for (i in 3 until pixels.size step 4) pixels[i] = 0xFF.toByte()
        // count pixels that differ from the first one (the background), to catch a blank frame
        var different = 0
        for (i in 4 until pixels.size step 4) {
            if (pixels[i] != pixels[0] || pixels[i + 1] != pixels[1] || pixels[i + 2] != pixels[2]) different++
        }
        val pixmap = Pixmap(width, height, Pixmap.Format.RGBA8888)
        BufferUtils.copy(pixels, 0, pixmap.pixels, pixels.size)
        shots.mkdirs()
        val file = File(shots, "$name.png")
        PixmapIO.writePNG(FileHandle(file), pixmap)
        pixmap.dispose()
        shotNames += file.name
        if (different < 500) blankShots += name // a hidden window often gives blank frames; main() reports that
    }

    val blankShots = ArrayList<String>()

    /** Back to a known world: default stats, story restarted, harness defaults for the view, choices, dice and numbers. */
    suspend fun reset() {
        for (line in listOf("resetstats", "restart", "choicemode grey", "view split", "fixroll off", "numfmt 0 round")) {
            screen.runCommand(line)
        }
        settle()
    }
}

internal class Report {
    val passed = ArrayList<String>()
    val failed = ArrayList<Pair<String, String>>()
}

internal suspend fun Ui.scenario(report: Report, name: String, reset: Boolean = true, body: suspend Ui.() -> Unit) {
    try {
        if (reset) reset()
        body()
        report.passed += name
    } catch (e: Throwable) {
        report.failed += name to (e.message ?: e.toString())
        if (e !is Check) e.printStackTrace()
        runCatching { shot("FAILED-$name") }
    }
}

fun main(args: Array<String>) {
    val shots = File(System.getProperty("hdgife.shots") ?: "build/ui-shots")
    shots.mkdirs()
    shots.listFiles { f -> f.extension == "png" }?.forEach { it.delete() }
    val report = Report()
    var finished = false
    var crashed: Throwable? = null

    // if the game never comes up or a scenario hangs, fail instead of waiting forever
    Thread {
        Thread.sleep(TIMEOUT_SECONDS * 1000)
        System.err.println("UI TEST FAILED: no result after $TIMEOUT_SECONDS seconds")
        Runtime.getRuntime().halt(2)
    }.apply { isDaemon = true }.start()

    val hidden = "--show" !in args
    lateinit var game: TestGame
    var ui: Ui? = null
    game = TestGame(
        onReady = { screen ->
            // the harness itself uses no coroutines (so it runs under TeaVM), the test script does
            KtxAsync.initiate()
            KtxAsync.launch {
                try {
                    // assign first so a crash still prints the screenshot summary
                    val created = Ui(game, screen, shots, hidden)
                    ui = created
                    scenarios(created, report)
                } catch (e: Throwable) {
                    crashed = e
                }
                finished = true
                Gdx.app.exit()
            }
        },
        onFailure = { e ->
            crashed = e
            finished = true
            Gdx.app.exit()
        },
    )
    Lwjgl3Application(game, Lwjgl3ApplicationConfiguration().apply {
        setTitle("hdgife ui test")
        setWindowedMode(1280, 720)
        setInitialVisible(!hidden)
        useVsync(false)
        setForegroundFPS(60)
    })

    val blank = ui?.blankShots.orEmpty()
    println()
    println("=== UI test summary ===")
    for (name in report.passed) println("PASS  $name")
    for ((name, why) in report.failed) println("FAIL  $name\n      $why")
    println("Screenshots (${ui?.shotNames?.size ?: 0}) in ${shots.absolutePath}: ${ui?.shotNames.orEmpty().joinToString()}")
    if (blank.isNotEmpty()) {
        val note = if (hidden) "WARNING: blank screenshots $blank. A hidden window may not render on every system; run with -PuiShow to check."
        else "BLANK screenshots: $blank"
        println(note)
    }
    println("${report.passed.size} passed, ${report.failed.size} failed")
    val failure = when {
        !finished -> "the game closed before the script finished"
        crashed != null -> "the script crashed: $crashed"
        report.failed.isNotEmpty() -> "${report.failed.size} scenario(s) failed"
        blank.isNotEmpty() && !hidden -> "blank screenshots: $blank"
        else -> null
    }
    if (failure != null) {
        System.err.println("UI TEST FAILED: $failure")
        crashed?.printStackTrace()
        exitProcess(1)
    }
    println("UI TEST PASSED")
    exitProcess(0)
}
