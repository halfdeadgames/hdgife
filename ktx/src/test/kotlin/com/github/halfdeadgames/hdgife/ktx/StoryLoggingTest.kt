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
    fun `hdgifeLog writes hdgife messages through the ktx logger`() {
        hdgifeLog().log("Style: <b> never closed")
        assertEquals(listOf("INFO: hdgife - Style: <b> never closed"), application.logged)
    }

    @Test
    fun `hdgifeLog uses the logger it is given`() {
        hdgifeLog(Logger("story")).log("hello")
        assertEquals(listOf("INFO: story - hello"), application.logged)
    }
}
