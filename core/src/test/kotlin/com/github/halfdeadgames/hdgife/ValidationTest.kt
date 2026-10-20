package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.IssueKind
import com.github.halfdeadgames.hdgife.story.Severity
import com.github.halfdeadgames.hdgife.story.coreLibraryVersion
import com.github.halfdeadgames.hdgife.story.validateStory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationTest {
    private val withFallback = """
        EXTERNAL give(item)
        ~ give("sword")
        Hi
        -> END
        === function give(item) ===
        ~ return 0
    """.trimIndent()

    private val noFallback = """
        EXTERNAL give(item)
        ~ give("sword")
        Hi
        -> END
    """.trimIndent()

    @Test
    fun `a clean story has no issues`() {
        val report = managerOf("Hello\n-> END").validateStory()
        assertTrue(report.isOk)
        assertEquals(emptyList(), report.issues)
    }

    @Test
    fun `an unbound external with no fallback is an error`() {
        val report = managerOf(noFallback).validateStory()
        val issue = report.of(IssueKind.UnboundExternal).single()
        assertEquals(Severity.Error, issue.severity)
        assertEquals("give", issue.subject)
        assertFalse(report.isOk)
    }

    @Test
    fun `an external that relies on its ink fallback is info`() {
        val report = managerOf(withFallback).validateStory()
        assertEquals(Severity.Info, report.of(IssueKind.ExternalUsesFallback).single().severity)
        assertTrue(report.isOk)
    }

    @Test
    fun `a bound external is not reported`() {
        val manager = managerOf(noFallback)
        manager.bindFunction("give") { 0 }
        assertEquals(emptyList(), manager.validateStory().issues)
    }

    @Test
    fun `fallbacks switched off make an unbound external an error`() {
        val manager = managerOf(withFallback)
        manager.allowExternalFunctionFallbacks = false
        assertEquals(Severity.Error, manager.validateStory().of(IssueKind.UnboundExternal).single().severity)
    }

    @Test
    fun `the standalone function takes the names that will be bound`() {
        val story = compileInk(noFallback)
        assertEquals(Severity.Error, validateStory(story).of(IssueKind.UnboundExternal).single().severity)
        assertTrue(validateStory(story, boundFunctions = setOf("give")).isOk)
    }

    @Test
    fun `library names without the include are a warning`() {
        val story = compileInk("""
            EXTERNAL stat(name)
            Hi {stat("x")}
            -> END
            === function stat(name) ===
            ~ return 1
        """.trimIndent())
        val issue = validateStory(story).of(IssueKind.LibraryWithoutInclude).single()
        assertEquals(Severity.Warning, issue.severity)
        assertEquals("stat", issue.subject)
    }

    @Test
    fun `util names without the util include are a warning, with it they are not`() {
        val own = compileInk("""
            Hi {clamp(5, 0, 3)}
            -> END
            === function clamp(x, lo, hi) ===
            ~ return lo
        """.trimIndent())
        val issue = validateStory(own).of(IssueKind.LibraryWithoutInclude).single()
        assertEquals("clamp", issue.subject)
        assertContains(issue.message, "hdgife_util.ink")
        assertEquals(emptyList(), validateStory(compileInk("INCLUDE hdgife_util.ink\nHi")).of(IssueKind.LibraryWithoutInclude))
        assertEquals(emptyList(), validateStory(compileLib("INCLUDE hdgife_util.ink\nHi")).of(IssueKind.LibraryWithoutInclude))
        val withCore = compileLib("Hi\n-> END\n=== function clamp(x) ===\n~ return x")
        assertEquals("clamp", validateStory(withCore).of(IssueKind.LibraryWithoutInclude).single().subject)
    }

    @Test
    fun `a story that includes the library has no library warning`() {
        assertEquals(emptyList(), managerOf("Hi {stat(\"x\")}\n-> END").validateStory().of(IssueKind.LibraryWithoutInclude))
    }

    @Test
    fun `an old hdgife ink with no version is an error`() {
        val old = compileInk("""
            VAR hdgife_speaker = ""
            EXTERNAL emit1(name, a)
            ~ emit1("x", 1)
            Hi
            -> END
            === function emit1(name, a) ===
            ~ return 0
        """.trimIndent())
        val issue = validateStory(old).of(IssueKind.OldLibrary).single()
        assertEquals(Severity.Error, issue.severity)
        assertEquals("hdgife_version", issue.subject)
        assertContains(issue.message, "out of date")
    }

    @Test
    fun `an hdgife ink of another version is an error whichever way`() {
        for (version in listOf(HdgifeInk.version + 1, HdgifeInk.version - 1).filter { it > 0 }) {
            val other = compileInk("""
                VAR hdgife_speaker = ""
                Hi
                -> END
                === function hdgife_lib_version() ===
                ~ return $version
            """.trimIndent())
            val issue = validateStory(other).of(IssueKind.OldLibrary).single()
            assertEquals(Severity.Error, issue.severity)
            assertContains(issue.message, "version $version")
        }
    }

    @Test
    fun `the library version is read from the story without running it`() {
        val story = compileLib("Hi\n-> END")
        assertEquals(HdgifeInk.version, coreLibraryVersion(story))
        assertEquals(0, coreLibraryVersion(compileInk("Hi")))
    }

    @Test
    fun `the current library reports no old library`() {
        assertEquals(emptyList(), managerOf("Hi\n-> END").validateStory().of(IssueKind.OldLibrary))
    }

    @Test
    fun `observed names that are not declared are errors`() {
        val story = compileInk("VAR hp = 1\nHi\n-> END")
        val issue = validateStory(story, observedVariables = listOf("hp", "mp", "mp")).of(IssueKind.UndeclaredObservedVariable).single()
        assertEquals("mp", issue.subject)
        assertEquals(Severity.Error, issue.severity)
    }

    @Test
    fun `the manager passes its own observers`() {
        val manager = managerOf("VAR hp = 1\nHi\n-> END")
        manager.observe("hp") { _, _ -> }
        assertTrue(manager.validateStory().isOk)
    }

    @Test
    fun `unreadable requirement clauses are found in choices`() {
        val manager = managerOf("""
            Pick
            * [Fine # requires: player.hp >= 3, faction == "rebels"] A -> END
            * [Broken # requires: this is not a clause, hp > 2] B -> END
            * [Also broken # requires: hp ~ 2] C -> END
        """.trimIndent())
        val issues = manager.validateStory().of(IssueKind.UnreadableRequirement)
        assertEquals(listOf("this is not a clause", "hp ~ 2"), issues.map { it.subject })
        assertTrue(issues.all { it.severity == Severity.Warning })
        assertTrue("Broken" in issues[0].message)
    }

    @Test
    fun `a custom requirements tag key is honoured`() {
        val manager = managerOf("Pick\n* [A # needs: nonsense] A -> END")
        assertEquals(emptyList(), manager.validateStory().issues)
        manager.requirementsTagKey = "needs"
        assertEquals(1, manager.validateStory().of(IssueKind.UnreadableRequirement).size)
    }

    @Test
    fun `tags with computed text are not checked`() {
        val manager = managerOf("VAR n = 1\nPick\n* [A # requires: {n} nonsense] A -> END")
        assertEquals(emptyList(), manager.validateStory().of(IssueKind.UnreadableRequirement))
    }

    @Test
    fun `issues come most serious first and the report prints`() {
        val manager = managerOf("EXTERNAL a()\nEXTERNAL b()\n~ b()\n~ a()\nHi\n-> END\n=== function b() ===\n~ return 0")
        val report = manager.validateStory()
        assertEquals(listOf(Severity.Error, Severity.Info), report.issues.map { it.severity })
        assertTrue(report.toString().startsWith("Error: "))
    }

    @Test
    fun `validating does not change the story`() {
        val manager = managerOf("VAR n = 1\nHi {n}\n* [Go] -> END")
        manager.validateStory()
        assertEquals("Hi 1", manager.next().texts().single())
    }
}

class LibraryVersionJsonTest {
    @Test
    fun `the library version is read from a story loaded from compiled JSON`() {
        val loaded = compiler.loadCompiled(compileLib("Hi\n-> END").toJson())
        assertEquals(HdgifeInk.version, coreLibraryVersion(loaded))
        assertEquals(0, coreLibraryVersion(compiler.loadCompiled(compileInk("Hi").toJson())))
    }
}
