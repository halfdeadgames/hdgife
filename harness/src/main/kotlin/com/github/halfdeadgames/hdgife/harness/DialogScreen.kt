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
import ktx.app.KtxInputAdapter
import ktx.app.KtxScreen
import ktx.app.Platform
import ktx.app.clearScreen
import ktx.log.logger
import ktx.scene2d.Scene2DSkin
import ktx.scene2d.label
import ktx.scene2d.scene2d
import ktx.scene2d.textButton
import kotlin.random.Random

private val log = logger<DialogScreen>()

/** Ink variables the harness watches from the start. Their latest value is in the info line under the panels. */
private val WATCHED = listOf("suspicion", "door_open", "player_coin")

/** Where a phone run keeps the story between launches. Written in [DialogScreen.pause], the one place Android promises. */
private const val AUTOSAVE = "autosave.json"

/** Touch layout sizes. The stage counts in dp there; fonts are sized in the same units and made at density pixels. */
private const val BODY_SP = 18f
private const val SMALL_SP = 13f
private const val CONSOLE_SP = 15f

/** The smallest touch target Android guidance asks for. */
private const val TOUCH_DP = 48f

/** Stage width in dp below which a touch screen shows one panel at a time. A phone upright is 320 to 440. */
private const val COMPACT_DP = 600f

/** Space kept at the screen edges, and extra at the bottom where the system gesture bar swipes. */
private const val EDGE_DP = 12f
private const val GESTURE_DP = 8f

