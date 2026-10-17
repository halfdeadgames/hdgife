package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.Gdx
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.story.StoryManager
import kotlinx.coroutines.launch
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.async.KtxAsync
import ktx.log.logger

private val log = logger<TestGame>()

class TestGame : KtxGame<KtxScreen>() {
    override fun create() {
        KtxAsync.initiate()
        val compiler = InkCompilerService()
        val provider = MapStateProvider().apply {
            setStat("guard", "sanity", 80f)
            setStat("guard", "tiredness", 20f)
            setStat("guard", "mood", 60f)
            setStat("guard", "strength", 12f)
        }
        KtxAsync.launch {
            try {
                val story = compiler.compile(Gdx.files.internal("ink/main.ink"))
                val manager = StoryManager(story, provider).apply { speaker = "guard" }
                addScreen(DialogScreen(manager, provider, compiler))
                setScreen<DialogScreen>()
            } catch (e: Exception) {
                log.error(e) { "Ink compile failed" }
            }
        }
    }
}
