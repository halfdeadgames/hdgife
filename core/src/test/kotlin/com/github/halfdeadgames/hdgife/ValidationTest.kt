package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.IssueKind
import com.github.halfdeadgames.hdgife.story.Severity
import com.github.halfdeadgames.hdgife.story.validateStory
import kotlin.test.Test
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
        assertTrue(report.ok)
        assertEquals(emptyList(), report.issues)
    }

    @Test
    fun `an unbound external with no fallback is an error`() {
        val report = managerOf(noFallback).validateStory()
        val issue = report.of(IssueKind.UNBOUND_EXTERNAL).single()
        assertEquals(Severity.ERROR, issue.severity)
        assertEquals("give", issue.subject)
        assertFalse(report.ok)
    }

    @Test
    fun `an external that relies on its ink fallback is info`() {
        val report = managerOf(withFallback).validateStory()
        assertEquals(Severity.INFO, report.of(IssueKind.EXTERNAL_USES_FALLBACK).single().severity)
        assertTrue(report.ok)
    }

    @Test
    fun `a bound external is not reported`() {
        val m = managerOf(noFallback)
        m.bindFunction("give") { 0 }
        assertEquals(emptyList(), m.validateStory().issues)
    }

    @Test
    fun `fallbacks switched off make an unbound external an error`() {
        val m = managerOf(withFallback)
        m.allowExternalFunctionFallbacks = false
        assertEquals(Severity.ERROR, m.validateStory().of(IssueKind.UNBOUND_EXTERNAL).single().severity)
    }

    @Test
    fun `the standalone function takes the names that will be bound`() {
        val story = compileInk(noFallback)
        assertEquals(Severity.ERROR, validateStory(story).of(IssueKind.UNBOUND_EXTERNAL).single().severity)
        assertTrue(validateStory(story, boundFunctions = setOf("give")).ok)
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
        val issue = validateStory(story).of(IssueKind.LIBRARY_WITHOUT_INCLUDE).single()
        assertEquals(Severity.WARNING, issue.severity)
        assertEquals("stat", issue.subject)
    }

    @Test
    fun `a story that includes the library has no library warning`() {
        assertEquals(emptyList(), managerOf("Hi {stat(\"x\")}\n-> END").validateStory().of(IssueKind.LIBRARY_WITHOUT_INCLUDE))
    }

    @Test
    fun `an old hdgife ink without the event counter is an error`() {
        val old = compileInk("""
            VAR hdgife_speaker = ""
            EXTERNAL emit(name, a)
            ~ emit("x", 1)
            Hi
            -> END
            === function emit(name, a) ===
            ~ return 0
        """.trimIndent())
        val issue = validateStory(old).of(IssueKind.OLD_LIBRARY).single()
        assertEquals(Severity.ERROR, issue.severity)
        assertEquals("hdgife_event_seq", issue.subject)
    }

    @Test
    fun `an hdgife ink from before has_stat_of is a warning`() {
        val old = compileInk("""
            VAR hdgife_speaker = ""
            VAR hdgife_event_seq = 0
            Hi
            -> END
        """.trimIndent())
        val issue = validateStory(old).of(IssueKind.OLD_LIBRARY).single()
        assertEquals(Severity.WARNING, issue.severity)
        assertEquals("has_stat_of", issue.subject)
    }

    @Test
    fun `the current library reports no old library`() {
        assertEquals(emptyList(), managerOf("Hi\n-> END").validateStory().of(IssueKind.OLD_LIBRARY))
    }

    @Test
    fun `observed names that are not declared are errors`() {
        val story = compileInk("VAR hp = 1\nHi\n-> END")
        val issue = validateStory(story, observedVariables = listOf("hp", "mp", "mp")).of(IssueKind.UNDECLARED_OBSERVED_VARIABLE).single()
        assertEquals("mp", issue.subject)
        assertEquals(Severity.ERROR, issue.severity)
    }

    @Test
    fun `the manager passes its own observers`() {
        val m = managerOf("VAR hp = 1\nHi\n-> END")
        m.observe("hp") { _, _ -> }
        assertTrue(m.validateStory().ok)
    }

    @Test
    fun `unreadable requirement clauses are found in choices`() {
        val m = managerOf("""
            Pick
            * [Fine # requires: player.hp >= 3, faction == "rebels"] A -> END
            * [Broken # requires: this is not a clause, hp > 2] B -> END
            * [Also broken # requires: hp ~ 2] C -> END
        """.trimIndent())
        val issues = m.validateStory().of(IssueKind.UNREADABLE_REQUIREMENT)
        assertEquals(listOf("this is not a clause", "hp ~ 2"), issues.map { it.subject })
        assertTrue(issues.all { it.severity == Severity.WARNING })
        assertTrue("Broken" in issues[0].message)
    }

    @Test
    fun `a custom requirements tag key is honoured`() {
        val m = managerOf("Pick\n* [A # needs: nonsense] A -> END")
        assertEquals(emptyList(), m.validateStory().issues)
        m.requirementsTagKey = "needs"
        assertEquals(1, m.validateStory().of(IssueKind.UNREADABLE_REQUIREMENT).size)
    }

    @Test
    fun `tags with computed text are not checked`() {
        val m = managerOf("VAR n = 1\nPick\n* [A # requires: {n} nonsense] A -> END")
        assertEquals(emptyList(), m.validateStory().of(IssueKind.UNREADABLE_REQUIREMENT))
    }

    @Test
    fun `issues come most serious first and the report prints`() {
        val m = managerOf("EXTERNAL a()\nEXTERNAL b()\n~ b()\n~ a()\nHi\n-> END\n=== function b() ===\n~ return 0")
        val report = m.validateStory()
        assertEquals(listOf(Severity.ERROR, Severity.INFO), report.issues.map { it.severity })
        assertTrue(report.toString().startsWith("ERROR: "))
    }

    @Test
    fun `validating does not change the story`() {
        val m = managerOf("VAR n = 1\nHi {n}\n* [Go] -> END")
        m.validateStory()
        assertEquals("Hi 1", m.next().texts().single())
    }
}
