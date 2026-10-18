package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.github.halfdeadgames.hdgife.HdgifeLog
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.IncompatibleLibraryException
import com.github.halfdeadgames.hdgife.story.NumberFormat
import com.github.halfdeadgames.hdgife.story.Rounding
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.StoryStepException
import com.github.halfdeadgames.hdgife.story.VariableObservation
import com.strongjoshua.console.GUIConsole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ktx.app.KtxInputAdapter
import ktx.app.KtxScreen
import ktx.app.clearScreen
import ktx.async.KtxAsync
import ktx.log.logger
import ktx.scene2d.Scene2DSkin
import ktx.scene2d.label
import ktx.scene2d.scene2d
import ktx.scene2d.textButton
import kotlin.random.Random

private val log = logger<DialogScreen>()

/** Ink variables the harness watches from the start. Their latest value is in the info line under the panels. */
private val WATCHED = listOf("suspicion", "door_open", "player_coin")

/** What `save` keeps: the engine's state string and the step that was on screen, so `load` can show it again. */
private class Snapshot(val state: String, val step: DialogStep, val number: Int)

/**
 * Test UI for the ink story. It shows only the latest [DialogStep], twice: a RAW panel with the exact engine output
 * and a RENDERED panel with the same step as a game might draw it. A debug console sits on top.
 * The read-only members below ([stage], [rawText], [renderedLabels], [choiceButtons], [restartButton],
 * [runCommand]...) are what the UI test (`:lwjgl3:uiTest`) uses to look at the screen and drive it.
 */
