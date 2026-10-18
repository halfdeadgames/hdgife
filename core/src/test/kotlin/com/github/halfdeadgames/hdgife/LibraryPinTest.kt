package com.github.halfdeadgames.hdgife

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryPinTest {
    private fun signatures(resource: String): List<String> {
        val text = checkNotNull(javaClass.getResourceAsStream("/hdgife/$resource")) { "missing $resource" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val pattern = Regex("""^(?:={2,}\s*function|EXTERNAL)\s+(\w+)\s*\(([^)]*)\)""")
        return text.lines().mapNotNull { line ->
            val m = pattern.find(line) ?: return@mapNotNull null
            val params = m.groupValues[2].trim()
            val arity = if (params.isEmpty()) 0 else params.split(',').size
            "${m.groupValues[1]}/$arity"
        }
    }

    private fun currentHash(): String {
        val all = (signatures("hdgife.ink") + signatures("hdgife_util.ink")).sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest(all.joinToString("\n").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** The hash of the sorted function names and arities for each library version. Add a line when you bump the version. */
    private val pinned = mapOf(
        1 to "06de8d3f1e6320e55080dce5aab9f51aa5efa5c266542b245b2f8c2aa9bd5bc9",
    )

    @Test
    fun `library functions are pinned to hdgife_version`() {
        assertEquals(
            pinned[HdgifeInk.VERSION],
            currentHash(),
            "library changed: bump hdgife_version (in hdgife.ink) and pin the new hash for it in LibraryPinTest",
        )
    }
}
