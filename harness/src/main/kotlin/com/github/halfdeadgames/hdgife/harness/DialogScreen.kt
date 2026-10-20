package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.Window
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.strongjoshua.console.GUIConsole
import ktx.app.KtxInputAdapter
import ktx.app.KtxScreen
import ktx.app.Platform
import ktx.app.clearScreen
import ktx.log.logger
import ktx.scene2d.Scene2DSkin
import ktx.scene2d.label
import ktx.scene2d.scene2d
import ktx.scene2d.textButton

private val log = logger<DialogScreen>()

private const val bodyFontSp = 18f
private const val smallFontSp = 13f
private const val consoleFontSp = 15f

private const val touchTargetDp = 48f

private const val compactWidthDp = 600f

private const val edgePaddingDp = 12f
private const val gestureInsetDp = 8f

private const val minConsoleHeightDp = 140f

class DialogScreen(
    private val manager: StoryManager,
    private val provider: MapStateProvider,
    private val compiler: InkCompilerService,
    private val fonts: FontSource? = null,
) : KtxScreen, DialogView {
    private val skin = Skin(Gdx.files.internal("ui/uiskin.json"))
    private val consoleSkin = Skin(Gdx.files.classpath("default_skin/uiskin.json"))
    private val mono: BitmapFont
    private val ownedFonts = ArrayList<BitmapFont>()
    private val mobile = Platform.isMobile
    private val uiScale = if (mobile) Gdx.graphics.density.coerceAtLeast(1f) else 1f
    private val screenViewport = ScreenViewport().apply { unitsPerPixel = 1f / uiScale }
    val stage = Stage(screenViewport)
    private val console: GUIConsole
    private val raw: RawPanel
    private val rendered: RenderedPanel
    private val panelRow = Table()
    val statusLabel: Label
    val infoLabel: Label
    val restartButton: TextButton

    val viewButton: TextButton
    val consoleButton: TextButton
    private val toolbar = Table()
    private val root = Table()
    private val restartCell: Cell<*>

    private val renderedTab: TextButton
    private val rawTab: TextButton
    private val splitTab: TextButton

    private var compact = false

    private var preferredMode = ViewMode.Split

    private var keyboardPx = 0

    private var choiceMode = ChoiceMode.Grey
    var viewMode = ViewMode.Split
        private set
    private val controller = DialogController(manager, provider, compiler, this, mobile)

    val consoleLines = ArrayList<String>()

    override fun say(message: String) {
        consoleLines += message
        console.log(message)
    }

    init {
        Scene2DSkin.defaultSkin = skin
        skin.add(choiceStyleName, TextButton.TextButtonStyle(skin.get(TextButton.TextButtonStyle::class.java)).apply {
            disabledFontColor = Color(0.72f, 0.72f, 0.72f, 1f)
        })
        for (font in skin.getAll(BitmapFont::class.java).values()) font.data.markupEnabled = true
        if (fonts != null) {
            fun make(sizeDp: Float, monospace: Boolean): BitmapFont = fonts.create(Math.round(sizeDp * uiScale), monospace).apply {
                data.setScale(1f / uiScale)
                setUseIntegerPositions(false)
                data.markupEnabled = true
                ownedFonts += this
            }
            val body = make(bodyFontSp, false)
            val small = make(smallFontSp, false)
            mono = make(smallFontSp, true)
            for (style in skin.getAll(Label.LabelStyle::class.java).values()) style.font = body
            for (style in skin.getAll(TextButton.TextButtonStyle::class.java).values()) style.font = body
            skin.add(smallStyleName, Label.LabelStyle(small, Color.WHITE))
        } else {
            mono = monoFont(skin, Gdx.files.internal("ui/font-list.fnt"))
            if (mobile) skin.add(smallStyleName, Label.LabelStyle(skin.get(Label.LabelStyle::class.java)))
        }
        if (mobile) styleForTouch()

        raw = RawPanel(skin, mono, mobile)
        rendered = RenderedPanel(skin, mobile, controller::choose)
        val small = if (mobile) smallStyleName else "default"
        statusLabel = if (mobile) {
            object : Label("", skin) {
                override fun getPrefHeight(): Float = if (text.isEmpty()) 0f else super.getPrefHeight()
            }.apply { wrap = true }
        } else {
            scene2d.label("").apply { wrap = true }
        }
        infoLabel = scene2d.label("", small).apply { wrap = true; color.set(0.62f, 0.62f, 0.62f, 1f) }
        restartButton = scene2d.textButton("Restart").apply { onChange { controller.restart() } }
        viewButton = scene2d.textButton("View").apply { onChange { setViewMode(null) } }
        consoleButton = scene2d.textButton("Console").apply { onChange { toggleConsole() } }
        val tabStyle = if (mobile) tabStyleName else "default"
        renderedTab = scene2d.textButton("Rendered", tabStyle).apply { onChange { setViewMode(ViewMode.Rendered) } }
        rawTab = scene2d.textButton("Raw", tabStyle).apply { onChange { setViewMode(ViewMode.Raw) } }
        splitTab = scene2d.textButton("Split", tabStyle).apply { onChange { setViewMode(ViewMode.Split) } }
        for (tab in listOf(renderedTab, rawTab, splitTab)) tab.setProgrammaticChangeEvents(false)

        if (mobile) {
            consoleButton.style = skin.get(tabStyleName, TextButton.TextButtonStyle::class.java)
            restartButton.style = skin.get(choiceStyleName, TextButton.TextButtonStyle::class.java)
        }

        root.setFillParent(true)
        root.pad(8f)
        if (mobile) root.add(toolbar).growX().padBottom(8f).row() else root.add(toolbar).left().padBottom(4f).row()
        root.add(panelRow).grow().row()
        root.add(infoLabel).left().growX().padTop(4f).row()
        root.add(statusLabel).left().growX().padTop(if (mobile) 0f else 2f).row()
        restartCell = if (mobile) root.add(restartButton).growX().padTop(6f) else root.add(restartButton).left().padTop(4f)
        stage.addActor(root)
        setRestartVisible(false)
        applyViewMode()

        if (mobile && fonts != null) {
            val consoleFont = fonts.create(Math.round(consoleFontSp * uiScale), false).also { ownedFonts += it }
            consoleSkin.add("default-font", consoleFont, BitmapFont::class.java)
            for (style in consoleSkin.getAll(Label.LabelStyle::class.java).values()) style.font = consoleFont
            for (style in consoleSkin.getAll(TextButton.TextButtonStyle::class.java).values()) style.font = consoleFont
            for (style in consoleSkin.getAll(TextField.TextFieldStyle::class.java).values()) style.font = consoleFont
            for (style in consoleSkin.getAll(Window.WindowStyle::class.java).values()) style.titleFont = consoleFont
        } else if (mobile) {
            val consoleFont = consoleSkin.getFont("default-font")
            consoleFont.data.setScale(uiScale)
            consoleFont.setUseIntegerPositions(false)
            for (region in consoleFont.regions) region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        console = if (Gdx.app.type == Application.ApplicationType.WebGL) {
            BrowserConsole(consoleSkin, Input.Keys.GRAVE)
        } else {
            GUIConsole(consoleSkin, false, Input.Keys.GRAVE)
        }
        if (mobile) {
            console.enableSubmitButton(true)
            Gdx.input.setKeyboardHeightObserver(object : Input.KeyboardHeightObserver {
                override fun onKeyboardHeightChanged(height: Int) {
                    Gdx.app.postRunnable {
                        keyboardPx = height
                        placeConsole(Gdx.graphics.width, Gdx.graphics.height)
                    }
                }

                override fun onKeyboardShow(height: Int) = Unit
                override fun onKeyboardHide() = Unit
            })
        }
        console.setCommandExecutor(DialogCommands(controller))
        console.isVisible = false
        say(
            if (mobile) "Console ready. Type help. The tabs change the layout."
            else "Console ready. Type help. F2 changes the layout.",
        )

        controller.start()
    }

    private fun styleForTouch() {
        val base = skin.get(TextButton.TextButtonStyle::class.java)
        skin.add(tabStyleName, TextButton.TextButtonStyle(base).apply {
            up = skin.tint(0.16f, 0.17f, 0.22f)
            down = skin.tint(0.3f, 0.38f, 0.52f)
            checked = skin.tint(0.2f, 0.4f, 0.66f)
            checkedDown = down
            over = null
            checkedOver = null
            fontColor = Color(0.8f, 0.82f, 0.86f, 1f)
            checkedFontColor = Color.WHITE
        })
        skin.get(choiceStyleName, TextButton.TextButtonStyle::class.java).apply {
            up = skin.tint(0.19f, 0.24f, 0.34f)
            down = skin.tint(0.3f, 0.4f, 0.58f)
            disabled = skin.tint(0.13f, 0.14f, 0.17f)
            over = null
            fontColor = Color.WHITE
        }
    }

    val rawText: String get() = raw.text

    val renderedLabels: List<Label> get() = rendered.labels

    val choiceButtons: List<TextButton> get() = rendered.choiceButtons

    val rawScrollY: Float get() = raw.scrollY
    val rawMaxScrollY: Float get() = raw.maxScrollY

    val rawVisible: Boolean get() = raw.hasParent()
    val renderedVisible: Boolean get() = rendered.hasParent()

    fun getVariable(name: String): Any? = controller.getVariable(name)

    fun runCommand(line: String) = console.execCommand(line)

    override fun show() {
        val keys = object : KtxInputAdapter {
            override fun keyDown(keycode: Int): Boolean {
                if (keycode != Input.Keys.F2) return false
                setViewMode(null)
                return true
            }
        }
        Gdx.input.inputProcessor = InputMultiplexer(console.inputProcessor, keys, stage)
        controller.begin()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
        console.refresh()
        val nowCompact = mobile && stage.viewport.worldWidth < compactWidthDp
        if (stacked != isPortrait() || compact != nowCompact) {
            compact = nowCompact
            applyViewMode()
        }
        if (mobile) {
            applyInsets()
            rendered.limitChoices(stage.viewport.worldHeight * 0.45f)
            placeConsole(width, height)
        }
    }

    private fun applyInsets() {
        val unit = screenViewport.unitsPerPixel
        val graphics = Gdx.graphics
        root.pad(
            graphics.safeInsetTop * unit + edgePaddingDp,
            graphics.safeInsetLeft * unit + edgePaddingDp,
            graphics.safeInsetBottom * unit + edgePaddingDp + gestureInsetDp,
            graphics.safeInsetRight * unit + edgePaddingDp,
        )
    }

    override fun pause() {
        log.info { "pause" }
        controller.autosave()
    }

    override fun resume() {
        log.info { "resume" }
    }

    private fun placeConsole(width: Int, height: Int) {
        val graphics = Gdx.graphics
        val underToolbar = graphics.safeInsetTop + ((toolbar.prefHeight + edgePaddingDp + 8f) / screenViewport.unitsPerPixel).toInt()
        val minimum = (minConsoleHeightDp * uiScale).toInt()
        val top = if (keyboardPx > 0 && height - underToolbar - keyboardPx < minimum) graphics.safeInsetTop else underToolbar
        val consoleHeight = if (keyboardPx > 0) (height - top - keyboardPx).coerceAtLeast(minimum / 2) else height * 45 / 100
        val left = graphics.safeInsetLeft
        console.setSize(width - left - graphics.safeInsetRight, consoleHeight)
        console.setPosition(left, height - top - consoleHeight)
    }

    private fun toggleConsole() {
        console.isVisible = !console.isVisible
        if (mobile) Gdx.input.setOnscreenKeyboardVisible(console.isVisible)
    }

    private fun isPortrait() = stage.viewport.worldWidth < stage.viewport.worldHeight

    override fun render(delta: Float) {
        clearScreen(red = 0.1f, green = 0.1f, blue = 0.12f)
        stage.act(delta)
        stage.draw()
        console.draw()
    }

    override fun dispose() {
        controller.dispose()
        stage.dispose()
        console.dispose()
        mono.dispose()
        for (font in ownedFonts) if (font !== mono) font.dispose()
        ownedFonts.clear()
        fonts?.dispose()
        skin.dispose()
        consoleSkin.dispose()
    }

    override fun showStep(step: DialogStep, number: Int, note: String?, linesFrom: DialogStep) {
        raw.show(step, number, note, linesFrom)
        rendered.show(step, choiceMode, linesFrom)
        statusLabel.setText(if (step.isEnded) "Story ended." else "")
        setRestartVisible(step.isEnded)
    }

    override fun showFailure(step: DialogStep, number: Int, note: String, status: String) {
        showStep(step, number, note, step)
        statusLabel.setText(status)
        setRestartVisible(true)
    }

    override fun setStatus(text: String) = statusLabel.setText(text)

    private var stacked = false

    private fun layoutToolbar() {
        toolbar.clearChildren()
        if (!mobile) {
            toolbar.add(viewButton).minWidth(96f).minHeight(24f).padRight(8f)
            toolbar.add(consoleButton).minWidth(96f).minHeight(24f)
            return
        }
        val tabs = if (compact) listOf(renderedTab, rawTab) else listOf(renderedTab, rawTab, splitTab)
        for (tab in tabs) toolbar.add(tab).growX().uniformX().minHeight(touchTargetDp).padRight(6f)
        toolbar.add(consoleButton).minWidth(touchTargetDp * 2).minHeight(touchTargetDp)
        renderedTab.isChecked = viewMode == ViewMode.Rendered
        rawTab.isChecked = viewMode == ViewMode.Raw
        splitTab.isChecked = viewMode == ViewMode.Split
    }

    private fun applyViewMode() {
        viewMode = if (compact && preferredMode == ViewMode.Split) ViewMode.Rendered else preferredMode
        layoutToolbar()
        panelRow.clearChildren()
        stacked = isPortrait()
        when (viewMode) {
            ViewMode.Split -> if (stacked) {
                panelRow.add(raw).grow().uniform().padBottom(8f).row()
                panelRow.add(rendered).grow().uniform()
            } else if (mobile) {
                panelRow.add(rendered).grow().uniform().padRight(8f)
                panelRow.add(raw).grow().uniform()
            } else {
                panelRow.add(raw).grow().uniform().padRight(8f)
                panelRow.add(rendered).grow().uniform()
            }
            ViewMode.Raw -> panelRow.add(raw).grow()
            ViewMode.Rendered -> panelRow.add(rendered).grow()
        }
        refreshInfo()
    }

    private fun setRestartVisible(visible: Boolean) {
        restartButton.isVisible = visible
        if (mobile) {
            restartCell.minHeight(if (visible) touchTargetDp + 8f else 0f).height(if (visible) touchTargetDp + 8f else 0f)
            restartCell.padTop(if (visible) 6f else 0f)
            root.invalidateHierarchy()
        }
    }

    override fun refreshInfo() {
        infoLabel.setText(
            (if (mobile) "" else "view: ${viewMode.name.lowercase()} (F2)   ") + "choices: ${choiceMode.name.lowercase()}   " +
                controller.watched.entries.joinToString("  ") { "${it.key}=${it.value}" },
        )
    }

    override fun setChoiceMode(mode: ChoiceMode) {
        choiceMode = mode
        controller.lastStep?.let { rendered.showChoices(it, mode) }
        refreshInfo()
        say("Choice mode: ${mode.name.lowercase()}")
    }

    override fun setViewMode(mode: ViewMode?) {
        var next = mode ?: ViewMode.entries[(viewMode.ordinal + 1) % ViewMode.entries.size]
        if (mode == null && compact && next == ViewMode.Split) next = ViewMode.entries[(next.ordinal + 1) % ViewMode.entries.size]
        preferredMode = next
        applyViewMode()
        say("View: ${viewMode.name.lowercase()}")
    }
}
