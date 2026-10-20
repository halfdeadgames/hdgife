package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.Align
import com.github.halfdeadgames.hdgife.ktx.scene2d.RichDialogLabel
import com.github.halfdeadgames.hdgife.ktx.scene2d.describeStyle
import com.github.halfdeadgames.hdgife.ktx.scene2d.escapeMarkup
import com.github.halfdeadgames.hdgife.story.DialogLine
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.StoryEvent
import com.github.halfdeadgames.hdgife.story.Style
import ktx.scene2d.scene2d
import ktx.scene2d.textButton

enum class ViewMode { Split, Raw, Rendered }

enum class ChoiceMode { Hide, Grey, Show }

internal const val choiceStyleName = "choice"

internal const val tabStyleName = "tab"
internal const val smallStyleName = "small"

internal fun Skin.tint(red: Float, green: Float, blue: Float): Drawable = newDrawable("white", Color(red, green, blue, 1f))

private val dimColor = Color(0.62f, 0.62f, 0.62f, 1f)

internal fun Actor.onChange(block: () -> Unit) {
    addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent?, actor: Actor?) = block()
    })
}

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

internal fun monoFont(skin: Skin, file: com.badlogic.gdx.files.FileHandle): BitmapFont {
    val fontData = BitmapFont.BitmapFontData(file, false)
    fontData.markupEnabled = true
    return BitmapFont(fontData, skin.getRegion("font-list"), true).apply {
        setFixedWidthGlyphs(String(CharArray(95) { (32 + it).toChar() }.filter { it !in "mwMW@%&" }.toCharArray()))
    }
}

class RawPanel(skin: Skin, font: BitmapFont, touch: Boolean = false) : Table() {
    private val title = Label("RAW  the engine's DialogStep", skin)
    private val body = Label("", Label.LabelStyle(font, if (touch) Color(0.84f, 0.85f, 0.88f, 1f) else Color.LIGHT_GRAY)).apply { wrap = true }
    private val bodyTable = Table().apply {
        if (touch) top().left().pad(12f) else top().left().pad(0f, 4f, 0f, 4f)
        add(body).growX()
    }
    private val pane = ScrollPane(bodyTable, skin).apply {
        setScrollingDisabled(true, false)
        setFadeScrollBars(touch)
        setScrollbarsOnTop(touch)
        scrollOnHover()
    }

    init {
        if (touch) {
            background(skin.tint(0.06f, 0.065f, 0.08f))
        } else {
            add(title).left().padBottom(4f).row()
        }
        add(pane).grow()
    }

    val text: String get() = body.text.toString()

    val scrollY: Float get() = pane.scrollY
    val maxScrollY: Float get() = pane.maxY

    fun show(step: DialogStep, number: Int, note: String?, linesFrom: DialogStep = step) {
        body.setText(formatRaw(step, number, note, linesFrom))
        pane.validate()
        pane.scrollY = 0f
        pane.updateVisualScroll()
    }
}

class RenderedPanel(private val skin: Skin, private val touch: Boolean = false, private val onChoose: (Int) -> Unit) : Table() {
    private val title = Label("RENDERED  what a player would see", skin)
    private val lineTable = Table().apply {
        if (touch) {
            top().left().pad(16f)
            background(this@RenderedPanel.skin.tint(0.14f, 0.155f, 0.2f))
        } else {
            top().left().pad(0f, 4f, 0f, 4f)
        }
    }
    private val pane = ScrollPane(lineTable, skin).apply {
        setScrollingDisabled(true, false)
        setFadeScrollBars(touch)
        setScrollbarsOnTop(touch)
        scrollOnHover()
    }
    private val choiceTable = Table()
    private val choicePane = ScrollPane(choiceTable, skin).apply {
        setScrollingDisabled(true, false)
        setFadeScrollBars(true)
        setScrollbarsOnTop(true)
        setOverscroll(false, false)
        scrollOnHover()
    }
    private val choiceCell: Cell<*>
    private val speakers = SpeakerColors()
    private val bodyStyle = skin.get(Label.LabelStyle::class.java)
    private val flashBackground = skin.tint(1f, 1f, 1f)
    private val rows = ArrayList<Actor>()
    private var current = 0

    var typewriter = true

