package com.github.halfdeadgames.hdgife.ktx

import ktx.log.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StoryLoggingTest {
    private val application = RecordedApplication()

    @BeforeTest
    fun installApplication() = application.install()

    @AfterTest
    fun uninstallApplication() = application.uninstall()

    @Test
    fun `ktxHdgifeLog writes hdgife messages through the ktx logger`() {
        ktxHdgifeLog().log("Style: <b> never closed")
        assertEquals(listOf("INFO: hdgife - Style: <b> never closed"), application.logged)
    }

    @Test
    fun `ktxHdgifeLog uses the logger it is given`() {
        ktxHdgifeLog(Logger("story")).log("hello")
        assertEquals(listOf("INFO: story - hello"), application.logged)
    }
}
