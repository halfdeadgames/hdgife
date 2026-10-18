package com.github.halfdeadgames.hdgife

import java.net.URL
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Canaries for the Blade Ink 1.3.2 bugs that hdgife patches (InkNativePatch and HdgifeStory). They run the
 * UNPATCHED upstream classes, loaded in a separate class loader, and expect the bugs. If one of these fails,
 * upstream fixed it in the Blade Ink version now in use: revisit the patches and then update or delete the canary.
 */
class UpstreamCanaryTest {
    private fun probe(method: String, json: String): String {
        val probeName = UpstreamProbe::class.java.name
        val urls = listOf(
            com.bladecoder.ink.runtime.Story::class.java, // blade-ink jar
            UpstreamProbe::class.java, // test classes
        ).map { it.protectionDomain.codeSource.location }.toTypedArray()
        return Isolated(urls, UpstreamCanaryTest::class.java.classLoader, probeName).use { loader ->
            loader.loadClass(probeName).getMethod(method, String::class.java).invoke(null, json) as String
        }
    }

    /** Child-first for Blade Ink and the probe, so they don't see hdgife's patches. */
    private class Isolated(urls: Array<URL>, parent: ClassLoader, private val probeName: String) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("com.bladecoder.ink.") && !name.startsWith(probeName)) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val type = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(type)
                return type
            }
        }
    }

    @Test
    fun `canary - upstream FLOOR on a float still throws`() {
        val result = probe("floorOnFloat", compileInk("{FLOOR(2.5)}").toJson())
        assertTrue(
            result.startsWith("error:ClassCastException"),
            "Blade Ink now gives '$result' for FLOOR(2.5). The FLOOR/CEILING/INT cast bug looks fixed upstream: revisit InkNativePatch.",
        )
    }

    private val stringInk = "EXTERNAL f()\n~ temp t = \"x{f()}\"\n{t}\n=== function f() ===\n~ return 1"

    @Test
    fun `canary - upstream rejects a lookahead safe function in a string`() {
        val result = probe("safeFunctionInString", compileInk(stringInk).toJson())
        assertTrue(
            result.startsWith("error:StoryException") && "string generation" in result,
            "Blade Ink now gives '$result' for a safe function in a string. The inverted lookahead guard looks fixed upstream: revisit HdgifeStory.",
        )
    }

    @Test
    fun `canary - upstream lets an unsafe function into a string`() {
        assertEquals(
            "ok:x1",
            probe("unsafeFunctionInString", compileInk(stringInk).toJson()),
            "The inverted lookahead guard looks fixed upstream (an unsafe function is now refused in strings): revisit HdgifeStory.",
        )
    }
}
