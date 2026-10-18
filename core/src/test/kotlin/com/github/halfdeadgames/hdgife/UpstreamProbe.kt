package com.github.halfdeadgames.hdgife

import com.bladecoder.ink.runtime.Story

/**
 * Runs plain Blade Ink code. UpstreamCanaryTest loads this class in an isolated class loader, so the Blade Ink
 * classes it sees are the upstream ones, with no hdgife patch applied. Results are strings: `ok:<text>` or `error:<class>`.
 */
object UpstreamProbe {
    @JvmStatic
    fun floorOnFloat(json: String): String = run(json) { }

    @JvmStatic
    fun safeFunctionInString(json: String): String = run(json) { it.bindExternalFunction("f", one(), true) }

    @JvmStatic
    fun unsafeFunctionInString(json: String): String = run(json) { it.bindExternalFunction("f", one(), false) }

    private fun one() = object : Story.ExternalFunction<Any?> {
        override fun call(args: Array<out Any?>?): Any? = 1
    }

    private fun run(json: String, setup: (Story) -> Unit): String =
        try {
            val story = Story(json)
            setup(story)
            "ok:" + story.Continue().trim()
        } catch (e: Throwable) {
            "error:" + e.javaClass.simpleName + " " + e.message
        }
}
