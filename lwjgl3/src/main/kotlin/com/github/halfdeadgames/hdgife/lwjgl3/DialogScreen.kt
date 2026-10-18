package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Colors
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.story.ChoiceRequirement
import com.github.halfdeadgames.hdgife.story.DialogLine
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.TextSpan
import com.github.halfdeadgames.hdgife.story.NumberFormat
import com.github.halfdeadgames.hdgife.story.Rounding
import com.github.halfdeadgames.hdgife.story.StoryEvent
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.TextMarker
import com.github.halfdeadgames.hdgife.story.VariableObservation
import com.strongjoshua.console.GUIConsole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ktx.app.KtxScreen
import ktx.app.clearScreen
import ktx.async.KtxAsync
import ktx.log.logger
import ktx.scene2d.Scene2DSkin
import ktx.scene2d.label
import ktx.scene2d.scene2d
import ktx.scene2d.textButton

private val log = logger<DialogScreen>()

/** How this harness treats choices whose requirements are not met. The engine leaves that to the game. */
enum class ChoiceMode { HIDE, GREY, SHOW }

/** Style names this harness draws itself. Everything else is an "effect" that the harness only reports. */
private val DRAWN = setOf("em", "b", "color")

/**
 * Harness-only mapping from engine style names to libGDX color markup. The engine just reports names.
 * `em` and `b` get fixed colors. `color` is resolved here: hex values (`Style.hexColor()` is non-null) become
 * `[#rrggbbaa]`, anything else is looked up as a libGDX named color. Effects (shake, bounce...) are not drawn.
 */
private fun styleColor(span: TextSpan): String? {
    var result: String? = null
    for (style in span.styles) {
        when (style.name) {
            "em" -> result = "#ffd27f"
            "b" -> result = "#ffffff"
            "color" -> {
                val hex = style.hexColor()
                if (hex != null) {
                    result = "#%08x".format(hex)
                } else {
                    val name = style.value?.uppercase()
                    if (name != null && Colors.get(name) != null) result = name
                }
            }
        }
    }
    return result
}

private fun show(value: Any?): String = if (value is Float && value % 1f == 0f) value.toInt().toString() else value.toString()

private fun describeStyle(style: Style) = buildString {
    append(style.name)
    style.value?.let { append('=').append(it) }
    for ((k, v) in style.params) append(' ').append(k).append('=').append(v)
}

private fun describeMarker(marker: TextMarker) = buildString {
    append('<').append(marker.name)
    marker.value?.let { append('=').append(it) }
    for ((k, v) in marker.params) append(' ').append(k).append('=').append(v)
    append("/> at ").append(marker.offset)
}

/** What the harness does not draw: effect spans and point markers. It only reports them. */
private fun effectsNote(line: DialogLine): String? {
    val parts = buildList {
        for (span in line.spans) {
            for (style in span.styles) if (style.name !in DRAWN) add("${describeStyle(style)} on \"${span.text}\"")
        }
        line.markers.mapTo(this, ::describeMarker)
    }
    return if (parts.isEmpty()) null else "fx: " + parts.joinToString("; ")
}

/** Spans to libGDX markup. A literal `[` is doubled so it isn't read as markup. */
private fun toMarkup(spans: List<TextSpan>): String = buildString {
    for (span in spans) {
        val color = styleColor(span)
        if (color != null) append('[').append(color).append(']')
        append(span.text.replace("[", "[["))
        if (color != null) append("[]")
    }
}

private fun describe(r: ChoiceRequirement): String {
    if (!r.valid) return "bad requirement '${r.raw}'"
    val have = r.actual?.let { "have ${show(it)}" } ?: "stat missing"
    val why = if (r.mismatch) ", type mismatch" else ""
    return "${r.entity ?: "?"}.${r.stat} ${r.op?.symbol} ${show(r.target)} ($have$why)"
}

private fun Actor.onChange(block: () -> Unit) {
    addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent?, actor: Actor?) = block()
    })
}

