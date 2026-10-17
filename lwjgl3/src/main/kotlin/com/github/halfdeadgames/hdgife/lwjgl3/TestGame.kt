package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter.Linear
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.app.clearScreen
import ktx.assets.disposeSafely
import ktx.assets.toInternalFile
import kotlinx.coroutines.launch
import ktx.async.KtxAsync
import ktx.graphics.use
import ktx.log.logger
import com.badlogic.gdx.Gdx
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.state.NarrativeStateProvider
import com.github.halfdeadgames.hdgife.story.StoryManager

class TestGame : KtxGame<KtxScreen>() {
    override fun create() {
        KtxAsync.initiate()
        smokeTestInk()

        addScreen(FirstScreen())
        setScreen<FirstScreen>()
    }
}

class FirstScreen : KtxScreen {
    private val image = Texture("logo.png".toInternalFile(), true).apply { setFilter(Linear, Linear) }
    private val batch = SpriteBatch()

    override fun render(delta: Float) {
        clearScreen(red = 0.7f, green = 0.7f, blue = 0.7f)
        batch.use {
            it.draw(image, 100f, 160f)
        }
    }

    override fun dispose() {
        image.disposeSafely()
        batch.disposeSafely()
    }
}

private val log = logger<TestGame>()

private fun smokeTestInk() {
    val provider = object : NarrativeStateProvider {
        override fun getStat(entityId: String, statName: String) = 42f
    }
    KtxAsync.launch {
        try {
            val story = InkCompilerService().compile(Gdx.files.internal("ink/main.ink"))
            StoryManager(story, provider).syncStatsToInk("player", listOf("hp"))
            log.info { "Ink output: " + story.continueMaximally().trim() }
        } catch (e: Exception) {
            log.error(e) { "Ink smoke test failed" }
        }
    }
}