    init {
        if (!touch) add(title).left().padBottom(4f).row()
        add(pane).grow().row()
        choiceCell = add(choicePane).growX().padTop(if (touch) 10f else 8f)
        lineTable.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) = finishReveal()
        })
    }

    fun limitChoices(maxHeight: Float) {
        choiceCell.maxHeight(maxHeight)
        invalidateHierarchy()
    }

    fun scrollToChoice(button: TextButton) {
        choicePane.validate()
        choicePane.scrollTo(button.x, button.y, button.width, button.height)
        choicePane.updateVisualScroll()
        choicePane.invalidate()
        choicePane.validate()
    }

    val rowMarkup: List<String> get() = rows.map { if (it is RichDialogLabel) it.markup else (it as Label).text.toString() }

    val richLines: List<RichDialogLabel> get() = rows.filterIsInstance<RichDialogLabel>()

    val isRevealing: Boolean get() = current < rows.size

    val choiceButtons: List<TextButton> get() = choiceTable.children.filterIsInstance<TextButton>()

    override fun act(delta: Float) {
        super.act(delta)
        val row = rows.getOrNull(current)
        if (row is RichDialogLabel && row.isDone) {
            current++
            revealCurrent()
        }
    }

    fun finishReveal() {
        for (index in current until rows.size) {
            val row = rows[index]
            row.isVisible = true
            if (row is RichDialogLabel) row.skip()
        }
        current = rows.size
    }

    private fun revealCurrent() {
        while (current < rows.size) {
            val row = rows[current]
            row.isVisible = true
            if (row is RichDialogLabel && !row.isDone) {
                row.started = true
                return
            }
            current++
        }
    }

    private fun addRow(markup: String, color: Color? = null): Label {
        val label = (if (touch && color != null) Label(markup, skin, smallStyleName) else Label(markup, skin)).apply {
            wrap = true
            if (color != null) this.color.set(color)
        }
        place(label, color != null)
        return label
    }

    private fun place(actor: Actor, dim: Boolean) {
        lineTable.add(actor).growX().padBottom(if (touch) if (dim) 8f else 14f else 4f).row()
        rows += actor
    }

    fun show(step: DialogStep, mode: ChoiceMode, linesFrom: DialogStep = step) {
        lineTable.clearChildren()
        rows.clear()
        current = 0
        var next = 0
        for ((index, line) in linesFrom.lines.withIndex()) {
            while (next < step.events.size && step.events[next].lineIndex <= index) addEvent(step.events[next++])
            val stillOpen = linesFrom.lines.getOrNull(index + 1)?.spans?.firstOrNull()?.styles ?: step.openStyles
            addLine(line, stillOpen)
        }
        while (next < step.events.size) addEvent(step.events[next++])
        if (step.openStyles.isNotEmpty()) {
            addRow("(style still open: ${escapeMarkup(step.openStyles.joinToString { describeStyle(it) })})", dimColor)
        }
        if (typewriter) {
            for (row in rows) row.isVisible = false
            revealCurrent()
        }
        pane.validate()
        pane.scrollY = 0f
        pane.updateVisualScroll()
        showChoices(step, mode)
    }

    private fun addLine(line: DialogLine, stillOpen: List<Style>) {
        val speakerName = line.speaker
        val colorName = speakerName?.let { speakers.of(it) }
        val rich = RichDialogLabel(
            bodyStyle.font, flashBackground, bodyStyle.fontColor ?: Color.WHITE, line.spans, line.markers,
            speakerName, colorName, stillOpen, typewriter,
        )
        place(rich, false)
        if (!line.tags.isEmpty) addRow(escapeMarkup(line.tags.raw.joinToString("  ") { describeTag(it, line.tags) }), dimColor)
    }

    private fun addEvent(event: StoryEvent) {
        addRow("! event ${escapeMarkup(describeEvent(event))}", Color.GOLDENROD)
    }

    fun showChoices(step: DialogStep, mode: ChoiceMode) {
        choiceTable.clearChildren()
        for (choice in step.choices) {
            if (!choice.isAvailable && mode == ChoiceMode.Hide) continue
            val button = scene2d.textButton("", choiceStyleName)
            val markup = choiceMarkup(choice)
            button.label.setText(markup.replace("  [#ff8a80](needs", "${Char(10)}[#ff8a80](needs").replace("  [#7fbf7f](met", "${Char(10)}[#7fbf7f](met"))
            button.label.wrap = true
            button.labelCell.growX()
            if (touch) {
                button.label.setAlignment(Align.left)
                button.pad(12f, 16f, 12f, 16f)
            }
            if (!choice.isAvailable && mode == ChoiceMode.Grey) button.isDisabled = true
            button.onChange { onChoose(choice.index) }
            choiceTable.add(button).growX().minHeight(if (touch) 56f else 0f).padBottom(if (touch) 8f else 4f).row()
        }
        choicePane.validate()
        choicePane.scrollY = 0f
        choicePane.updateVisualScroll()
    }
}
