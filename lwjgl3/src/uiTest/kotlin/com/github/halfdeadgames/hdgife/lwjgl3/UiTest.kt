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
import kotlinx.coroutines.launch
import ktx.async.KtxAsync
import ktx.async.skipFrame
import java.io.File
import kotlin.system.exitProcess

/*
 * Automated UI test for the harness. It starts the real TestGame in a real window and plays it like a person:
 * it reads the log labels, clicks the choice buttons with real touch events and uses the console to set things up.
 * Run it with `./gradlew :lwjgl3:uiTest`. It needs a display (the window is hidden unless you pass -PuiShow).
 * Exit code 0 means all scenarios passed.
 */

private const val TIMEOUT_SECONDS = 180L
private const val SHOT_FRAME_BUDGET = 300

private class Check(message: String) : AssertionError(message)

private val TAG = Regex("\\[[^\\[\\]]*]")

/** Label markup to the text a player reads: tags removed, `[[` turned back into `[`. */
private fun plain(markup: String): String = markup.replace("[[", "\u0000").replace(TAG, "").replace("\u0000", "[")

private fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw Check(message())
}

private fun <T> expectEquals(expected: T, actual: T, what: String) =
    expect(expected == actual) { "$what: expected <$expected> but was <$actual>" }

/** What the test does and sees. All reads are of the real widgets on the stage. */
private class Ui(private val game: TestGame, val screen: DialogScreen, private val shots: File, private val hidden: Boolean) {
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

    /** Plain text of every log label, top to bottom. */
    val log: List<String> get() = screen.logLabels.map { plain(it.text.toString()) }

    /** Markup (colour tags included) of every log label. */
    val markup: List<String> get() = screen.logLabels.map { it.text.toString() }

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

    /** Back to a known world: story restarted, harness defaults for stats and choice mode. */
    suspend fun reset() {
        for (line in listOf(
            "restart", "choicemode grey",
            "npc guard sanity 80", "npc guard tiredness 20", "npc guard mood 60", "npc guard strength 12",
            "npc merchant mood 70", "hp 0",
        )) screen.runCommand(line)
        settle()
    }
}

private class Report {
    val passed = ArrayList<String>()
    val failed = ArrayList<Pair<String, String>>()
}

