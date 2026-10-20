package com.github.halfdeadgames.hdgife.ktx.scene2d

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.github.halfdeadgames.hdgife.story.Style
import com.github.halfdeadgames.hdgife.story.TextMarker
import com.github.halfdeadgames.hdgife.story.TextSpan
import ktx.scene2d.KWidget
import ktx.scene2d.Scene2dDsl
import ktx.scene2d.actor

@Scene2dDsl
public inline fun <S> KWidget<S>.richDialogLabel(
    font: BitmapFont,
    flashBackground: Drawable,
    spans: List<TextSpan>,
    markers: List<TextMarker> = emptyList(),
    baseColor: Color = Color.WHITE,
    speaker: String? = null,
    speakerColorName: String? = null,
    stillOpen: List<Style> = emptyList(),
    typewriter: Boolean = true,
    init: (@Scene2dDsl RichDialogLabel).(S) -> Unit = {},
): RichDialogLabel {
    val label = RichDialogLabel(font, flashBackground, baseColor, spans, markers, speaker, speakerColorName, stillOpen, typewriter)
    return actor(label, init)
}
