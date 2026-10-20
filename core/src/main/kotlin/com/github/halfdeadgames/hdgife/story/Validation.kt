package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Container
import com.bladecoder.ink.runtime.ControlCommand
import com.bladecoder.ink.runtime.Divert
import com.bladecoder.ink.runtime.Story
import com.bladecoder.ink.runtime.StringValue
import com.bladecoder.ink.runtime.Tag
import com.github.halfdeadgames.hdgife.ExperimentalHdgifeApi
import com.github.halfdeadgames.hdgife.HdgifeInk

@ExperimentalHdgifeApi
public enum class Severity {
    Info,
    Warning,
    Error,
}

@ExperimentalHdgifeApi
public enum class IssueKind {
    UnboundExternal,
    ExternalUsesFallback,
    LibraryWithoutInclude,
    OldLibrary,
    UndeclaredObservedVariable,
    UnreadableRequirement,
}

@ExperimentalHdgifeApi
public class ValidationIssue internal constructor(
    public val severity: Severity,
    public val kind: IssueKind,
    public val subject: String,
    public val message: String,
) {
    override fun equals(other: Any?): Boolean = this === other || other is ValidationIssue &&
        severity == other.severity && kind == other.kind && subject == other.subject && message == other.message

    override fun hashCode(): Int = listOf(severity, kind, subject, message).hashCode()

    override fun toString(): String = "ValidationIssue(severity=$severity, kind=$kind, subject=$subject, message=$message)"
}

@ExperimentalHdgifeApi
public class StoryReport internal constructor(public val issues: List<ValidationIssue>) {
    public val errors: List<ValidationIssue> get() = issues.filter { it.severity == Severity.Error }
    public val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.Warning }
    public val infos: List<ValidationIssue> get() = issues.filter { it.severity == Severity.Info }

    public val isOk: Boolean get() = errors.isEmpty()

    public fun of(kind: IssueKind): List<ValidationIssue> = issues.filter { it.kind == kind }

    override fun equals(other: Any?): Boolean = this === other || other is StoryReport && issues == other.issues

    override fun hashCode(): Int = issues.hashCode()

    override fun toString(): String =
        if (issues.isEmpty()) "No issues" else issues.joinToString("\n") { "${it.severity}: ${it.message}" }
}

@ExperimentalHdgifeApi
public object StoryValidator {
    @JvmStatic
    public fun validate(story: Story): StoryReport = validateStory(story)
}

internal fun validateStory(
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
            issues += ValidationIssue(Severity.Info, IssueKind.ExternalUsesFallback, name, "$name() has no binding, so its ink fallback runs")
        } else if (hasFallback) {
            issues += ValidationIssue(Severity.Error, IssueKind.UnboundExternal, name, "$name() has no binding and external function fallbacks are switched off")
        } else {
            issues += ValidationIssue(Severity.Error, IssueKind.UnboundExternal, name, "$name() has no binding and no ink fallback, so ink fails when it is called")
        }
    }

    for ((names, included, file) in listOf(
        Triple(HdgifeInk.coreFunctions, core, "hdgife.ink"),
        Triple(HdgifeInk.utilFunctions, util, "hdgife_util.ink"),
    )) {
        if (included) continue
        for (name in names) {
            if (story.hasFunction(name)) {
                issues += ValidationIssue(
                    Severity.Warning, IssueKind.LibraryWithoutInclude, name,
                    "$name is a reserved library name, but the story does not INCLUDE $file; " +
                        "a StoryManager replaces your external $name with its own, and including the library later would clash",
                )
            }
        }
    }
    libraryProblem(story)?.let {
        issues += ValidationIssue(Severity.Error, IssueKind.OldLibrary, "hdgife_version", it.message.orEmpty())
    }

    for (name in observedVariables.distinct()) {
        if (variables.get(name) == null) {
            issues += ValidationIssue(Severity.Error, IssueKind.UndeclaredObservedVariable, name, "Observed variable '$name' is not declared in the story")
        }
    }

    for ((tag, context) in requirementTags) {
        val value = Tags.splitTag(tag.trim()).second
        for (part in splitClauses(value)) {
            val clause = part.trim()
            if (clause.isEmpty() || isReadableRequirement(clause)) continue
            val where = if (context.isEmpty()) "" else " (near \"$context\")"
            issues += ValidationIssue(Severity.Warning, IssueKind.UnreadableRequirement, clause, "Requirement '$clause' cannot be read$where")
        }
    }

    return StoryReport(issues.sortedByDescending { it.severity })
}

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
            inTag -> dynamic = true
        }
    }
    container.namedOnlyContent?.values?.forEach { if (it is Container) scan(it, externals, requirementTags, key) }
}

private fun collect(tag: String, context: String, into: MutableList<Pair<String, String>>, key: String) {
    val trimmed = tag.trim()
    if (Tags.splitTag(trimmed).first == key) into += trimmed to context
}
