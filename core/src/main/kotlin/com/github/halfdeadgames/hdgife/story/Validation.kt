package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Container
import com.bladecoder.ink.runtime.ControlCommand
import com.bladecoder.ink.runtime.Divert
import com.bladecoder.ink.runtime.Story
import com.bladecoder.ink.runtime.StringValue
import com.bladecoder.ink.runtime.Tag
import com.github.halfdeadgames.hdgife.HdgifeInk

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

    /**
     * A reserved library name (see `HdgifeInk.LIBRARY_FUNCTIONS`) is defined in a story that does not include the
     * file it belongs to (hdgife.ink or hdgife_util.ink). [Severity.WARNING].
     */
    LIBRARY_WITHOUT_INCLUDE,

    /** The story includes a hdgife.ink that is not the version of this engine (old, with no version, or newer). [Severity.ERROR]. */
    OLD_LIBRARY,

    /** A variable that is observed (or meant to be) is not declared in the story. [Severity.ERROR]. */
    UNDECLARED_OBSERVED_VARIABLE,

    /** A requirement tag has a clause the engine cannot read. [Severity.WARNING]. */
    UNREADABLE_REQUIREMENT,
}

/** One finding of [validateStory]. [subject] is the name or text it is about (a function, a variable, a clause). */
class ValidationIssue internal constructor(val severity: Severity, val kind: IssueKind, val subject: String, val message: String) {
    override fun equals(other: Any?): Boolean = this === other || other is ValidationIssue &&
        severity == other.severity && kind == other.kind && subject == other.subject && message == other.message

    override fun hashCode(): Int = listOf(severity, kind, subject, message).hashCode()

    override fun toString() = "ValidationIssue(severity=$severity, kind=$kind, subject=$subject, message=$message)"
}

/** The result of [validateStory]: every finding, most serious first. */
class StoryReport internal constructor(val issues: List<ValidationIssue>) {
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.WARNING }
    val infos: List<ValidationIssue> get() = issues.filter { it.severity == Severity.INFO }

    /** True when there are no errors. Warnings and infos do not count. */
    val ok: Boolean get() = errors.isEmpty()

    fun of(kind: IssueKind): List<ValidationIssue> = issues.filter { it.kind == kind }

    override fun equals(other: Any?): Boolean = this === other || other is StoryReport && issues == other.issues

    override fun hashCode(): Int = issues.hashCode()

    override fun toString(): String =
        if (issues.isEmpty()) "No issues" else issues.joinToString("\n") { "${it.severity}: ${it.message}" }
}

/**
 * Checks [story] and reports what is wrong or risky, without throwing. It reads the compiled story and its
 * variables only. Nothing is run, so it does not change the story, and it never calls your bindings.
 *
 * What it covers:
 * - `EXTERNAL`s the story calls that nothing binds: an error without an ink fallback (or with fallbacks switched
 *   off, see `Story.allowExternalFunctionFallbacks`), an info when the fallback will run. A function counts as bound
 *   when [story] has a binding for it or its name is in [boundFunctions]. An `EXTERNAL` that is declared but never
 *   called is not in the compiled story and is not seen.
 * - Reserved library names (`HdgifeInk.LIBRARY_FUNCTIONS`: `stat`, `fmt`, `emit`, `chance`, `clamp`, ...) that the story
 *   defines itself without including the file they belong to (`INCLUDE hdgife.ink` or `INCLUDE hdgife_util.ink`). A
 *   manager binds the external ones to its own functions and replaces yours, so include the library or rename them.
 * - A hdgife.ink of another version than `HdgifeInk.VERSION`, an old one with no version included (counted as 0;
 *   `StoryManager` refuses such a story with an `IncompatibleLibraryException`).
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
    val core = includesCoreLibrary(story)
    val util = includesUtilLibrary(story)

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

    for ((names, included, file) in listOf(
        Triple(HdgifeInk.CORE_FUNCTIONS, core, "hdgife.ink"),
        Triple(HdgifeInk.UTIL_FUNCTIONS, util, "hdgife_util.ink"),
    )) {
        if (included) continue
        for (name in names) {
            if (story.hasFunction(name)) {
                issues += ValidationIssue(
                    Severity.WARNING, IssueKind.LIBRARY_WITHOUT_INCLUDE, name,
                    "$name is a reserved library name, but the story does not INCLUDE $file; " +
                        "a StoryManager replaces your external $name with its own, and including the library later would clash",
                )
            }
        }
    }
    libraryProblem(story)?.let {
        issues += ValidationIssue(Severity.ERROR, IssueKind.OLD_LIBRARY, "hdgife_version", it.message.orEmpty())
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
