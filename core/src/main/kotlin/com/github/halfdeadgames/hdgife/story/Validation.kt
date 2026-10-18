package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Container
import com.bladecoder.ink.runtime.ControlCommand
import com.bladecoder.ink.runtime.Divert
import com.bladecoder.ink.runtime.Story
import com.bladecoder.ink.runtime.StringValue
import com.bladecoder.ink.runtime.Tag

/** How serious a [ValidationIssue] is. */
enum class Severity {
    /** Worth knowing, nothing is wrong. */
    INFO,

    /** Probably a mistake or a risk, but the story runs. */
    WARNING,

    /** The story will fail or lose data when it reaches this. */
    ERROR,
}

/** What a [ValidationIssue] is about, so a game can react to one kind without reading messages. */
enum class IssueKind {
    /** The story calls an `EXTERNAL` with no Kotlin binding and no ink fallback. [Severity.ERROR]. */
    UNBOUND_EXTERNAL,

    /** An `EXTERNAL` with no Kotlin binding runs its ink fallback instead. [Severity.INFO]. */
    EXTERNAL_USES_FALLBACK,

    /** A library name is defined in a story that does not include hdgife.ink, and the manager will take it over. [Severity.WARNING]. */
    LIBRARY_WITHOUT_INCLUDE,

    /** The story includes an out-of-date hdgife.ink. [Severity.ERROR] when events would be lost, else [Severity.WARNING]. */
    OLD_LIBRARY,

    /** A variable that is observed (or meant to be) is not declared in the story. [Severity.ERROR]. */
    UNDECLARED_OBSERVED_VARIABLE,

    /** A requirement tag has a clause the engine cannot read. [Severity.WARNING]. */
    UNREADABLE_REQUIREMENT,
}

/** One finding of [validateStory]. [subject] is the name or text it is about (a function, a variable, a clause). */
data class ValidationIssue(val severity: Severity, val kind: IssueKind, val subject: String, val message: String)

/** The result of [validateStory]: every finding, most serious first. */
data class StoryReport(val issues: List<ValidationIssue>) {
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.WARNING }
    val infos: List<ValidationIssue> get() = issues.filter { it.severity == Severity.INFO }

    /** True when there are no errors. Warnings and infos do not count. */
    val ok: Boolean get() = errors.isEmpty()

    fun of(kind: IssueKind): List<ValidationIssue> = issues.filter { it.kind == kind }

    override fun toString(): String =
        if (issues.isEmpty()) "No issues" else issues.joinToString("\n") { "${it.severity}: ${it.message}" }
}

private val LIBRARY_FUNCTIONS = listOf(
    "stat", "stat_of", "has_stat", "has_stat_of", "stat_text", "stat_text_of", "fmt",
    "hdgife_emit0", "hdgife_emit1", "hdgife_emit2", "hdgife_emit3",
)

/**
 * Checks [story] and reports what is wrong or risky, without throwing. It reads the compiled story and its
 * variables only. Nothing is run, so it does not change the story, and it never calls your bindings.
 *
 * What it covers:
 * - `EXTERNAL`s the story calls that nothing binds: an error without an ink fallback (or with fallbacks switched
 *   off, see `Story.allowExternalFunctionFallbacks`), an info when the fallback will run. A function counts as bound
 *   when [story] has a binding for it or its name is in [boundFunctions]. An `EXTERNAL` that is declared but never
 *   called is not in the compiled story and is not seen.
 * - Library names (`stat`, `fmt`, `has_stat_of`, ...) that the story defines itself without `INCLUDE hdgife.ink`. The
 *   manager binds those names to its own functions and replaces yours, so include the library or rename them.
 * - An old hdgife.ink: no `hdgife_event_seq` (events would be lost, `StoryManager` refuses such a story), or no
 *   `has_stat_of`.
 * - [observedVariables] that the story does not declare.
 * - Clauses in `# requires:` tags (the key is [requirementsTagKey]) that the engine cannot read. This is a best-effort
 *   scan of the tags written literally in the ink source. Tags with `{...}` inside, or added at runtime, are not seen,
 *   and whether the entity or stat exists is only known when the game runs, so it is not checked.
 */
