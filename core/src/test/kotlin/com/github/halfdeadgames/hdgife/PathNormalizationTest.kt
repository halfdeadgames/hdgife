package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.compiler.normalizePath
import com.github.halfdeadgames.hdgife.compiler.siblingPath
import kotlin.test.Test
import kotlin.test.assertEquals

class PathNormalizationTest {
    @Test
    fun `an absolute posix path keeps its leading slash`() {
        assertEquals("/tmp/story/main.ink", normalizePath("/tmp/story/./main.ink"))
    }

    @Test
    fun `a relative path stays relative`() {
        assertEquals("story/main.ink", normalizePath("story\\sub\\..\\main.ink"))
    }

    @Test
    fun `a sibling of an absolute path stays absolute`() {
        assertEquals("/tmp/story/other.ink", siblingPath("/tmp/story/main.ink", "other.ink"))
    }

    @Test
    fun `a sibling of a bare file name has no directory`() {
        assertEquals("other.ink", siblingPath("main.ink", "other.ink"))
    }
}
