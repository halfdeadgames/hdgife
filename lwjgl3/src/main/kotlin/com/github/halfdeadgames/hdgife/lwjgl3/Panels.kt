package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.github.halfdeadgames.hdgife.story.DialogLine
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.StoryEvent
import ktx.scene2d.scene2d
import ktx.scene2d.textButton

/** How the two panels are laid out. */
enum class ViewMode { SPLIT, RAW, RENDERED }

/** How this harness treats choices whose requirements are not met. The engine leaves that to the game. */
enum class ChoiceMode { HIDE, GREY, SHOW }

internal const val CHOICE_STYLE = "choice"

private val DIM = Color(0.62f, 0.62f, 0.62f, 1f)

internal fun Actor.onChange(block: () -> Unit) {
    addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent?, actor: Actor?) = block()
    })
}

/** The mouse wheel scrolls this pane while the pointer is over it. scene2d does not do that by itself. */
internal fun ScrollPane.scrollOnHover() {
    addListener(object : InputListener() {
        override fun enter(event: InputEvent?, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
            if (pointer == -1) stage?.scrollFocus = this@scrollOnHover
        }

        override fun exit(event: InputEvent?, x: Float, y: Float, pointer: Int, toActor: Actor?) {
            if (pointer == -1 && stage?.scrollFocus === this@scrollOnHover) stage?.scrollFocus = null
        }
    })
}

/**
 * A monospace-ish copy of the skin's small font: the same glyphs, with the printable ASCII characters forced to
 * one width (not the very wide ones). It shares the skin's atlas texture (so it does not own it) and the caller disposes the font.
 */
internal fun monoFont(skin: Skin, file: com.badlogic.gdx.files.FileHandle): BitmapFont {
    val data = BitmapFont.BitmapFontData(file, false)
    data.markupEnabled = true
    return BitmapFont(data, skin.getRegion("font-list"), true).apply {
        // The widest letters are left out, or every character would be as wide as an M. They keep their own width.
        setFixedWidthGlyphs(String(CharArray(95) { (32 + it).toChar() }.filter { it !in "mwMW@%&" }.toCharArray()))
    }
}

/** Left panel: the step exactly as the engine gave it, scrollable, in a small monospace-ish font. */
class RawPanel(skin: Skin, font: BitmapFont) : Table() {
    private val title = Label("RAW  the engine's DialogStep", skin)
    private val body = Label("", Label.LabelStyle(font, Color.LIGHT_GRAY)).apply { wrap = true }
    private val bodyTable = Table().apply { top().left().pad(0f, 4f, 0f, 4f); add(body).growX() }
    private val pane = ScrollPane(bodyTable, skin).apply {
        setScrollingDisabled(true, false)
        setFadeScrollBars(false)
        scrollOnHover()
    }

    init {
        add(title).left().padBottom(4f).row()
        add(pane).grow()
    }

    /** The text on screen, with color markup. */
    val text: String get() = body.text.toString()

    /** Scroll position and its limit, so the UI test can check that the panel scrolls. */
    val scrollY: Float get() = pane.scrollY
    val maxScrollY: Float get() = pane.maxY

    fun show(step: DialogStep, number: Int, note: String?) {
        body.setText(formatRaw(step, number, note))
        pane.validate()
        pane.scrollY = 0f
        pane.updateVisualScroll()
    }
}

/**
 * Right panel: the step as a game might show it. Speaker names in a color, spans colored through libGDX's `Colors`
 * table (names) or `Style.hexColor()` (hex), effects annotated (and a short shake or bob on the line), point
 * markers written where they sit, events as gold notes where they happened, and choice buttons that follow
 * the [ChoiceMode]. The test reads it through [labels] and [choiceButtons].
 */
class RenderedPanel(private val skin: Skin, private val onChoose: (Int) -> Unit) : Table() {
    private val title = Label("RENDERED  what a player would see", skin)
    private val lineTable = Table().apply { top().left().pad(0f, 4f, 0f, 4f) }
    private val pane = ScrollPane(lineTable, skin).apply {
        setScrollingDisabled(true, false)
        setFadeScrollBars(false)
        scrollOnHover()
    }
    private val choiceTable = Table()
    private val speakers = SpeakerColors()

    init {
        add(title).left().padBottom(4f).row()
        add(pane).grow().row()
        add(choiceTable).growX().padTop(8f)
    }

    /** Every row of the text area, top to bottom: lines, event notes, tags and the open-style note. */
    val labels: List<Label> get() = lineTable.children.filterIsInstance<Label>()

    /** The choice buttons on screen, top to bottom. */
    val choiceButtons: List<TextButton> get() = choiceTable.children.filterIsInstance<TextButton>()

    private fun addRow(markup: String, color: Color? = null): Label {
        val label = Label(markup, skin).apply {
            wrap = true
            if (color != null) this.color.set(color)
        }
        lineTable.add(label).growX().padBottom(4f).row()
        return label
    }

    fun show(step: DialogStep, mode: ChoiceMode) {
        lineTable.clearChildren()
        var next = 0
        for ((index, line) in step.lines.withIndex()) {
            while (next < step.events.size && step.events[next].lineIndex <= index) addEvent(step.events[next++])
            addLine(line)
        }
        while (next < step.events.size) addEvent(step.events[next++])
        if (step.openStyles.isNotEmpty()) {
            addRow("(style still open: ${esc(step.openStyles.joinToString { describeStyle(it) })})", DIM)
        }
        pane.validate()
        pane.scrollY = 0f
        pane.updateVisualScroll()
        showChoices(step, mode)
    }

    private fun addLine(line: DialogLine) {
        val label = addRow(lineMarkup(line, speakers))
        // a short motion for the effects a harness can fake cheaply; the dim ~name after the span is the annotation
        when {
            hasEffect(line.spans, SHAKES) -> label.addAction(
                Actions.repeat(10, Actions.sequence(Actions.moveBy(2f, 0f, 0.04f), Actions.moveBy(-2f, 0f, 0.04f))),
            )
            hasEffect(line.spans, BOBS) -> label.addAction(
                Actions.repeat(6, Actions.sequence(Actions.moveBy(0f, 2f, 0.06f), Actions.moveBy(0f, -2f, 0.06f))),
            )
        }
        if (!line.tags.isEmpty) addRow(esc(line.tags.raw.joinToString("  ") { "# $it" }), DIM)
    }

    private fun addEvent(event: StoryEvent) {
        addRow("! event ${esc(event.name)}(${esc(event.args.joinToString { show(it) })})", Color.GOLDENROD)
    }

    /** Harness policy: hide, grey out (with the targets shown) or show unavailable choices. The engine blocks none. */
    fun showChoices(step: DialogStep, mode: ChoiceMode) {
        choiceTable.clearChildren()
        for (choice in step.choices) {
            if (!choice.available && mode == ChoiceMode.HIDE) continue
            val button = scene2d.textButton("", CHOICE_STYLE)
            button.label.setText(choiceMarkup(choice))
            button.label.wrap = true
            if (!choice.available && mode == ChoiceMode.GREY) button.isDisabled = true
            button.onChange { onChoose(choice.index) }
            choiceTable.add(button).growX().padBottom(4f).row()
        }
    }
}