fun validateStory(
    story: Story,
    boundFunctions: Set<String> = emptySet(),
    observedVariables: Collection<String> = emptyList(),
    requirementsTagKey: String = "requires",
): StoryReport {
    val issues = ArrayList<ValidationIssue>()
    val variables = story.variablesState
    val includesLibrary = variables.get("hdgife_speaker") != null

    val externals = LinkedHashSet<String>()
    val requirementTags = ArrayList<Pair<String, String>>()
    scan(story.mainContentContainer, externals, requirementTags, requirementsTagKey)

    for (name in externals) {
        if (name in boundFunctions || story.getExternalFunction(name) != null) continue
        val hasFallback = story.hasFunction(name)
        if (hasFallback && story.allowExternalFunctionFallbacks()) {
            issues += ValidationIssue(Severity.INFO, IssueKind.EXTERNAL_USES_FALLBACK, name, "$name() has no binding, so its ink fallback runs")
        } else if (hasFallback) {
            issues += ValidationIssue(Severity.ERROR, IssueKind.UNBOUND_EXTERNAL, name, "$name() has no binding and external function fallbacks are switched off")
        } else {
            issues += ValidationIssue(Severity.ERROR, IssueKind.UNBOUND_EXTERNAL, name, "$name() has no binding and no ink fallback, so ink fails when it is called")
        }
    }

    if (!includesLibrary) {
        for (name in LIBRARY_FUNCTIONS) {
            if (story.hasFunction(name)) {
                issues += ValidationIssue(
                    Severity.WARNING, IssueKind.LIBRARY_WITHOUT_INCLUDE, name,
                    "$name is a library name, but the story does not INCLUDE hdgife.ink; a StoryManager replaces your $name with its own",
                )
            }
        }
    } else {
        val hasEmit = story.hasFunction("emit") || story.hasFunction("emit0")
        if (hasEmit && variables.get("hdgife_event_seq") == null) {
            issues += ValidationIssue(
                Severity.ERROR, IssueKind.OLD_LIBRARY, "hdgife_event_seq",
                "hdgife.ink is out of date (no hdgife_event_seq), so emit events would be lost; recompile with the current hdgife.ink",
            )
        } else if (!story.hasFunction("has_stat_of")) {
            issues += ValidationIssue(
                Severity.WARNING, IssueKind.OLD_LIBRARY, "has_stat_of",
                "hdgife.ink is out of date (no has_stat_of); recompile with the current hdgife.ink",
            )
        }
    }

    for (name in observedVariables.distinct()) {
        if (variables.get(name) == null) {
            issues += ValidationIssue(Severity.ERROR, IssueKind.UNDECLARED_OBSERVED_VARIABLE, name, "Observed variable '$name' is not declared in the story")
        }
    }

    for ((tag, context) in requirementTags) {
        val value = Tags.splitTag(tag.trim()).second
        for (part in StoryManager.splitClauses(value)) {
            val clause = part.trim()
            if (clause.isEmpty() || StoryManager.isReadableRequirement(clause)) continue
            val where = if (context.isEmpty()) "" else " (near \"$context\")"
            issues += ValidationIssue(Severity.WARNING, IssueKind.UNREADABLE_REQUIREMENT, clause, "Requirement '$clause' cannot be read$where")
        }
    }

    return StoryReport(issues.sortedByDescending { it.severity })
}

/** Collects the external function names called in [container] and the literal `requires` tags, each with the text just before it. */
private fun scan(container: Container, externals: MutableSet<String>, requirementTags: MutableList<Pair<String, String>>, key: String) {
    val tag = StringBuilder()
    var inTag = false
    var dynamic = false
    var lastText = ""
    for (item in container.content) {
        when {
            item is Container -> scan(item, externals, requirementTags, key)
            item is Divert -> if (item.isExternal) item.targetPathString?.let { externals += it }
            item is Tag -> collect(item.text, lastText, requirementTags, key)
            item is ControlCommand && item.commandType == ControlCommand.CommandType.BeginTag -> {
                inTag = true
                dynamic = false
                tag.setLength(0)
            }
            item is ControlCommand && item.commandType == ControlCommand.CommandType.EndTag -> {
                if (inTag && !dynamic) collect(tag.toString(), lastText, requirementTags, key)
                inTag = false
            }
            item is StringValue -> if (inTag) tag.append(item.value) else if (item.isNonWhitespace) lastText = item.value.trim()
            inTag -> dynamic = true // a value or a call inside the tag: it is only known at run time
        }
    }
    container.namedOnlyContent?.values?.forEach { if (it is Container) scan(it, externals, requirementTags, key) }
}

private fun collect(tag: String, context: String, into: MutableList<Pair<String, String>>, key: String) {
    val trimmed = tag.trim()
    if (Tags.splitTag(trimmed).first == key) into += trimmed to context
}
