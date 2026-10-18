package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.utils.Disposable

/**
 * Gives the harness crisp fonts at an exact pixel size. A phone screen has two to four times the pixels per inch of
 * a desktop one, so the skin's small bitmap fonts come out tiny and soft there. The Android launcher passes a
 * FreeType implementation; the desktop and web builds pass none and keep the skin's bitmap fonts.
 * The harness owns what [create] returns and disposes it, and disposes the source itself last.
 */
interface FontSource : Disposable {
    /** A font whose glyphs are [sizePx] pixels high: the proportional UI font, or the monospace one for the raw panel. */
    fun create(sizePx: Int, mono: Boolean): BitmapFont
}
