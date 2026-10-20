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
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.ScreenUtils
import com.github.halfdeadgames.hdgife.harness.*
import kotlinx.coroutines.launch
import ktx.async.KtxAsync
import ktx.async.skipFrame
import java.io.File
import kotlin.system.exitProcess

private const val timeoutSeconds = 180L
private const val shotFrameBudget = 300

internal class Check(message: String) : AssertionError(message)

internal val markupTag = Regex("\\[[^\\[\\]]*]")

internal fun plain(markup: String): String = markup.replace("[[", "\u0000").replace(markupTag, "").replace("\u0000", "[")

internal fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw Check(message())
}

internal fun <T> expectEquals(expected: T, actual: T, what: String) =
    expect(expected == actual) { "$what: expected <$expected> but was <$actual>" }

internal class Ui(private val game: TestGame, val screen: DialogScreen, private val shots: File, private val hidden: Boolean) {
    private var pendingShot: String? = null
    val shotNames = ArrayList<String>()

    init {
        game.afterRender = { pendingShot?.let { grab(it); pendingShot = null } }
    }

    suspend fun settle() = repeat(3) { skipFrame() }

    suspend fun command(line: String) {
        screen.runCommand(line)
        settle()
    }

    val log: List<String> get() = screen.renderedMarkup.map(::plain)

    val markup: List<String> get() = screen.renderedMarkup

    val raw: String get() = plain(screen.rawText)

    fun dump(name: String) {
        val newline = System.lineSeparator()
        val dumpFile = File(shots, "dump-$name.txt")
        dumpFile.writeText(
            "=== RAW ===" + newline + raw + newline + "=== RENDERED ===" + newline + markup.joinToString(newline) + newline +
                "=== BUTTONS ===" + newline + buttons.joinToString(newline) { buttonText(it) + if (it.isDisabled) "  [disabled]" else "" } + newline +
                "=== INFO ===" + newline + screen.infoLabel.text + newline + screen.statusLabel.text + newline,
        )
    }

    val buttons: List<TextButton> get() = screen.choiceButtons

    fun buttonText(button: TextButton) = plain(button.label.text.toString())

    fun button(prefix: String): TextButton = buttons.firstOrNull { buttonText(it).startsWith(prefix) }
        ?: throw Check("No choice button starting with \"$prefix\". On screen: ${buttons.map(::buttonText)}")

    suspend fun click(button: TextButton) {
        screen.scrollToChoice(button)
        tap(button, "\"${buttonText(button)}\"")
    }

    suspend fun tap(target: Actor, name: String) {
        val stage = screen.stage
        val centre = target.localToStageCoordinates(Vector2(target.width / 2f, target.height / 2f))
        val screenPoint = stage.stageToScreenCoordinates(Vector2(centre))
        val screenX = screenPoint.x.toInt()
        val screenY = screenPoint.y.toInt()
        expect(target.stage === stage && centre.y in 0f..stage.height) { "$name is not on the visible stage: $centre" }
        val hit = stage.hit(centre.x, centre.y, true)
        expect(hit != null && (hit === target || hit.isDescendantOf(target))) {
            "Click at $centre would hit ${hit?.javaClass?.simpleName ?: "nothing"}, not $name (covered or off screen?)"
        }
        stage.touchDown(screenX, screenY, 0, Input.Buttons.LEFT)
        stage.touchUp(screenX, screenY, 0, Input.Buttons.LEFT)
        settle()
    }

    suspend fun waitUntil(what: String, frames: Int = 900, condition: () -> Boolean) {
        var waited = 0
        while (!condition()) {
            if (++waited > frames) throw Check("Gave up waiting for $what after $frames frames")
            skipFrame()
        }
    }

    suspend fun click(prefix: String) = click(button(prefix))

    suspend fun shot(name: String) {
        settle()
        pendingShot = name
        var frames = 0
        while (pendingShot != null) {
            if (++frames > shotFrameBudget) {
                pendingShot = null
                throw Check("Screenshot \"$name\" was not taken within $shotFrameBudget frames (is the game rendering?)")
            }
            skipFrame()
        }
    }

    private fun grab(name: String) {
        val width = Gdx.graphics.backBufferWidth
        val height = Gdx.graphics.backBufferHeight
        val pixels = ScreenUtils.getFrameBufferPixels(0, 0, width, height, true)
        for (i in 3 until pixels.size step 4) pixels[i] = 0xFF.toByte()
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
        if (different < 500) blankShots += name
    }

    val blankShots = ArrayList<String>()

    suspend fun reset() {
        for (line in listOf("typewriter off", "resetstats", "restart", "choicemode grey", "view split", "fixroll off", "numfmt 0 round")) {
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
    shots.listFiles { file -> file.extension == "png" }?.forEach { it.delete() }
    val report = Report()
    var finished = false
    var crashed: Throwable? = null

    Thread {
        Thread.sleep(timeoutSeconds * 1000)
        System.err.println("UI TEST FAILED: no result after $timeoutSeconds seconds")
        Runtime.getRuntime().halt(2)
    }.apply { isDaemon = true }.start()

    val hidden = "--show" !in args
    lateinit var game: TestGame
    var ui: Ui? = null
    game = TestGame(
        onReady = { screen ->
            KtxAsync.initiate()
            KtxAsync.launch {
                try {
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