/** The console never gets shorter than this, even when a landscape keyboard leaves little room. */
private const val MIN_CONSOLE_DP = 140f

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
    private val fonts: FontSource? = null,
) : KtxScreen, DialogControl {
    private val skin = Skin(Gdx.files.internal("ui/uiskin.json"))
    private val consoleSkin = Skin(Gdx.files.classpath("default_skin/uiskin.json"))
    private val mono: BitmapFont
    // fonts made at the screen's real pixel size (only when a FontSource was given); owned and disposed here
    private val ownedFonts = ArrayList<BitmapFont>()
    // On a phone one pixel is tiny. Dividing by the density (dpi / 160) makes the stage units about dp. On the desktop
    // the stage stays one unit per pixel, as before.
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

    /** The touch way in to what the keyboard does on the desktop: F2 for the layout, backtick for the console. */
    val viewButton: TextButton
    val consoleButton: TextButton
    private val toolbar = Table()
    private val root = Table()
    private val restartCell: Cell<*>

    /** The touch layout's tabs. Not on a desktop, where the View button and F2 do this. */
    private val renderedTab: TextButton
    private val rawTab: TextButton
    private val splitTab: TextButton

    /** Narrow touch screen (a phone upright): one panel at a time. Wide ones (landscape, tablets) can split. */
    private var compact = false

    /** What the user asked for. [viewMode] is what shows: a split does not fit a compact screen, so it falls back to Rendered. */
    private var preferredMode = ViewMode.SPLIT

    /** Soft keyboard height in pixels, as the system reports it, so the console can stay above it. */
    private var keyboardPx = 0

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
        if (fonts != null) {
            // Crisp text at the real pixel size, drawn at 1:1 in the dp-sized stage. The sizes are in sp-like units.
            fun make(sizeDp: Float, monospace: Boolean): BitmapFont = fonts.create(Math.round(sizeDp * uiScale), monospace).apply {
                data.setScale(1f / uiScale)
                setUseIntegerPositions(false) // whole dp is 2 to 4 pixels: rounding to it would space the glyphs unevenly
                data.markupEnabled = true
                ownedFonts += this
            }
            val body = make(BODY_SP, false)
            val small = make(SMALL_SP, false)
            mono = make(SMALL_SP, true)
            for (style in skin.getAll(Label.LabelStyle::class.java).values()) style.font = body
            for (style in skin.getAll(TextButton.TextButtonStyle::class.java).values()) style.font = body
            skin.add(SMALL_STYLE, Label.LabelStyle(small, Color.WHITE))
        } else {
            mono = monoFont(skin, Gdx.files.internal("ui/font-list.fnt"))
            if (mobile) skin.add(SMALL_STYLE, Label.LabelStyle(skin.get(Label.LabelStyle::class.java)))
        }
        if (mobile) styleForTouch()

        raw = RawPanel(skin, mono, mobile)
        rendered = RenderedPanel(skin, mobile, ::choose)
        val small = if (mobile) SMALL_STYLE else "default"
        statusLabel = if (mobile) {
            // an empty status takes no room on a phone, where every row of height counts
            object : Label("", skin) {
                override fun getPrefHeight(): Float = if (text.isEmpty()) 0f else super.getPrefHeight()
            }.apply { wrap = true }
        } else {
            scene2d.label("").apply { wrap = true }
        }
        infoLabel = scene2d.label("", small).apply { wrap = true; color.set(0.62f, 0.62f, 0.62f, 1f) }
        restartButton = scene2d.textButton("Restart").apply { onChange { restart() } }
        viewButton = scene2d.textButton("View").apply { onChange { setViewMode(null) } }
        consoleButton = scene2d.textButton("Console").apply { onChange { toggleConsole() } }
        val tabStyle = if (mobile) TAB_STYLE else "default"
        renderedTab = scene2d.textButton("Rendered", tabStyle).apply { onChange { setViewMode(ViewMode.RENDERED) } }
        rawTab = scene2d.textButton("Raw", tabStyle).apply { onChange { setViewMode(ViewMode.RAW) } }
        splitTab = scene2d.textButton("Split", tabStyle).apply { onChange { setViewMode(ViewMode.SPLIT) } }
        for (tab in listOf(renderedTab, rawTab, splitTab)) tab.setProgrammaticChangeEvents(false)

        if (mobile) {
            consoleButton.style = skin.get(TAB_STYLE, TextButton.TextButtonStyle::class.java)
            restartButton.style = skin.get(CHOICE_STYLE, TextButton.TextButtonStyle::class.java)
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

        // the console needs fonts and drawables that uiskin.json lacks, so it gets its bundled skin
        // (its stage counts in pixels, so on a phone the font is scaled up instead)
        if (mobile && fonts != null) {
            // crisp instead of scaled: the console's stage counts in pixels, so this font is made at its drawn size
            val consoleFont = fonts.create(Math.round(CONSOLE_SP * uiScale), false).also { ownedFonts += it }
            // the console also asks the skin for this font by name when it makes its log lines
            consoleSkin.add("default-font", consoleFont, BitmapFont::class.java)
            for (style in consoleSkin.getAll(Label.LabelStyle::class.java).values()) style.font = consoleFont
            for (style in consoleSkin.getAll(TextButton.TextButtonStyle::class.java).values()) style.font = consoleFont
            for (style in consoleSkin.getAll(TextField.TextFieldStyle::class.java).values()) style.font = consoleFont
            for (style in consoleSkin.getAll(Window.WindowStyle::class.java).values()) style.titleFont = consoleFont
        } else if (mobile) {
            val consoleFont = consoleSkin.getFont("default-font")
            consoleFont.data.setScale(uiScale)
            consoleFont.setUseIntegerPositions(false)
            // scaled up, so smooth it rather than show the pixels
            for (region in consoleFont.regions) region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        // the browser build gets a console that avoids a TeaVM reflection limit, see BrowserConsole
        console = if (Gdx.app.type == Application.ApplicationType.WebGL) {
            BrowserConsole(consoleSkin, Input.Keys.GRAVE)
        } else {
            GUIConsole(consoleSkin, false, Input.Keys.GRAVE)
        }
        // a phone has no Enter key you can count on, so the console gets a Submit button
        if (mobile) {
            console.enableSubmitButton(true)
            // the console hangs from the top, so it needs to know how much of the screen the keyboard takes
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
        console.setCommandExecutor(DialogCommands(this))
        console.isVisible = false
        say(
            if (mobile) "Console ready. Type help. The tabs change the layout."
            else "Console ready. Type help. F2 changes the layout.",
        )

        // the engine's warnings go to the console too, next to wherever the game already sent them
        val engineLog = manager.log
        manager.log = HdgifeLog { message ->
            engineLog?.log(message)
            say("engine: $message")
        }
        manager.formatOverride(::harnessFormatOverride)
        // The engine reports events and never acts on them. This is the harness acting on them.
        manager.onEvent { event ->
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

    /**
     * The touch look: flat, high contrast buttons that are easy to hit. [TAB_STYLE] is a tab (checked is the open one)
     * and also the Console button; [CHOICE_STYLE] is a choice card and also Restart. The desktop keeps the skin's own look.
     */
    private fun styleForTouch() {
        val base = skin.get(TextButton.TextButtonStyle::class.java)
        skin.add(TAB_STYLE, TextButton.TextButtonStyle(base).apply {
            up = skin.tint(0.16f, 0.17f, 0.22f)
            down = skin.tint(0.3f, 0.38f, 0.52f)
            checked = skin.tint(0.2f, 0.4f, 0.66f)
            checkedDown = down
            over = null
            checkedOver = null
            fontColor = Color(0.8f, 0.82f, 0.86f, 1f)
            checkedFontColor = Color.WHITE
        })
        skin.get(CHOICE_STYLE, TextButton.TextButtonStyle::class.java).apply {
            up = skin.tint(0.19f, 0.24f, 0.34f)
            down = skin.tint(0.3f, 0.4f, 0.58f)
            disabled = skin.tint(0.13f, 0.14f, 0.17f)
            over = null
            fontColor = Color.WHITE
        }
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
        if (lastStep == null && !restoreAutosave()) advance()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
        console.refresh()
        val nowCompact = mobile && stage.viewport.worldWidth < COMPACT_DP
        // portrait stacks the two panels, landscape puts them side by side; a narrow touch screen shows one at a time
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

    /**
     * Keeps the layout out of a display cutout (a notch or punch hole) and off the edge where the system gesture
     * bar lives. libGDX reports the cutout insets in pixels; the stage counts in dp.
     */
    private fun applyInsets() {
        val unit = screenViewport.unitsPerPixel
        val g = Gdx.graphics
        root.pad(
            g.safeInsetTop * unit + EDGE_DP,
            g.safeInsetLeft * unit + EDGE_DP,
            g.safeInsetBottom * unit + EDGE_DP + GESTURE_DP,
            g.safeInsetRight * unit + EDGE_DP,
        )
    }

    /**
     * Android calls this when the app goes to the background, and it may kill the process afterwards, so this is where
     * a phone run saves its story. The GL side needs nothing here: when the context is lost and made again, libGDX
     * reloads the managed textures and shaders (the skins, fonts and both stages' batches) by itself. The harness
     * creates no FrameBuffers or Pixmap-backed textures, which would be the things to rebuild in [resume].
     */
    override fun pause() {
        log.info { "pause" }
        if (mobile && lastStep != null) {
            try {
                // write aside and move over, so a process killed in the middle never leaves a half-written save
                val temp = Gdx.files.local("$AUTOSAVE.tmp")
                temp.writeString(manager.saveState(), false, "UTF-8")
                temp.moveTo(Gdx.files.local(AUTOSAVE))
            } catch (e: Exception) {
                log.error(e) { "Autosave failed" }
            }
        }
    }

    override fun resume() {
        log.info { "resume" }
    }

    /** Brings back the story saved by [pause], if there is one. The lines are not part of a save, so only the choices show. */
    private fun restoreAutosave(): Boolean {
        val file = Gdx.files.local(AUTOSAVE)
        if (!mobile || !file.exists()) return false
        try {
            manager.loadState(file.readString("UTF-8"))
        } catch (e: Exception) {
            log.error(e) { "Autosave could not be loaded" }
            file.delete()
            return false
        }
        val step = readNext() ?: return true
        showStep(step, "restored from the autosave: the lines are not part of a save, so only the choices and open styles are shown")
        say("Restored the story saved when the app was last paused. Stat values are not part of it.")
        return true
    }

    /**
     * Console size and place on a phone, in pixels: hanging under the toolbar, and short enough to end above the soft
     * keyboard when that is up (the keyboard's height comes from the observer set in [init]). Inside the cutout insets.
     */
    private fun placeConsole(width: Int, height: Int) {
        val g = Gdx.graphics
        val underToolbar = g.safeInsetTop + ((toolbar.prefHeight + EDGE_DP + 8f) / screenViewport.unitsPerPixel).toInt()
        val minimum = (MIN_CONSOLE_DP * uiScale).toInt()
        // A landscape keyboard leaves little room. Then the console takes the top of the screen, over the toolbar,
        // so that its input line and a few log lines stay above the keys.
        val top = if (keyboardPx > 0 && height - underToolbar - keyboardPx < minimum) g.safeInsetTop else underToolbar
        val consoleHeight = if (keyboardPx > 0) (height - top - keyboardPx).coerceAtLeast(minimum / 2) else height * 45 / 100
        val left = g.safeInsetLeft
        console.setSize(width - left - g.safeInsetRight, consoleHeight)
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
        for (observation in observations.values) manager.unobserve(observation)
        observations.clear()
        stage.dispose()
        console.dispose()
        mono.dispose()
        for (font in ownedFonts) if (font !== mono) font.dispose()
        ownedFonts.clear()
        fonts?.dispose()
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
        setRestartVisible(true)
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
        statusLabel.setText(if (step.isEnded) "Story ended." else "")
        setRestartVisible(step.isEnded)
    }

    /** Whether the panels are stacked (portrait) or side by side. Set by [applyViewMode]. */
    private var stacked = false

    /** The toolbar's buttons. Touch: Rendered / Raw (/ Split when the screen is wide) tabs and Console. Desktop: View and Console. */
    private fun layoutToolbar() {
        toolbar.clearChildren()
        if (!mobile) {
            toolbar.add(viewButton).minWidth(96f).minHeight(24f).padRight(8f)
            toolbar.add(consoleButton).minWidth(96f).minHeight(24f)
            return
        }
        // every touch target is at least 48dp; the tabs share the width, so the toolbar stays on one row
        val tabs = if (compact) listOf(renderedTab, rawTab) else listOf(renderedTab, rawTab, splitTab)
        for (tab in tabs) toolbar.add(tab).growX().uniformX().minHeight(TOUCH_DP).padRight(6f)
        toolbar.add(consoleButton).minWidth(TOUCH_DP * 2).minHeight(TOUCH_DP)
        renderedTab.isChecked = viewMode == ViewMode.RENDERED
        rawTab.isChecked = viewMode == ViewMode.RAW
        splitTab.isChecked = viewMode == ViewMode.SPLIT
    }

    private fun applyViewMode() {
        viewMode = if (compact && preferredMode == ViewMode.SPLIT) ViewMode.RENDERED else preferredMode
        layoutToolbar()
        panelRow.clearChildren()
        stacked = isPortrait()
        when (viewMode) {
            ViewMode.SPLIT -> if (stacked) {
                panelRow.add(raw).grow().uniform().padBottom(8f).row()
                panelRow.add(rendered).grow().uniform()
            } else if (mobile) {
                // landscape phone: the story on the left where it is read, the engine's view next to it
                panelRow.add(rendered).grow().uniform().padRight(8f)
                panelRow.add(raw).grow().uniform()
            } else {
                panelRow.add(raw).grow().uniform().padRight(8f)
                panelRow.add(rendered).grow().uniform()
            }
            ViewMode.RAW -> panelRow.add(raw).grow()
            ViewMode.RENDERED -> panelRow.add(rendered).grow()
        }
        refreshInfo()
    }

    /** Shows or hides Restart. On a phone it is a full-width button, so hiding it also gives its row back. */
    private fun setRestartVisible(visible: Boolean) {
        restartButton.isVisible = visible
        if (mobile) {
            restartCell.minHeight(if (visible) TOUCH_DP + 8f else 0f).height(if (visible) TOUCH_DP + 8f else 0f)
            restartCell.padTop(if (visible) 6f else 0f)
            root.invalidateHierarchy()
        }
    }

    private fun refreshInfo() {
        infoLabel.setText(
            (if (mobile) "" else "view: ${viewMode.name.lowercase()} (F2)   ") + "choices: ${choiceMode.name.lowercase()}   " +
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
        manager.syncStats("player", PLAYER_SYNCED_STATS)
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
            if (!step.isEnded) statusLabel.setText("> " + choice.text)
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
        // cycling skips Split where it does not fit, so one press always changes what is on screen
        var next = mode ?: ViewMode.entries[(viewMode.ordinal + 1) % ViewMode.entries.size]
        if (mode == null && compact && next == ViewMode.SPLIT) next = ViewMode.entries[(next.ordinal + 1) % ViewMode.entries.size]
        preferredMode = next
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
        runInBackground({ compileHarnessStory(compiler) }) { outcome ->
            try {
                manager.replaceStory(outcome.getOrThrow())
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
