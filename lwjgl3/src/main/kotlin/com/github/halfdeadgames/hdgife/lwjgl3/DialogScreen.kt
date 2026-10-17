package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.Color
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
import com.github.halfdeadgames.hdgife.story.DialogChoice
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.NumberFormat
import com.github.halfdeadgames.hdgife.story.Rounding
import com.github.halfdeadgames.hdgife.story.StoryManager
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
    private var lastChoices: List<DialogChoice> = emptyList()

    init {
        // ktx-scene2d builders read the default skin
        Scene2DSkin.defaultSkin = skin
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

    private fun showStep(step: DialogStep) {
        if (step.text.isNotEmpty()) for (paragraph in step.text.lines()) addLine(paragraph)
        for (tag in step.tags) addLine("# $tag", dim)
        lastChoices = step.choices
        choiceTable.clearChildren()
        for (choice in step.choices) {
            val button = scene2d.textButton(choice.text)
            button.label.wrap = true
            button.onChange { choose(choice.index) }
            choiceTable.add(button).growX().padBottom(4f).row()
        }
        statusLabel.setText(if (step.ended) "Story ended." else "")
        restartButton.isVisible = step.ended
    }

    override fun log(message: String) = console.log(message)

    override fun listVariables(): List<String> = manager.story.variablesState.toList()

    override fun getVariable(name: String): Any? = manager.story.variablesState.get(name)

    override fun setVariable(name: String, value: Any) {
        manager.story.variablesState.set(name, value)
    }

    override fun setStat(entity: String, stat: String, value: Float) {
        val name = applyStat(manager, provider, entity, stat, value)
        console.log("$name = ${manager.story.variablesState.get(name)}")
    }

    override fun setNpcStat(entity: String, stat: String, value: Float) {
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
        val choice = lastChoices.firstOrNull { it.index == index }
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
        manager.story.resetState()
        resetLog()
    }

    private fun resetLog() {
        logTable.clearChildren()
        advance()
    }
}