class DialogScreen(
    private val manager: StoryManager,
    private val provider: MapStateProvider,
    private val compiler: InkCompilerService,
) : KtxScreen, DialogControl {
    private val skin = Skin(Gdx.files.internal("ui/uiskin.json"))
    private val consoleSkin = Skin(Gdx.files.classpath("default_skin/uiskin.json"))
    private val mono: BitmapFont
    val stage = Stage(ScreenViewport())
    private val console: GUIConsole
    private val raw: RawPanel
    private val rendered: RenderedPanel
    private val panelRow = Table()
    val statusLabel: Label
    val infoLabel: Label
    val restartButton: TextButton

    private var lastStep: DialogStep? = null
    private var stepNumber = 0
    private var choiceMode = ChoiceMode.GREY
    var viewMode = ViewMode.SPLIT
        private set
    private var snapshot: Snapshot? = null
    private val observations = mutableMapOf<String, VariableObservation>()
    private val watched = LinkedHashMap<String, String>()
    private var rng = Random(1)
    private var fixedRoll: Int? = null

    /** Everything written to the console, in order, so the UI test can read it. Includes the engine's own log lines. */
    val consoleLines = ArrayList<String>()

    private fun say(message: String) {
        consoleLines += message
        console.log(message)
    }

    /** The harness's own game state, driven by ink: `emit("give_item", x)` adds to it, ink reads it back. */
    private val inventory = mutableMapOf<String, Int>()

    init {
        // ktx-scene2d builders read the default skin
        Scene2DSkin.defaultSkin = skin
        // the skin's disabled text is dark on dark, too dim to read a greyed-out choice and what it needs
        skin.add(CHOICE_STYLE, TextButton.TextButtonStyle(skin.get(TextButton.TextButtonStyle::class.java)).apply {
            disabledFontColor = Color(0.72f, 0.72f, 0.72f, 1f)
        })
        // spans are drawn with libGDX color markup, so every skin font needs it on
        for (font in skin.getAll(BitmapFont::class.java).values()) font.data.markupEnabled = true
        mono = monoFont(skin, Gdx.files.internal("ui/font-list.fnt"))

        raw = RawPanel(skin, mono)
        rendered = RenderedPanel(skin, ::choose)
        statusLabel = scene2d.label("")
        infoLabel = scene2d.label("").apply { color.set(0.62f, 0.62f, 0.62f, 1f) }
        restartButton = scene2d.textButton("Restart").apply { onChange { restart() } }

        val root = Table().apply {
            setFillParent(true)
            pad(8f)
            add(panelRow).grow().row()
            add(infoLabel).left().padTop(4f).row()
            add(statusLabel).left().padTop(2f).row()
            add(restartButton).left().padTop(4f)
        }
        stage.addActor(root)
        restartButton.isVisible = false
        applyViewMode()

        // the console needs fonts and drawables that uiskin.json lacks, so it gets its bundled skin
        console = GUIConsole(consoleSkin, false, Input.Keys.GRAVE)
        console.setCommandExecutor(DialogCommands(this))
        console.isVisible = false
        say("Console ready. Type help. F2 changes the layout.")

        // the engine's warnings go to the console too, next to wherever the game already sent them
        val engineLog = manager.log
        manager.log = HdgifeLog { message ->
            engineLog?.log(message)
            say("engine: $message")
        }
        manager.formatOverride = ::harnessFormatOverride
        // The engine reports events and never acts on them. This is the harness acting on them.
        manager.onEvent = { event ->
            say("event #${event.id} ${event.name}(${event.args.joinToString { show(it) }}) before line ${event.lineIndex}")
            val first = event.args.firstOrNull()?.toString()
            when (event.name) {
                "give_item" -> if (first != null) inventory.merge(first, 1, Int::plus)
                "spend" -> {
                    val amount = event.args.getOrNull(1) as? Int
                    if (first != null && amount != null) {
                        val have = manager.stateProvider?.getStat("player", first) as? Int ?: 0
                        applyStat(manager, provider, "player", first, have - amount)
                    }
                }
            }
        }
        // And the other direction: ink asks the game. roll has a side effect (the dice), so it is not lookahead safe.
        manager.bindFunction("inventory_count", lookaheadSafe = true) { args -> inventory[args.string(0)] ?: 0 }
        manager.bindFunction("player_name", lookaheadSafe = true) { "Wren" }
        manager.bindFunction("roll") { args -> fixedRoll ?: rng.nextInt(1, args.int(0) + 1) }
        syncPlayer()
        for (name in WATCHED) watch(name)
    }

    /** The raw panel's text, with color markup. */
    val rawText: String get() = raw.text

    /** Every row of the rendered panel's text area, top to bottom. */
    val renderedLabels: List<Label> get() = rendered.labels

    /** The choice buttons on screen, top to bottom. */
    val choiceButtons: List<TextButton> get() = rendered.choiceButtons

    /** How far the raw panel is scrolled, and how far it can go. */
    val rawScrollY: Float get() = raw.scrollY
    val rawMaxScrollY: Float get() = raw.maxScrollY

    /** Which panels are on the stage. */
    val rawVisible: Boolean get() = raw.hasParent()
    val renderedVisible: Boolean get() = rendered.hasParent()

    /** The latest step as the engine gave it. */
    val currentStep: DialogStep? get() = lastStep

    /** Runs a console command line exactly as if it was typed into the console. */
    fun runCommand(line: String) = console.execCommand(line)

    override fun show() {
        // console first, so it takes keys when open; then the layout key; then the stage
        val keys = object : KtxInputAdapter {
            override fun keyDown(keycode: Int): Boolean {
                if (keycode != Input.Keys.F2) return false
                setViewMode(null)
                return true
            }
        }
        Gdx.input.inputProcessor = InputMultiplexer(console.inputProcessor, keys, stage)
        if (lastStep == null) advance()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
        console.refresh()
    }

    override fun render(delta: Float) {
        clearScreen(red = 0.1f, green = 0.1f, blue = 0.12f)
        stage.act(delta)
        stage.draw()
        console.draw()
    }

    override fun dispose() {
        for (observation in observations.values) manager.unobserve(observation)
        observations.clear()
        stage.dispose()
        console.dispose()
        mono.dispose()
        skin.dispose()
        consoleSkin.dispose()
    }

    // ---- showing a step ----------------------------------------------------------------------------------------

    /** Continues the story and shows what comes out. */
    private fun advance() {
        showStep(readNext() ?: return)
    }

    /** [StoryManager.next], with a failure shown on screen (a StoryStepException with its partial step) and null returned. */
    private fun readNext(): DialogStep? = try {
        manager.next()
    } catch (e: StoryStepException) {
        showFailure(e)
        null
    } catch (e: Exception) {
        log.error(e) { "Story error" }
        statusLabel.setText("Story error: ${e.message}")
        say("Story error: ${e.message}")
        null
    }

    /** The engine keeps the lines it built before a failure. Show that partial step with the error in the raw panel. */
    private fun showFailure(e: StoryStepException) {
        log.error(e) { "Story error" }
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val why = root.message ?: root.toString()
        showStep(e.step, "STORY ERROR: ${e.message} / cause: $why")
        // one short line: a long label would widen the whole layout. The raw panel has the full text.
        statusLabel.setText("Story error: " + why.take(110).replace('\n', ' ') + if (why.length > 110) "..." else "")
        restartButton.isVisible = true
        say("Story error: ${e.message}")
    }

    /**
     * Shows [step]. [linesFrom] is where the panels take the lines and the step tags from. It is [step] itself, except
     * after a load: a save holds no lines, so the harness keeps the lines of the step that was saved and takes
     * everything else (choices, open styles, ended, events) from the engine.
     */
    private fun showStep(step: DialogStep, note: String? = null, number: Int = ++stepNumber, linesFrom: DialogStep = step) {
        lastStep = step
        raw.show(step, number, note, linesFrom)
        rendered.show(step, choiceMode, linesFrom)
        statusLabel.setText(if (step.ended) "Story ended." else "")
        restartButton.isVisible = step.ended
    }

    private fun applyViewMode() {
        panelRow.clearChildren()
        when (viewMode) {
            ViewMode.SPLIT -> {
                panelRow.add(raw).grow().uniform().padRight(8f)
                panelRow.add(rendered).grow().uniform()
            }
            ViewMode.RAW -> panelRow.add(raw).grow()
            ViewMode.RENDERED -> panelRow.add(rendered).grow()
        }
        refreshInfo()
    }

    private fun refreshInfo() {
        infoLabel.setText(
            "view: ${viewMode.name.lowercase()} (F2)   choices: ${choiceMode.name.lowercase()}   " +
                watched.entries.joinToString("  ") { "${it.key}=${it.value}" },
        )
    }

    /** Keeps the latest value of an ink variable for the info line, and logs each change to the console. */
    private fun watch(name: String) {
        observe(name)
        watched[name] = show(manager.getVariable(name))
        refreshInfo()
    }

    private fun syncPlayer() {
        manager.syncStatsToInk("player", PLAYER_SYNCED_STATS)
    }

    // ---- DialogControl -----------------------------------------------------------------------------------------

    override fun log(message: String) = say(message)

    override fun listVariables(): List<String> = manager.variableNames

    override fun getVariable(name: String): Any? = manager.getVariable(name)

    override fun setVariable(name: String, value: Any) = manager.setVariable(name, value)

    override fun observe(name: String) {
        if (name in observations) {
            say("Already observing $name")
            return
        }
        try {
            observations[name] = manager.observe(name) { old, new ->
                say("$name: $old -> $new")
                if (name in watched) {
                    watched[name] = show(new)
                    refreshInfo()
                }
            }
            say("Observing $name")
        } catch (e: IllegalArgumentException) {
            say(e.message ?: "Cannot observe $name")
        }
    }

    override fun unobserve(name: String) {
        val observation = observations.remove(name)
        if (observation == null) {
            say("Not observing $name")
        } else {
            manager.unobserve(observation)
            watched.remove(name)
            refreshInfo()
            say("Stopped observing $name")
        }
    }

    override fun setStat(entity: String, stat: String, value: Any) {
        val name = applyStat(manager, provider, entity, stat, value)
        val synced = manager.getVariable(name)
        say(if (synced == null) "$entity.$stat = $value (no ink variable $name)" else "$name = $synced")
    }

    override fun setNpcStat(entity: String, stat: String, value: Any) {
        provider.setStat(entity, stat, value)
        say("$entity.$stat = $value")
    }

    override fun resetStats() {
        provider.loadShowcaseStats()
        syncPlayer()
        say("Stats back to the harness defaults")
    }

    override fun talk(knot: String, speaker: String?) {
        try {
            manager.beginScene(knot, speaker)
        } catch (e: Exception) {
            say("Talk failed: ${e.message}")
            return
        }
        advance()
    }

    override fun seed(value: Int) {
        rng = Random(value)
        try {
            manager.seedRandom(value)
            say("Seeded $value (ink and the game's dice)")
        } catch (e: IncompatibleLibraryException) {
            say("Seed needs INCLUDE hdgife.ink")
        }
    }

    override fun fixRoll(value: Int?) {
        fixedRoll = value
        say(if (value == null) "roll() is random again" else "roll() now always gives $value")
    }

    override fun setNumberFormat(decimals: Int, rounding: Rounding) {
        manager.numberFormat = NumberFormat(decimals, rounding)
        say("Number format: $decimals decimals, $rounding")
    }

    override fun choose(index: Int) {
        val choice = lastStep?.choices?.firstOrNull { it.index == index }
        if (choice == null) {
            say("No choice $index")
            return
        }
        try {
            val step = manager.choose(index)
            showStep(step)
            if (!step.ended) statusLabel.setText("> " + choice.text)
        } catch (e: StoryStepException) {
            showFailure(e)
        } catch (e: Exception) {
            log.error(e) { "Story error" }
            say("Story error: ${e.message}")
        }
    }

    override fun setChoiceMode(mode: ChoiceMode) {
        choiceMode = mode
        lastStep?.let { rendered.showChoices(it, mode) }
        refreshInfo()
        say("Choice mode: ${mode.name.lowercase()}")
    }

    override fun setViewMode(mode: ViewMode?) {
        viewMode = mode ?: ViewMode.entries[(viewMode.ordinal + 1) % ViewMode.entries.size]
        applyViewMode()
        say("View: ${viewMode.name.lowercase()}")
    }

    override fun save() {
        val step = lastStep
        if (step == null) {
            say("Nothing to save yet")
            return
        }
        val json = manager.saveState()
        snapshot = Snapshot(json, step, stepNumber)
        say("Saved ${json.length} chars in memory. Stat values are not part of it.")
    }

    override fun load() {
        val saved = snapshot
        if (saved == null) {
            say("Nothing saved yet")
            return
        }
        try {
            manager.loadState(saved.state)
        } catch (e: Exception) {
            say("Load failed: ${e.message}")
            return
        }
        // The engine brings back the story, the speaker and the open styles. The lines are not part of a save, so the
        // game shows the ones it kept, with the choices and open styles read again from the engine.
        val again = readNext() ?: return
        showStep(again, "loaded save: lines kept by the harness, choices and openStyles read again from the engine", saved.number, linesFrom = saved.step)
        say("Loaded (speaker is now ${manager.speaker ?: "none"})")
    }

    override fun validate() {
        val report = manager.validateStory()
        say("Validation: ${report.errors.size} errors, ${report.warnings.size} warnings, ${report.infos.size} infos")
        for (issue in report.issues) say("  ${issue.severity.name.lowercase()} ${issue.kind.name.lowercase()}: ${issue.message}")
    }

    override fun divert(path: String) {
        try {
            manager.story.choosePathString(path)
        } catch (e: Exception) {
            say("Divert failed: ${e.message}")
            return
        }
        advance()
    }

    override fun reload() {
        KtxAsync.launch {
            try {
                val story = withContext(Dispatchers.IO) { compileHarnessStory(compiler) }
                manager.replaceStory(story)
                syncPlayer()
                restartView()
                say("Reloaded")
            } catch (e: Exception) {
                log.error(e) { "Reload failed" }
                say("Reload failed: ${e.message}")
            }
        }
    }

    override fun restart() {
        manager.reset()
        inventory.clear()
        applyStat(manager, provider, "player", "coin", START_COIN)
        syncPlayer()
        restartView()
    }

    private fun restartView() {
        stepNumber = 0
        lastStep = null
        advance()
    }
}
