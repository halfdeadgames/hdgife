package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.utils.Disposable

interface FontSource : Disposable {
    fun create(sizePx: Int, mono: Boolean): BitmapFont
}
