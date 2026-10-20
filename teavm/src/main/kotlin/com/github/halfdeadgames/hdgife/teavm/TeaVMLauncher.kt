package com.github.halfdeadgames.hdgife.teavm

import com.github.halfdeadgames.hdgife.harness.TestGame
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration

object TeaVMLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = WebApplicationConfiguration().apply {
            width = 0
            height = 0
        }
        WebApplication(TestGame(), config)
    }
}