/** Test UI for the ink story: log, choice buttons and a debug console. */
class DialogScreen(
    private val manager: StoryManager,
    private val provider: MapStateProvider,
    private val compiler: InkCompilerService,
) : KtxScreen, DialogControl {
    private val skin = Skin(Gdx.files.internal("ui/uiskin.json"))
    private val consoleSkin = Skin(Gdx.files.classpath("default_skin/uiskin.json"))
    private val stage = Stage(ScreenViewport())
    private val console: GUIConsole
    private val logTable = Table().apply { top().left() }
    private val logPane = ScrollPane(logTable, skin).apply {
        setScrollingDisabled(true, false)
        setFadeScrollBars(false)
    }
    private val choiceTable = Table()
    private val statusLabel: Label
    private val restartButton: TextButton
    private val dim = Color(0.6f, 0.6f, 0.6f, 1f)
    private var scrollPending = false
    private var lastStep: DialogStep? = null
    private var choiceMode = ChoiceMode.GREY
    private var savedState: String? = null
    private val observations = mutableMapOf<String, VariableObservation>()

    /** The harness's own game state, driven by ink: `emit("give_item", x)` adds to it, ink reads it back. */
    private val inventory = mutableMapOf<String, Int>()

    init {
        // ktx-scene2d builders read the default skin
        Scene2DSkin.defaultSkin = skin
        // spans are drawn with libGDX color markup, so every skin font needs it on
        for (font in skin.getAll(BitmapFont::class.java).values()) font.data.markupEnabled = true
        statusLabel = scene2d.label("")
        restartButton = scene2d.textButton("Restart").apply { onChange { restart() } }

        val root = Table().apply {
            setFillParent(true)
            pad(8f)
            add(logPane).grow().row()
            add(choiceTable).growX().padTop(8f).row()
            add(statusLabel).left().padTop(4f).row()
            add(restartButton).left().padTop(4f)
        }
        stage.addActor(root)
        restartButton.isVisible = false

        // the console needs fonts and drawables that uiskin.json lacks, so it gets its bundled skin
        console = GUIConsole(consoleSkin, false, Input.Keys.GRAVE)
        console.setCommandExecutor(DialogCommands(this))
        console.isVisible = false
        console.log("Console ready. Type help.")

        // The engine reports events and never acts on them. This is the harness acting on them.
        manager.onEvent = { event ->
            console.log("event ${event.name}(${event.args.joinToString { show(it) }}) before line ${event.lineIndex}")
            if (event.name == "give_item") {
                val item = event.args.firstOrNull()?.toString()
                if (item != null) inventory.merge(item, 1, Int::plus)
            }
        }
        // And the other direction: ink asks the game. Safe because it only reads.
        manager.bindFunction("inventory_count", lookaheadSafe = true) { args -> inventory[args.string(0)] ?: 0 }
    }

    override fun show() {
        // console first, so it takes keys when open
        Gdx.input.inputProcessor = InputMultiplexer(console.inputProcessor, stage)
        if (logTable.children.size == 0) advance()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
        console.refresh()
    }

    override fun render(delta: Float) {
        clearScreen(red = 0.1f, green = 0.1f, blue = 0.12f)
        stage.act(delta)
        if (scrollPending) {
            logPane.validate()
            logPane.scrollPercentY = 1f
            scrollPending = false
        }
        stage.draw()
        console.draw()
    }

    override fun dispose() {
        stage.dispose()
        console.dispose()
        skin.dispose()
        consoleSkin.dispose()
    }

    private fun addLine(text: String, color: Color? = null) {
        val line = Label(text, skin).apply {
            wrap = true
            if (color != null) this.color.set(color)
        }
        logTable.add(line).growX().padBottom(4f).row()
        scrollPending = true
    }

    /** Continues the story, appends output and rebuilds the choice buttons. */
    private fun advance(first: DialogStep? = null) {
        val step = try {
            first ?: manager.next()
        } catch (e: Exception) {
            log.error(e) { "Story error" }
            addLine("Story error: ${e.message}", Color.SALMON)
            console.log("Story error: ${e.message}")
            return
        }
        showStep(step)
    }

    private fun addDialogLine(line: DialogLine) {
        val prefix = line.speaker?.let { "[#9ecbff]${it.replace("[", "[[")}:[] " } ?: ""
        addLine(prefix + toMarkup(line.spans))
        if (!line.tags.isEmpty) addLine("# " + line.tags.raw.joinToString(", "), dim)
        effectsNote(line)?.let { addLine(it, dim) }
    }

    private fun addEvent(event: StoryEvent) {
        addLine("! event ${event.name}(${event.args.joinToString { show(it) }})", Color.GOLDENROD)
    }

    private fun showStep(step: DialogStep) {
        var next = 0
        for ((index, line) in step.lines.withIndex()) {
            while (next < step.events.size && step.events[next].lineIndex <= index) addEvent(step.events[next++])
            addDialogLine(line)
        }
        while (next < step.events.size) addEvent(step.events[next++])
        if (step.openStyles.isNotEmpty()) addLine("(still open: ${step.openStyles.joinToString { describeStyle(it) }})", dim)
        lastStep = step
        buildChoices(step)
        statusLabel.setText(if (step.ended) "Story ended." else "")
        restartButton.isVisible = step.ended
    }

    /** Harness policy: hide, grey out (with the targets shown) or show unavailable choices. The engine blocks none. */
    private fun buildChoices(step: DialogStep) {
        choiceTable.clearChildren()
        for (choice in step.choices) {
            if (!choice.available && choiceMode == ChoiceMode.HIDE) continue
            val markup = buildString {
                append(toMarkup(choice.spans))
                if (!choice.available) {
                    val needs = choice.requirements.filterNot { it.met }.joinToString("; ", transform = ::describe)
                    append("  [#ff8a80](needs ").append(needs.replace("[", "[[")).append(")[]")
                }
            }
            val button = scene2d.textButton("")
            button.label.setText(markup)
            button.label.wrap = true
            if (!choice.available && choiceMode == ChoiceMode.GREY) button.isDisabled = true
            button.onChange { choose(choice.index) }
            choiceTable.add(button).growX().padBottom(4f).row()
        }
    }

    override fun log(message: String) = console.log(message)

    override fun listVariables(): List<String> = manager.variableNames

    override fun getVariable(name: String): Any? = manager.variable(name)

    override fun setVariable(name: String, value: Any) = manager.setVariable(name, value)

    override fun observe(name: String) {
        if (name in observations) {
            console.log("Already observing $name")
            return
        }
        try {
            observations[name] = manager.observe(name) { old, new -> console.log("$name: $old -> $new") }
            console.log("Observing $name")
        } catch (e: IllegalArgumentException) {
            console.log(e.message ?: "Cannot observe $name")
        }
    }

    override fun unobserve(name: String) {
        val observation = observations.remove(name)
        if (observation == null) {
            console.log("Not observing $name")
        } else {
            manager.unobserve(observation)
            console.log("Stopped observing $name")
        }
    }

    override fun setStat(entity: String, stat: String, value: Any) {
        val name = applyStat(manager, provider, entity, stat, value)
        val synced = manager.variable(name)
        console.log(if (synced == null) "$entity.$stat = $value (no ink variable $name)" else "$name = $synced")
    }

    override fun setNpcStat(entity: String, stat: String, value: Any) {
        provider.setStat(entity, stat, value)
        console.log("$entity.$stat = $value")
    }

    override fun talk(knot: String, speaker: String?) {
        try {
            manager.beginScene(knot, speaker)
        } catch (e: Exception) {
            console.log("Talk failed: ${e.message}")
            return
        }
        advance()
    }

    override fun seed(value: Int) {
        console.log(if (manager.seedRandom(value)) "Seeded $value" else "Seed needs INCLUDE hdgife.ink")
    }

    override fun setNumberFormat(decimals: Int, rounding: Rounding) {
        manager.numberFormat = NumberFormat(decimals, rounding)
        console.log("Number format: $decimals decimals, $rounding")
    }

    override fun choose(index: Int) {
        val choice = lastStep?.choices?.firstOrNull { it.index == index }
        if (choice == null) {
            console.log("No choice $index")
            return
        }
        addLine("> " + choice.text, dim)
        try {
            showStep(manager.choose(index))
        } catch (e: Exception) {
            log.error(e) { "Story error" }
            console.log("Story error: ${e.message}")
        }
    }

    override fun setChoiceMode(mode: ChoiceMode) {
        choiceMode = mode
        lastStep?.let { buildChoices(it) }
        console.log("Choice mode: ${mode.name.lowercase()}")
    }

    override fun save() {
        val json = manager.saveState()
        savedState = json
        console.log("Saved ${json.length} chars in memory. Stat values are not part of it.")
    }

    override fun load() {
        val json = savedState
        if (json == null) {
            console.log("Nothing saved yet")
            return
        }
        try {
            manager.loadState(json)
        } catch (e: Exception) {
            console.log("Load failed: ${e.message}")
            return
        }
        addLine("(state loaded, speaker is now ${manager.speaker ?: "none"})", dim)
        advance()
        console.log("Loaded")
    }

    override fun divert(path: String) {
        try {
            manager.story.choosePathString(path)
        } catch (e: Exception) {
            console.log("Divert failed: ${e.message}")
            return
        }
        advance()
    }

    override fun reload() {
        KtxAsync.launch {
            try {
                val story = withContext(Dispatchers.IO) { compileHarnessStory(compiler) }
                manager.story = story
                resetLog()
                console.log("Reloaded")
            } catch (e: Exception) {
                log.error(e) { "Reload failed" }
                console.log("Reload failed: ${e.message}")
            }
        }
    }

    override fun restart() {
        manager.reset()
        inventory.clear()
        resetLog()
    }

    private fun resetLog() {
        logTable.clearChildren()
        advance()
    }
}
