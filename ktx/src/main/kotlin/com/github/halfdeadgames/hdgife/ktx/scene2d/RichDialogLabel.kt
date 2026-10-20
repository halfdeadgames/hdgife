package com.github.halfdeadgames.hdgife.ktx.scene2d

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Colors
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.IntArray as GdxIntArray
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.TextMarker
import com.github.halfdeadgames.hdgife.story.TextSpan

private const val noEffect: Byte = 0
private const val shakeEffect: Byte = 1
private const val waveEffect: Byte = 2
private const val bounceEffect: Byte = 3

private const val charsPerSecond = 70f
private const val defaultPauseSeconds = 0.5f
private const val flashSeconds = 0.4f
private const val flashAlpha = 0.3f
private const val shakeDegreesPerSecond = 1500f
private const val waveDegreesPerSecond = 360f
private const val bounceDegreesPerSecond = 180f
private const val fallbackWidth = 320f

private val dimColor = Color(0.54f, 0.54f, 0.54f, 1f)

private const val pauseTrigger: Byte = 0
private const val flashTrigger: Byte = 1

public class RichDialogLabel(
    private val font: BitmapFont,
    private val flashBackground: Drawable,
    private val baseColor: Color,
    spans: List<TextSpan>,
    markers: List<TextMarker>,
    speaker: String? = null,
    speakerColorName: String? = null,
    stillOpen: List<Style> = emptyList(),
    typewriter: Boolean = true,
) : Widget() {
    public val markup: String
    public val text: String

    private val chars: CharArray
    private val colors: Array<Color>
    private val effects: ByteArray
    private val amplitudes: FloatArray
    private val speeds: FloatArray
    private val instant: BooleanArray
    private val positions: FloatArray

    private val triggerAt: IntArray
    private val triggerKind: ByteArray
    private val triggerSeconds: FloatArray

    private val lineStarts = GdxIntArray()
    private val tint = Color()
    private val savedFontColor = Color()

    private var laidOutWidth = -1f
    private var laidOutScaleX = 0f
    private var laidOutScaleY = 0f
    private var lineHeight = 0f
    private var nextTrigger = 0
    private var carry = 0f
    private var pauseLeft = 0f
    private var flashLeft = 0f
    private var time = 0f

    public var revealedChars: Int = 0
        private set
    public var started: Boolean = false

    public val totalChars: Int get() = chars.size
    public val isDone: Boolean get() = revealedChars >= chars.size
    public val isPaused: Boolean get() = pauseLeft > 0f
    public val isFlashing: Boolean get() = flashLeft > 0f
    public val animationTime: Float get() = time
    public val hasEffects: Boolean

    init {
        val builtColors = ArrayList<Color>()
        val builtEffects = ArrayList<Byte>()
        val builtAmplitudes = ArrayList<Float>()
        val builtSpeeds = ArrayList<Float>()
        val builtInstant = ArrayList<Boolean>()
        val triggers = ArrayList<Triple<Int, Byte, Float>>()
        val plain = StringBuilder()
        val markupText = StringBuilder(speakerPrefixMarkup(speaker, speakerColorName))

        fun add(runText: String, color: Color, effect: Style?, isInstant: Boolean) {
            val kind = when (effect?.name) {
                "shake" -> shakeEffect
                "wave" -> waveEffect
                "bounce" -> bounceEffect
                else -> noEffect
            }
            val amplitude = effect?.let { numberParam(it, "amp", 0) } ?: 1f
            val speed = effect?.let { numberParam(it, "speed", 1) } ?: 1f
            plain.append(runText)
            repeat(runText.length) {
                builtColors += color
                builtEffects += kind
                builtAmplitudes += amplitude
                builtSpeeds += speed
                builtInstant += isInstant
            }
        }

        if (speaker != null) add("$speaker: ", speakerColorName?.let(Colors::get) ?: baseColor, null, true)
        walkRuns(spans, markers, stillOpen) { kind, runText, color, effect, marker ->
            markupText.appendRunMarkup(kind, runText, color)
            when (kind) {
                RunKind.Text -> add(runText, color?.let(::resolveColor) ?: baseColor, effect, false)
                RunKind.Note -> add(runText, dimColor, null, true)
                RunKind.Marker -> {
                    add(runText, dimColor, null, true)
                    if (marker != null && marker.name == "pause") {
                        triggers += Triple(plain.length, pauseTrigger, pauseSeconds(marker))
                    } else if (marker != null && marker.name in flashMarkerNames) {
                        triggers += Triple(plain.length, flashTrigger, 0f)
                    }
                }
            }
        }
        markup = markupText.toString()
        text = plain.toString()
        chars = text.toCharArray()
        colors = builtColors.toTypedArray()
        effects = builtEffects.toByteArray()
        amplitudes = builtAmplitudes.toFloatArray()
        speeds = builtSpeeds.toFloatArray()
        instant = builtInstant.toBooleanArray()
        positions = FloatArray(chars.size)
        triggerAt = IntArray(triggers.size) { triggers[it].first }
        triggerKind = ByteArray(triggers.size) { triggers[it].second }
        triggerSeconds = FloatArray(triggers.size) { triggers[it].third }
        hasEffects = effects.any { it != noEffect }

        if (typewriter) {
            absorbInstant()
            fireTriggers()
        } else {
            revealedChars = chars.size
            nextTrigger = triggerAt.size
        }
    }

    public fun skip() {
        revealedChars = chars.size
        pauseLeft = 0f
        nextTrigger = triggerAt.size
    }

    override fun act(delta: Float) {
        super.act(delta)
        time += delta
        if (flashLeft > 0f) flashLeft -= delta
        if (!started || isDone) return
        if (pauseLeft > 0f) {
            pauseLeft -= delta
            return
        }
        carry += delta * charsPerSecond
        while (carry >= 1f && !isDone && pauseLeft <= 0f) {
            carry -= 1f
            revealedChars++
            absorbInstant()
            fireTriggers()
        }
    }

    private fun absorbInstant() {
        while (revealedChars < chars.size && instant[revealedChars]) revealedChars++
    }

    private fun fireTriggers() {
        while (nextTrigger < triggerAt.size && revealedChars >= triggerAt[nextTrigger]) {
            if (triggerKind[nextTrigger] == pauseTrigger) {
                pauseLeft = triggerSeconds[nextTrigger]
                carry = 0f
            } else {
                flashLeft = flashSeconds
            }
            nextTrigger++
        }
    }

    override fun getPrefWidth(): Float = 0f

    override fun getPrefHeight(): Float {
        layoutFor(if (width > 0f) width else fallbackWidth)
        return lineStarts.size * lineHeight
    }

    override fun layout() {
        val before = lineStarts.size
        layoutFor(width)
        if (lineStarts.size != before) invalidateHierarchy()
    }

    private fun layoutFor(targetWidth: Float) {
        val data = font.data
        if (targetWidth == laidOutWidth && data.scaleX == laidOutScaleX && data.scaleY == laidOutScaleY) return
        laidOutWidth = targetWidth
        laidOutScaleX = data.scaleX
        laidOutScaleY = data.scaleY
        lineHeight = data.lineHeight
        lineStarts.clear()
        lineStarts.add(0)
        var x = 0f
        var index = 0
        while (index < chars.size) {
            var wordEnd = index
            var wordWidth = 0f
            while (wordEnd < chars.size && chars[wordEnd] != ' ') wordWidth += advance(chars[wordEnd++])
            if (x > 0f && x + wordWidth > targetWidth) {
                lineStarts.add(index)
                x = 0f
            }
            while (index < wordEnd) {
                positions[index] = x
                x += advance(chars[index++])
            }
            if (index < chars.size) {
                positions[index] = x
                x += advance(chars[index++])
            }
        }
    }

    private fun advance(char: Char): Float {
        val data = font.data
        val glyph = data.getGlyph(char) ?: return data.spaceXadvance
        return glyph.xadvance * data.scaleX
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        validate()
        if (flashLeft > 0f) {
            val packed = batch.packedColor
            batch.setColor(1f, 1f, 1f, flashLeft / flashSeconds * flashAlpha * parentAlpha)
            flashBackground.draw(batch, x, y, width, height)
            batch.packedColor = packed
        }
        val data = font.data
        val wasMarkup = data.markupEnabled
        data.markupEnabled = false
        savedFontColor.set(font.color)
        val jitter = if (flashLeft > 0f) flashLeft / flashSeconds * 4f else 0f
        val jitterX = jitter * MathUtils.sinDeg(time * 2400f)
        val jitterY = jitter * MathUtils.cosDeg(time * 1900f)
        val shown = revealedChars
        for (line in 0 until lineStarts.size) {
            val from = lineStarts.get(line)
            val to = minOf(if (line + 1 < lineStarts.size) lineStarts.get(line + 1) else chars.size, shown)
            val top = y + height + font.descent - line * lineHeight + jitterY
            var i = from
            while (i < to) {
                if (chars[i] == ' ') {
                    i++
                    continue
                }
                val color = colors[i]
                if (effects[i] == noEffect) {
                    var j = i + 1
                    while (j < to && colors[j] === color && effects[j] == noEffect) j++
                    drawRange(batch, color, parentAlpha, x + positions[i] + jitterX, top, i, j)
                    i = j
                } else {
                    drawRange(batch, color, parentAlpha, x + positions[i] + jitterX + offsetX(i), top + offsetY(i), i, i + 1)
                    i++
                }
            }
        }
        data.markupEnabled = wasMarkup
        font.color = savedFontColor
    }

    private fun drawRange(batch: Batch, color: Color, parentAlpha: Float, drawX: Float, drawY: Float, from: Int, to: Int) {
        tint.set(color)
        tint.a *= parentAlpha
        font.color = tint
        font.draw(batch, text, drawX, drawY, from, to, 0f, Align.left, false, null)
    }

    private fun offsetX(index: Int): Float {
        if (effects[index] != shakeEffect) return 0f
        return amplitudes[index] * MathUtils.sinDeg(time * speeds[index] * shakeDegreesPerSecond + index * 131f)
    }

    private fun offsetY(index: Int): Float {
        val amplitude = amplitudes[index]
        return when (effects[index]) {
            shakeEffect -> amplitude * MathUtils.sinDeg(time * speeds[index] * shakeDegreesPerSecond * 0.8f + index * 57f)
            waveEffect -> amplitude * MathUtils.sinDeg(time * speeds[index] * waveDegreesPerSecond + index * 40f)
            bounceEffect -> amplitude * Math.abs(MathUtils.sinDeg(time * speeds[index] * bounceDegreesPerSecond + index * 25f))
            else -> 0f
        }
    }
}

private fun numberParam(style: Style, key: String, positional: Int): Float =
    style.param(key)?.toFloatOrNull() ?: style.values.getOrNull(positional)?.toFloatOrNull() ?: 1f

private fun pauseSeconds(marker: TextMarker): Float = marker.value?.toFloatOrNull() ?: defaultPauseSeconds

private fun resolveColor(name: String): Color? {
    if (!name.startsWith("#")) return Colors.get(name)
    return try {
        Color.valueOf(name.substring(1))
    } catch (_: RuntimeException) {
        null
    }
}
