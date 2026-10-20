package com.github.halfdeadgames.hdgife.android

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import com.github.halfdeadgames.hdgife.harness.FontSource

class AndroidFonts : FontSource {
    private var ui: FreeTypeFontGenerator? = null
    private var mono: FreeTypeFontGenerator? = null

    override fun create(sizePx: Int, mono: Boolean): BitmapFont {
        val generator = if (mono) {
            this.mono ?: FreeTypeFontGenerator(Gdx.files.internal("ui/RobotoMono.ttf")).also { this.mono = it }
        } else {
            ui ?: FreeTypeFontGenerator(Gdx.files.internal("ui/Roboto.ttf")).also { ui = it }
        }
        val parameter = FreeTypeFontParameter().apply {
            size = sizePx
            characters = FreeTypeFontGenerator.DEFAULT_CHARS + "–—‘’“”…•→←✓"
            minFilter = Texture.TextureFilter.Linear
            magFilter = Texture.TextureFilter.Linear
            hinting = FreeTypeFontGenerator.Hinting.Slight
            incremental = false
        }
        return generator.generateFont(parameter).apply { data.markupEnabled = true }
    }

    override fun dispose() {
        ui?.dispose()
        mono?.dispose()
        ui = null
        mono = null
    }
}