private suspend fun Ui.scenario(report: Report, name: String, reset: Boolean = true, body: suspend Ui.() -> Unit) {
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

private suspend fun runScenarios(ui: Ui, report: Report) {
    ui.settle()

    ui.scenario(report, "intro text", reset = false) {
        expect(log.take(4).let { it.size == 4 }) { "Expected at least 4 log lines, got $log" }
        // the harness starts with the guard as speaker, so the first lines carry his name
        expectEquals("guard: Welcome to the test story.", log[0], "first line")
        expectEquals("# intro", log[1], "tag line")
        expectEquals("guard: Player HP is 0. Name is Hero.", log[2], "second line")
        expectEquals("What now?", log[3], "third line")
        expectEquals(
            listOf("Look around", "Check my health", "Talk to the guard", "Watch the gate exchange", "Hear a rumor", "Leave"),
            buttons.map(::buttonText), "hub choices",
        )
        expect(buttons.none { it.isDisabled }) { "No hub choice should be disabled" }
        shot("01-intro")
    }

    ui.scenario(report, "hp from the console shows after Check my health") {
        command("hp 7")
        click("Check my health")
        expect("You have 7 hp." in log) { "Missing 'You have 7 hp.' in $log" }
        expect("You feel fine." in log) { "Missing 'You feel fine.' in $log" }
        shot("02-health-7")
        command("hp 0")
        click("Check my health")
        expect(log.takeLast(5).contains("You feel awful.")) { "Expected 'You feel awful.' near the end of $log" }
        expect(log.count { it == "You have 0 hp." } == 1) { "Expected one 'You have 0 hp.' in $log" }
    }

    ui.scenario(report, "set, divert and talk through the console") {
        command("set player_hp 12")
        command("divert status")
        expect("You have 12 hp." in log) { "set + divert should show 12 hp: $log" }
        command("talk guard_chat guard")
        expect(log.any { it.startsWith("guard: \"Halt, traveler.\"") }) { "talk should show the guard line: $log" }
    }

    ui.scenario(report, "guard scene follows npc stats and glue") {
        click("Talk to the guard")
        var scene = log.takeLast(8).joinToString("\n")
        expect("He stands crisp and alert." in scene) { "Calm guard expected:\n$scene" }
        expect("He regards you flatly." in scene) { "Flat mood expected:\n$scene" }
        expect("He holds his spear like a man who has used it." in scene) { "Strength 12 text expected:\n$scene" }
        expect("guard: Strength 12, strain 26.7, mood 60." in log) { "Numbers line missing: $log" }
        // mood 60 joins the glue line to the one above, with the stat call between them
        expect("guard: He keeps his eyes on you, patience at 60 percent." in log) { "Glue should join: $log" }
        expect("guard: He serves the crown and you carry 0 pass." in log) { "Faction line missing: $log" }
        shot("03-guard-calm")

        command("npc guard tiredness 90")
        command("npc guard mood 10")
        command("npc guard sanity 10")
        command("npc guard strength 18")
        val before = log.size
        click("Ask about the road")
        val added = log.drop(before)
        scene = added.joinToString("\n")
        expect("> Ask about the road" in added) { "Click should log the choice: $added" }
        expect("He sways on his feet, completely wrung out." in scene) { "Exhausted guard expected:\n$scene" }
        expect("He scowls at you." in scene) { "Scowl expected:\n$scene" }
        expect("His grip on the spear looks iron-hard." in scene) { "Strength 18 text expected:\n$scene" }
        expect("guard: Strength 18, strain 90.0, mood 10." in added) { "Numbers line wrong: $added" }
        // mood 10 does not glue: two separate labels
        expect("guard: He keeps his eyes on you," in added) { "Unjoined first half missing: $added" }
        expect("guard: patience at 10 percent." in added) { "Unjoined second half missing: $added" }
        shot("04-guard-tired")
    }

    ui.scenario(report, "colored and styled spans become label markup") {
        click("Watch the gate exchange")
        val m = markup
        val quiet = m.firstOrNull { "Quiet!" in it } ?: throw Check("No Quiet! line in $m")
        expect("[#ff5555ff]Quiet![]" in quiet) { "Hex colour markup wrong: $quiet" }
        expect("[#ffaa00ff]Strength[]" in quiet) { "Escaped hex colour markup wrong: $quiet" }
        val hurry = m.firstOrNull { "Hurry" in it } ?: throw Check("No Hurry line in $m")
        expect("[#66ccffff]Hurry[]" in hurry) { "0x colour markup wrong: $hurry" }
        val gold = m.firstOrNull { "practiced" in it } ?: throw Check("No merchant line in $m")
        expect("[GOLD]very[]" in gold) { "Named colour markup wrong: $gold" }
        // <em> opens on the first line and stays open over the second
        val em1 = m.firstOrNull { "does not lower" in it } ?: throw Check("No em line 1")
        val em2 = m.firstOrNull { "never leave you" in it } ?: throw Check("No em line 2")
        expect(em1.contains("[#ffd27f]He does not lower the spear.[]")) { "em on line 1 wrong: $em1" }
        expect(em2.contains("[#ffd27f]His eyes never leave you, not even for a blink.[]")) { "em should carry onto line 2: $em2" }
        // speakers: tag speaker on the crowd line, persistent speakers elsewhere
        expect(log.any { it.startsWith("crowd: ") }) { "Crowd tag speaker missing: $log" }
        expect(log.any { it.startsWith("merchant: ") }) { "Merchant speaker missing: $log" }
        // effects are not drawn, they are reported in a dim fx line
        expect(log.any { it.startsWith("fx: ") && "shake amp=2" in it }) { "fx line missing: $log" }
        shot("05-gate-styles")
    }

    ui.scenario(report, "requirement choices are greyed, hidden or shown with their needs") {
        click("Watch the gate exchange")
        val unavailable = listOf("Pull rank", "Call his bluff", "Stand aside")
        for (name in unavailable) expect(button(name).isDisabled) { "$name should be disabled" }
        for (name in listOf("Back the merchant up", "Show your papers", "Leave")) {
            expect(!button(name).isDisabled) { "$name should be enabled" }
        }
        val pullRank = buttonText(button("Pull rank"))
        expect("(needs player.rank >= 3 (have 2))" in pullRank) { "Needs text wrong: $pullRank" }
        val bluff = buttonText(button("Call his bluff"))
        expect("(needs guard.mood < 50 (have 60))" in bluff) { "Needs text wrong: $bluff" }
        val standAside = buttonText(button("Stand aside"))
        expect("(needs player.hp >= 5 (have 0))" in standAside) { "Needs text wrong: $standAside" }
        shot("06-choices-grey")

        // clicking a greyed button does nothing
        val before = log.size
        click(button("Pull rank"))
        expectEquals(before, log.size, "log size after clicking a disabled button")
        expectEquals(6, buttons.size, "choice count after clicking a disabled button")

        command("choicemode hide")
        expectEquals(3, buttons.size, "choices in hide mode")
        for (name in unavailable) expect(buttons.none { buttonText(it).startsWith(name) }) { "$name should be hidden" }
        shot("07-choices-hidden")

        command("choicemode show")
        expectEquals(6, buttons.size, "choices in show mode")
        expect(buttons.none { it.isDisabled }) { "Show mode keeps every choice clickable" }
        expect("(needs player.rank >= 3" in buttonText(button("Pull rank"))) { "Show mode keeps the needs note" }
        shot("08-choices-shown")

        // a stat change is picked up when the story builds its choices again
        command("choicemode grey")
        command("hp 9")
        command("divert gate_exchange")
        expect(!button("Stand aside").isDisabled) { "Stand aside should open up with hp 9" }
        expect("(needs" !in buttonText(button("Stand aside"))) { "No needs note when met" }
    }

    ui.scenario(report, "emitted events show in the log, in order") {
        click("Watch the gate exchange")
        val before = log.size
        click("Back the merchant up")
        val added = log.drop(before)
        val told = added.indexOfFirst { it.contains("Told you") }
        val event = added.indexOf("! event give_item(pass)")
        val hub = added.indexOf("What now?")
        expect(told >= 0 && event >= 0 && hub >= 0) { "Missing parts in $added" }
        expect(told < event && event < hub) { "Event should sit between the line and What now?: $added" }
        expectEquals(1, added.count { it.startsWith("! event") }, "event count")
        shot("09-event")
        // the harness acted on the event and ink reads the inventory back
        click("Talk to the guard")
        expect("guard: He serves the crown and you carry 1 pass." in log) { "Inventory should show 1 pass: ${log.takeLast(6)}" }
        // a second emit with two arguments
        command("hp 9")
        click("Leave")
        click("Watch the gate exchange")
        click("Stand aside")
        expect("! event reputation(guard, 5)" in log) { "reputation event missing: ${log.takeLast(6)}" }
    }

    ui.scenario(report, "an emit between a line and its glue joins and fires once") {
        click("Hear a rumor")
        val m = log
        expectEquals(1, m.count { it.startsWith("! event rumor_heard") }, "rumor event count")
        expect("Word has it that the gate falls tonight." in m) { "Glue across emit should join: $m" }
        expect(m.indexOf("! event rumor_heard(gate)") < m.indexOf("Word has it that the gate falls tonight.")) {
            "Event should come before the joined line: $m"
        }
        shot("10-rumor")
    }

    ui.scenario(report, "save, change and load bring back text, styles and choices") {
        click("Hear a rumor")
        expect(log.any { it.startsWith("(still open: color=ff5555") }) { "Style should be open at the choice: $log" }
        val savedLog = log
        val savedChoices = buttons.map(::buttonText)
        command("save")

        click("Listen")
        val listened = markup.first { it.contains("\"Run,\" he says") }
        expect(listened.startsWith("[#ff5555ff]\"Run,\" he says, ")) { "Red should carry over the choice: $listened" }
        expect("[#ffffff]fast[]" in listened) { "Bold inside red should show: $listened" }
        expect(log.any { it.endsWith("Then he is gone.") }) { "Tail text missing: $log" }
        expect(log.size > savedLog.size) { "Log should have grown" }
        shot("11-after-listen")

        command("load")
        // load puts the saved log back, adds its own note, and the step shows the style that is open again
        val reopened = savedLog.last { it.startsWith("(still open: ") }
        expectEquals(savedLog + "(state loaded, speaker is now none)" + reopened, log, "log after load")
        expectEquals(savedChoices, buttons.map(::buttonText), "choices after load")
        shot("12-after-load")

        // the open red style came back: the same click gives the same red text
        click("Listen")
        val again = markup.first { it.contains("\"Run,\" he says") }
        expectEquals(listened, again, "line after load")
        shot("13-listen-again")
    }

    ui.scenario(report, "restart after the story ends") {
        expect(!screen.restartButton.isVisible) { "Restart button should be hidden while the story runs" }
        click("Leave")
        expect("You walk away." in log) { "Farewell text missing: $log" }
        expectEquals(emptyList(), buttons.map(::buttonText), "choices after the end")
        expectEquals("Story ended.", screen.statusLabel.text.toString(), "status")
        expect(screen.restartButton.isVisible) { "Restart button should show at the end" }
        shot("14-ended")

        val rb = screen.restartButton
        val centre = rb.localToStageCoordinates(Vector2(rb.width / 2f, rb.height / 2f))
        val p = screen.stage.stageToScreenCoordinates(Vector2(centre))
        screen.stage.touchDown(p.x.toInt(), p.y.toInt(), 0, Input.Buttons.LEFT)
        screen.stage.touchUp(p.x.toInt(), p.y.toInt(), 0, Input.Buttons.LEFT)
        settle()
        expectEquals("Welcome to the test story.", log.first(), "first line after restart")
        expectEquals("What now?", log.last(), "last line after restart")
        expect(!screen.restartButton.isVisible) { "Restart button should hide again" }
        expectEquals(6, buttons.size, "hub choices after restart")
        shot("15-restarted")
    }

    ui.scenario(report, "seed makes the guard repeatable") {
        fun guardRun(): List<String> {
            screen.runCommand("restart")
            screen.runCommand("seed 7")
            val before = log.size
            screen.runCommand("talk guard_chat guard")
            return log.drop(before)
        }
        val first = guardRun()
        settle()
        val second = guardRun()
        expect(first.isNotEmpty()) { "talk produced nothing" }
        expectEquals(first, second, "guard scene with the same seed")
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
            KtxAsync.launch {
                try {
                    // assign first so a crash still prints the screenshot summary
                    val created = Ui(game, screen, shots, hidden)
                    ui = created
                    runScenarios(created, report)
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
        setWindowedMode(800, 600)
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
