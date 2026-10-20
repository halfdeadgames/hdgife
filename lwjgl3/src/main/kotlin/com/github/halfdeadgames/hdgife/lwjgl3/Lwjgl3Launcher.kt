@file:JvmName("Lwjgl3Launcher")

package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.github.halfdeadgames.hdgife.harness.TestGame

fun main() {
    if (StartupHelper.startNewJvmIfRequired())
      return
    Lwjgl3Application(TestGame(), Lwjgl3ApplicationConfiguration().apply {
        setTitle("hdgife")
        useVsync(true)
        setForegroundFPS(Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate + 1)
        setWindowedMode(1200, 720)
        setWindowIcon(*(arrayOf(128, 64, 32, 16).map { "libgdx$it.png" }.toTypedArray()))
    })
}
