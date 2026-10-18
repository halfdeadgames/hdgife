package com.github.halfdeadgames.hdgife.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.github.halfdeadgames.hdgife.harness.TestGame

/** Launches the Android application: the same harness as the desktop one. */
class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize(TestGame(fonts = AndroidFonts()), AndroidApplicationConfiguration().apply {
            useImmersiveMode = true // recommended by the libGDX docs, not required
            // the harness has no use for sensors, and not asking for them saves battery
            useAccelerometer = false
            useCompass = false
        })
    }
}
