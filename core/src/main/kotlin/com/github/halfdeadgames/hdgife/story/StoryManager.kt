package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.HdgifeStory
import com.bladecoder.ink.runtime.InkNativePatch
import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.HdgifeLog
import com.github.halfdeadgames.hdgife.state.NarrativeStateProvider
import kotlin.reflect.KClass

private val SPACES = Regex("[ \\t]+")
private val SPACE_BEFORE_PUNCT = Regex(" +([,.;:!?])")

/**
 * The default [StoryManager.textCleaner]: collapses repeated spaces, removes spaces before `, . ; : ! ?` and
 * trims the ends. It is English punctuation. It gets one line of plain text with the style markers already
 * removed, and without the line break.
 */
fun cleanLine(raw: String): String =
    raw.replace(SPACES, " ").replace(SPACE_BEFORE_PUNCT, "$1").trim()

/**
 * Wraps a [Story]: live stat access from ink, a current speaker, clean [DialogStep] output, game-defined ink
 * functions, an event channel and variable access. The engine reports and the game decides: it hides nothing,
 * blocks nothing and renders nothing.
 *
 * ### Stats
 * With `INCLUDE hdgife.ink` in the story, ink can call `stat(name)` (current speaker), `stat_of(entity, name)`
 * and `has_stat(name)`. They read [stateProvider] live. A stat can be any Kotlin value, see
 * [NarrativeStateProvider] for the mapping to ink types. Without a provider (or with a missing stat) `stat`
 * gives 0 and `has_stat` false. These functions are lookahead safe, see "Glue and lookahead" below.
 *
 * ### Speakers
 * [speaker] is the persistent speaker. Ink changes it with `speak("guard")`, a line can name a speaker for itself
 * with a tag (`# speaker: guard`, key set by [speakerTagKey]). A tag speaker applies to that line only.
 * Each [DialogLine] reports the speaker it had. `stat(name)` always reads the persistent speaker.
 *
 * ### Requirements
 * Choices can declare requirements in a tag inside the choice text, for example
 * `* [Lift the boulder # requires: strength >= 12, faction == rebels, guard.mood > 30]`. They are evaluated against
 * [stateProvider] and reported on each [DialogChoice]. Nothing is hidden or blocked here.
 *
 * ### Styles
 * Style markers (`<em>`, `<color=red>`, `<shake amp=2>`) and point markers (`<pause=0.5/>`) in lines and choices
 * come back as [TextSpan]s and [TextMarker]s. An open style stays open until its closing marker: across the
 * lines of a step and across [next] calls, also over a choice into the chosen branch. All of it is closed when the
 * story ends or the manager is reset. [DialogStep.openStyles] tells what is open at the end of a step.
 * Unbalanced closing markers are logged.
 *
 * ### Number display
 * `stat_text(name)` and `stat_text_of(entity, name)` print a stat using [numberFormat] (or the [formatOverride]
 * for that stat), and `fmt(x, decimals, mode)` formats any number explicitly.
 *
 * ### Game functions and events
 * [bindFunction] binds a Kotlin lambda to an ink `EXTERNAL` function. `~ emit("name", arg)` (hdgife.ink) is an event
 * channel that needs no `EXTERNAL` of your own: the calls come back on [DialogStep.events] and through [onEvent].
 *
 * ### Variables
 * [variable], [setVariable], [observe], [variableNames].
 *
 * ### Glue and lookahead
 * Ink runs ahead past a line break to see if glue (`<>`) joins the next line. A function that is not lookahead
 * safe ends that run ahead, which can stop glue from joining. Blade Ink 1.3.2 has a bug that makes safe functions
 * fail inside choice text and `"{...}"` strings. [HdgifeStory] fixes it. Stories made by `InkCompilerService` are
 * [HdgifeStory]s. If you pass a plain [Story] (built another way), the stat functions are bound as not lookahead
 * safe instead, which works everywhere except that they end glue lookahead. [lookaheadSafe] tells which one you got.
 *
 * ### Saving and resetting
 * [saveState] and [loadState] cover everything ink holds, including [speaker] and the random seed, in one string.
 * That needs `INCLUDE hdgife.ink`, which keeps the speaker in an ink variable. Your own stat values live in your
 * game, so save those yourself. Styles open at save time are not saved, they are closed on [loadState]. [reset]
 * restarts the story. To run different sources, compile a new story and make a new manager (or assign [story]).
 *
 * ### Defaults
 * Every default below is a game decision and can be replaced: [numberFormat], [formatOverride], [variableNamer],
 * [textCleaner], [speakerTagKey], [requirementsTagKey], [allowExternalFunctionFallbacks], [log], [valueConverter],
 * [onEvent].
 *
 * Story is not thread-safe. Call this from one thread.
 */
class StoryManager(
    story: Story,
    /** Source of stat values. Null means no stats: `stat` gives 0, `has_stat` false, requirements unmet. */
    var stateProvider: NarrativeStateProvider? = null,
    /**
     * Builds the ink variable name that [syncStatsToInk] writes a stat to. Default [defaultVariableName]:
     * `entityId_statName` with anything outside `[A-Za-z0-9_]` replaced by `_` (`player_hp`).
     */
    var variableNamer: (entityId: String, statName: String) -> String = ::defaultVariableName,
) {
    /**
     * The wrapped story. Assigning a new one (after a reload, say) binds the stat functions, your [bindFunction]s
     * and your [observe]rs to it, and resets what the manager holds (open styles, pending events, [speaker] is kept).
     */
    var story: Story = story
        set(value) {
            val keep = speaker
            closeStyles("the story was replaced")
            field = value
            field.setAllowExternalFunctionFallbacks(allowExternalFunctionFallbacks)
            bind(value)
            lastSeed = null
            pendingEvents.clear()
            speaker = keep
            reattachObservations()
        }

    /** Gets warnings such as an unknown `fmt` mode or an unbalanced style marker. Default null: the engine logs nothing. */
    var log: HdgifeLog? = null

    /** Tag key that names the speaker of a single line. Compared case-sensitively. Default `"speaker"`. */
    var speakerTagKey: String = "speaker"

    /** Tag key that declares choice requirements. Default `"requires"`. */
    var requirementsTagKey: String = "requires"

    /**
     * Cleans the plain text of each output line and choice, after style markers are removed. Default [cleanLine]
     * (English punctuation): collapse spaces, no space before `, . ; : ! ?`, trim. Replace it for other languages
     * or styles. Return the text unchanged to turn cleaning off. Spans and markers follow the cleaned text; a
     * cleaner that only removes characters keeps them exact (see `parseStyles`).
     */
    var textCleaner: (String) -> String = ::cleanLine

    /** Format used by ink's `stat_text` and `stat_text_of` when [formatOverride] has nothing for the stat. Default 0 decimals, round half away from zero. */
    var numberFormat: NumberFormat = NumberFormat(decimals = 0, rounding = Rounding.ROUND)

    /** Optional per-stat format. Return null to use [numberFormat]. Default null. Called each time ink prints a stat, so keep it cheap. */
    var formatOverride: ((entityId: String, statName: String) -> NumberFormat?)? = null

    /**
     * Whether ink functions declared `EXTERNAL` may fall back to the ink function of the same name when
     * nothing is bound. Default true, which hdgife.ink relies on (its stat and number functions have ink
     * fallbacks). If you set it to false, every `EXTERNAL` in the story needs a Kotlin binding. Applied to [story] now
     * and to any story assigned later.
     */
    var allowExternalFunctionFallbacks: Boolean = true
        set(value) {
            field = value
            story.setAllowExternalFunctionFallbacks(value)
        }

    /**
     * Turns a value of a type the engine doesn't map itself (anything but numbers, booleans, strings, chars and
     * enums) into something ink can hold: an `Int`, `Float`, `Boolean` or `String` (other results go through the
     * normal mapping once more, without this hook). Used for stats, [setVariable] and the results of
     * [bindFunction] functions. Default null: such values become `toString()`. Returning null also means
     * `toString()`.
     */
    var valueConverter: ((value: Any) -> Any?)? = null

    /**
     * Called for each `emit` event, in order, after [next] has built the step. The same events are on
     * [DialogStep.events]. Default null. The engine does nothing with events itself.
     */
    var onEvent: ((StoryEvent) -> Unit)? = null

    private var fallbackSpeaker: String? = null
    private var lastSeed: Int? = null
    private var openStyles: List<Style> = emptyList()
    private var eventLine = 0
    private val pendingEvents = ArrayList<StoryEvent>()
    private val userFunctions = LinkedHashMap<String, UserFunction>()
    private val observations = ArrayList<VariableObservation>()

    private class UserFunction(val lookaheadSafe: Boolean, val block: (FunctionArgs) -> Any?)

    /** True when the stat functions of the current story are bound lookahead safe (see "Glue and lookahead"). */
    val lookaheadSafe: Boolean get() = story is HdgifeStory

    /**
     * Entity that ink's `stat` and `has_stat` read from. Null means none, so they give 0 / false.
     * Stored in the ink variable `hdgife_speaker` when the story includes hdgife.ink, so it is part of
     * [saveState]. Otherwise it is a plain field and not saved.
     */
    var speaker: String?
        get() = if (hasSpeakerVariable()) (story.variablesState.get(SPEAKER_VARIABLE) as? String)?.ifEmpty { null } else fallbackSpeaker
        set(value) {
            if (hasSpeakerVariable()) story.variablesState.set(SPEAKER_VARIABLE, value ?: "") else fallbackSpeaker = value
        }

    private fun hasSpeakerVariable() = story.variablesState.get(SPEAKER_VARIABLE) != null

    init {
        InkNativePatch.apply()
        this.story.setAllowExternalFunctionFallbacks(allowExternalFunctionFallbacks)
        bind(story)
    }

    // ---- ink binding -------------------------------------------------------------------------------------------

    private fun bind(target: Story) {
        bindOnce(target, "stat", true) { args -> statInk(speaker, args.getOrNull(0)) ?: 0f }
        bindOnce(target, "stat_of", true) { args -> statInk(args.getOrNull(0)?.toString(), args.getOrNull(1)) ?: 0f }
        bindOnce(target, "stat_text", true) { args -> statText(speaker, args.getOrNull(0)) }
        bindOnce(target, "stat_text_of", true) { args -> statText(args.getOrNull(0)?.toString(), args.getOrNull(1)) }
        bindOnce(target, "fmt", true) { args -> fmtValue(args) }
        bindOnce(target, "has_stat", true) { args -> statInk(speaker, args.getOrNull(0)) != null }
        // Events must not be safe: they have a side effect, so ink must not run them during lookahead.
        bindOnce(target, "emit0", false) { args -> record(args); null }
        bindOnce(target, "emit", false) { args -> record(args); null }
        bindOnce(target, "emit2", false) { args -> record(args); null }
        bindOnce(target, "emit3", false) { args -> record(args); null }
        for ((name, def) in userFunctions) bindUser(target, name, def)
    }

    private fun bindOnce(target: Story, name: String, safe: Boolean, block: (Array<out Any?>) -> Any?) {
        if (target.getExternalFunction(name) != null) return
        attach(target, name, safe, Story.ExternalFunction<Any?> { args -> block(args) })
    }

    private fun attach(target: Story, name: String, safe: Boolean, function: Story.ExternalFunction<Any?>) {
        if (safe && target is HdgifeStory) target.bindLookaheadSafe(name, function)
        else target.bindExternalFunction(name, function, false)
    }

    private fun bindUser(target: Story, name: String, def: UserFunction) {
        attach(target, name, def.lookaheadSafe, Story.ExternalFunction<Any?> { args ->
            toInk(def.block(FunctionArgs(name, args.asList())))
        })
    }

    /**
     * Binds [function] to the ink function [name]. Declare it in ink and give it an ink fallback:
     *
     * ```ink
     * EXTERNAL give_item(item, count)
     * === function give_item(item, count) ===   // used when nothing is bound, e.g. in a tool without the game
     * ~ return 0
     * ```
     *
     * The `EXTERNAL` line is required for ink to compile the call. The fallback body is required unless every
     * run of the story has the binding, or you set [allowExternalFunctionFallbacks] to false (then ink fails
     * on first use with a clear message when a binding is missing). Ink checks this on the first `Continue`.
     *
     * The result is mapped to an ink value (see [NarrativeStateProvider]); null gives ink no value, for a function
     * used as a statement. Exceptions from [function] propagate out of [next].
     *
     * [lookaheadSafe] says the function may be called early or twice because ink runs ahead for glue. Leave it false
     * (default) for anything with a side effect, and know it then ends glue lookahead. Make it true only for
     * pure reads. Safe functions need an [HdgifeStory] ([lookaheadSafe] property) to work inside strings. Bindings
     * survive assigning a new [story]. Throws if [name] is already bound by something else.
     */
    fun bindFunction(name: String, lookaheadSafe: Boolean = false, function: (FunctionArgs) -> Any?) {
        if (name in userFunctions) unbindFunction(name)
        require(story.getExternalFunction(name) == null) { "'$name' is already bound" }
        val def = UserFunction(lookaheadSafe, function)
        userFunctions[name] = def
        bindUser(story, name, def)
    }

    /** Removes a function bound with [bindFunction]. Does nothing if there is none. */
    fun unbindFunction(name: String) {
        if (userFunctions.remove(name) != null && story.getExternalFunction(name) != null) story.unbindExternalFunction(name)
    }

    private fun record(args: Array<out Any?>) {
        pendingEvents += StoryEvent(args.getOrNull(0)?.toString() ?: "", args.drop(1), eventLine)
    }

    // ---- stats -------------------------------------------------------------------------------------------------

    /**
     * Maps [value] to the ink type the engine would use: `Int`, `Float`, `Boolean` or `String`, or null for null.
     * Rules are in [NarrativeStateProvider]. Anything unmapped goes through [valueConverter], then `toString()`.
     */
    fun toInk(value: Any?): Any? = when (value) {
        null -> null
        is Int -> value
        is Short, is Byte -> (value as Number).toInt()
        is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value.toFloat()
        is Float -> value
        is Double -> value.toFloat()
        is Boolean -> value
        is String -> value
        is CharSequence, is Char -> value.toString()
        is Enum<*> -> value.name
        else -> {
            val converted = valueConverter?.invoke(value)
            if (converted != null && converted !== value) toInkBasic(converted) else value.toString()
        }
    }

    private fun toInkBasic(value: Any): Any = when (value) {
        is Int, is Float, is Boolean, is String -> value
        is Short, is Byte -> (value as Number).toInt()
        is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value.toFloat()
        is Double -> value.toFloat()
        is CharSequence, is Char -> value.toString()
        is Enum<*> -> value.name
        else -> value.toString()
    }

    private fun rawStat(entity: String?, stat: Any?): Any? {
        val provider = stateProvider
        if (provider == null || entity == null || stat == null) return null
        return provider.getStat(entity, stat.toString())
    }

    /** The stat as an ink value, null if missing. */
    private fun statInk(entity: String?, stat: Any?): Any? = toInk(rawStat(entity, stat))

    private fun statText(entity: String?, stat: Any?): Any {
        val raw = rawStat(entity, stat)
        val format = if (entity != null && stat != null) formatOverride?.invoke(entity, stat.toString()) else null
        val formatter = format ?: numberFormat
        return when (raw) {
            null -> formatter.format(0)
            is Int, is Long, is Short, is Byte, is Float, is Double -> formatter.format(raw as Number)
            else -> toInk(raw).toString()
        }
    }

    private fun fmtValue(args: Array<out Any?>): Any {
        val value = args.getOrNull(0) as? Number ?: 0
        val decimals = ((args.getOrNull(1) as? Number)?.toInt() ?: 0).coerceIn(0, 9)
        val modeName = args.getOrNull(2)?.toString() ?: "round"
        val rounding = Rounding.parse(modeName) ?: Rounding.ROUND.also {
            log?.log("fmt: unknown mode '$modeName', using round")
        }
        return NumberFormat(decimals, rounding).format(value)
    }

    /**
     * Copies each stat into the ink variable named by [variableNamer], mapped to an ink type. Variables not
     * declared with `VAR` in the ink source are skipped without asking the provider, and so are stats the provider
     * returns null for. Does nothing without a [stateProvider]. Returns the names of the variables that were skipped.
     */
    fun syncStatsToInk(entityId: String, statNames: List<String>): List<String> {
        val provider = stateProvider ?: return emptyList()
        val variables = story.variablesState
        var skipped: ArrayList<String>? = null
        for (stat in statNames) {
            val name = variableNamer(entityId, stat)
            val value = if (variables.get(name) == null) null else toInk(provider.getStat(entityId, stat))
            if (value == null) {
                (skipped ?: ArrayList<String>().also { skipped = it }) += name
                continue
            }
            variables.set(name, value)
        }
        return skipped ?: emptyList()
    }

    // ---- scenes, seed, save ------------------------------------------------------------------------------------

    /**
     * Sets the current [speaker] and jumps to [knotPath] (`knot` or `knot.stitch`).
     * Call [next] afterwards to read the output. Throws if the path does not exist.
     */
    fun beginScene(knotPath: String, speakerId: String? = null) {
        speaker = speakerId
        story.choosePathString(knotPath)
    }

    /**
     * Seeds ink's RANDOM and shuffles so output is repeatable. Needs `INCLUDE hdgife.ink`
     * (returns false if the library is missing). Call it again after [reset], which reseeds randomly.
     *
     * The seed is stored in the ink state (ink's own SEED_RANDOM, together with the position in the random
     * sequence), so [saveState] and [loadState] carry it and the sequence continues exactly. The engine
     * can't set the seed directly: blade-ink keeps that setter package-private.
     */
    fun seedRandom(seed: Int): Boolean {
        if (!story.hasFunction(SEED_FUNCTION)) return false
        story.evaluateFunction(SEED_FUNCTION, arrayOf<Any>(seed))
        lastSeed = seed
        return true
    }

    /**
     * The last seed set through this manager, or null if none since the story was assigned or [loadState]
     * was called (the seed itself still lives in the loaded state). Setting calls [seedRandom] and throws
     * if the story doesn't include hdgife.ink.
     */
    var seed: Int?
        get() = lastSeed
        set(value) {
            if (value != null) check(seedRandom(value)) { "Seeding needs INCLUDE hdgife.ink in the story" }
        }

    /**
     * The whole story state as one JSON string: variables, position, visit counts, the random seed and
     * [speaker] (with hdgife.ink). Store it as is. Only load it into the same compiled story. Open styles are not
     * part of it.
     */
    fun saveState(): String = story.state.toJson()

    /**
     * Restores a string from [saveState]. Output continues from there. Throws if the state doesn't match
     * the story. Stat values are not part of it. Styles that were open are closed, pending events are dropped.
     */
    fun loadState(json: String) {
        story.state.loadJson(json)
        lastSeed = null
        closeStyles("state was loaded")
        pendingEvents.clear()
        rebaseObservations()
    }

    /**
     * Starts the story over: ink state back to the beginning (variables, position, visit counts, random seed and
     * [speaker]), open styles closed, pending events dropped. Bindings, observers and settings stay. It does not
     * recompile. To run different sources, let your game choose and order them, compile with `InkCompilerService`,
     * and build a new `StoryManager` (or assign [story]) and optionally [loadState] a saved state.
     */
    fun reset() {
        story.resetState()
        lastSeed = null
        fallbackSpeaker = null
        closeStyles("the story was reset")
        pendingEvents.clear()
        rebaseObservations()
    }

    private fun closeStyles(reason: String) {
        if (openStyles.isNotEmpty()) {
            log?.log("Style: <${openStyles.joinToString("> <") { it.name }}> never closed, closed because $reason")
        }
        openStyles = emptyList()
    }

    // ---- output ------------------------------------------------------------------------------------------------

    /**
     * Continues until the next choice or the end and returns the cleaned result.
     * With choices pending it returns them again with no lines.
     */
    fun next(): DialogStep {
        val s = story
        val lines = ArrayList<DialogLine>()
        val stepTags = ArrayList<String>()
        var carried: ArrayList<String>? = null
        while (s.canContinue()) {
            eventLine = lines.size
            val raw = s.Continue().trimEnd('\n', '\r')
            val lineTags = s.currentTags
            stepTags.addAll(lineTags)
            val styled = parseStyles(raw, openStyles, textCleaner, log)
            openStyles = styled.open
            if (styled.text.isBlank()) {
                // a line with no text hands its tags to the next line, as ink does
                if (lineTags.isNotEmpty()) (carried ?: ArrayList<String>().also { carried = it }).addAll(lineTags)
                continue
            }
            val tags = Tags.parse(carried?.let { it + lineTags } ?: lineTags)
            carried = null
            val tagSpeaker = tags[speakerTagKey]?.ifBlank { null }
            lines += DialogLine(styled.text, tagSpeaker ?: speaker, tags, styled.spans, styled.markers)
        }
        eventLine = lines.size
        val rawChoices = s.currentChoices
        val choices = ArrayList<DialogChoice>(rawChoices.size)
        for (i in rawChoices.indices) {
            val c = rawChoices[i]
            val styled = parseStyles(c.text, emptyList(), textCleaner, log)
            if (styled.open.isNotEmpty()) {
                log?.log("Style: <${styled.open.joinToString("> <") { it.name }}> never closed in choice \"${c.text}\"")
            }
            val tags = Tags.parse(c.tags ?: emptyList())
            choices += DialogChoice(c.index, styled.text, styled.spans, tags, requirementsOf(tags), styled.markers)
        }
        val ended = choices.isEmpty()
        if (ended) closeStyles("the story ended")
        val events = if (pendingEvents.isEmpty()) emptyList() else pendingEvents.toList()
        pendingEvents.clear()
        val step = DialogStep(lines, Tags.parse(stepTags), choices, ended, events, openStyles)
        val listener = onEvent
        if (listener != null) for (event in events) listener(event)
        return step
    }

    /** Picks choice [index] (as in [DialogChoice.index]) and returns the following [DialogStep]. Open styles carry over. */
    fun choose(index: Int): DialogStep {
        val count = story.currentChoices.size
        require(index in 0 until count) { "No choice $index, there are $count" }
        story.chooseChoiceIndex(index)
        return next()
    }

    // ---- requirements ------------------------------------------------------------------------------------------

    /**
     * Reads the requirement tags of a choice and checks them against [stateProvider]. Each clause is
     * `[entity.]stat op value`, ops `> >= < <= == !=`, clauses separated by commas, and the tag may repeat.
     * Without an entity the current [speaker] is used. Numbers compare with every op. Strings (and enum
     * names) and booleans only with `==` and `!=`; the value is written bare (`faction == rebels`) or in quotes.
     * A value or operator that doesn't fit the stat's type is a mismatch: unmet, and logged.
     * Clauses that can't be read are logged and returned unmet with their raw text.
     */
    private fun requirementsOf(tags: Tags): List<ChoiceRequirement> {
        val clauses = tags.all(requirementsTagKey)
        if (clauses.isEmpty()) return emptyList()
        val result = ArrayList<ChoiceRequirement>()
        val current = speaker
        for (value in clauses) {
            for (part in value.split(',')) {
                val raw = part.trim()
                if (raw.isEmpty()) continue
                result += evaluateRequirement(raw, current)
            }
        }
        return result
    }

    private fun evaluateRequirement(raw: String, currentSpeaker: String?): ChoiceRequirement {
        val match = REQUIREMENT.matchEntire(raw)
        val op = match?.let { Comparison.parse(it.groupValues[3]) }
        val text = match?.groupValues?.get(4)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
        if (match == null || op == null || text.isNullOrEmpty()) {
            log?.log("Requirement: cannot read '$raw'")
            return ChoiceRequirement(null, "", null, null, null, false, raw)
        }
        val entity = match.groupValues[1].ifEmpty { null } ?: currentSpeaker
        val stat = match.groupValues[2]
        val number = text.toDoubleOrNull()?.takeIf { it.isFinite() }
        val numberTarget: Any? = number?.let { text.toIntOrNull() ?: it.toFloat() }
        if (entity == null) {
            log?.log("Requirement: '$raw' names no entity and there is no speaker")
            return ChoiceRequirement(null, stat, op, numberTarget ?: text, null, false, raw)
        }
        val actual = statInk(entity, stat)
            ?: return ChoiceRequirement(entity, stat, op, numberTarget ?: text, null, false, raw)

        fun mismatch(why: String): ChoiceRequirement {
            log?.log("Requirement: '$raw' $why")
            return ChoiceRequirement(entity, stat, op, numberTarget ?: text, actual, false, raw, mismatch = true)
        }
        return when (actual) {
            is Int, is Float -> {
                if (number == null) return mismatch("compares a number to '$text'")
                val have = (actual as Number).let { if (it is Float) it.toString().toDouble() else it.toDouble() }
                val want = if (numberTarget is Float) numberTarget.toString().toDouble() else number
                ChoiceRequirement(entity, stat, op, numberTarget, actual, op.test(have, want), raw)
            }
            is Boolean -> {
                val want = when (text.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> return mismatch("compares a boolean to '$text'")
                }
                if (!op.isEquality) return mismatch("uses ${op.symbol} on a boolean")
                ChoiceRequirement(entity, stat, op, want, actual, (actual == want) == (op == Comparison.EQUAL), raw)
            }
            else -> {
                if (!op.isEquality) return mismatch("uses ${op.symbol} on a string")
                ChoiceRequirement(entity, stat, op, text, actual, (actual == text) == (op == Comparison.EQUAL), raw)
            }
        }
    }

    // ---- variables ---------------------------------------------------------------------------------------------

    /**
     * Names of all global variables declared in the story (`VAR` and `CONST` are not distinguished), sorted.
     * Includes the library's own, which start with `hdgife_`.
     */
    val variableNames: List<String> get() = story.variablesState.toList().sorted()

    /**
     * The current value of ink variable [name]: an `Int`, `Float`, `Boolean` or `String` (an `InkList` for lists),
     * or null if the story declares no such variable.
     */
    fun variable(name: String): Any? = story.variablesState.get(name)

    /**
     * Typed read: `variable<Int>("player_hp")`. Null when [name] is not declared. An ink int is accepted as a
     * `Float`, `Double` or `Long`, a float as a `Double`. Any other mismatch throws [IllegalArgumentException].
     */
    @JvmName("variableTyped")
    inline fun <reified T : Any> variable(name: String): T? = coerceVariable(name, variable(name), T::class)

    @PublishedApi
    internal fun <T : Any> coerceVariable(name: String, value: Any?, type: KClass<T>): T? {
        if (value == null) return null
        @Suppress("UNCHECKED_CAST")
        val converted: Any? = when {
            type.isInstance(value) -> value
            value is Int && type == Float::class -> value.toFloat()
            value is Int && type == Double::class -> value.toDouble()
            value is Int && type == Long::class -> value.toLong()
            value is Float && type == Double::class -> value.toString().toDouble()
            else -> null
        }
        @Suppress("UNCHECKED_CAST")
        return (converted as T?) ?: throw IllegalArgumentException(
            "$name is ${value.javaClass.simpleName} ($value), not ${type.simpleName}",
        )
    }

    /**
     * Sets ink variable [name] to [value], mapped like stats (see [NarrativeStateProvider]; an `InkList` is kept).
     * Throws [IllegalArgumentException] if the variable isn't declared. Observers fire as for any change.
     */
    fun setVariable(name: String, value: Any) {
        require(variable(name) != null) { "'$name' is not a variable declared in the story" }
        story.variablesState.set(name, toInk(value) ?: value)
    }

    /**
     * Calls [observer] with the old and new value each time ink variable [name] changes, whether ink or
     * [setVariable] changed it. Ink reports changes in a batch when a [next] call finishes, so the old value is
     * the one at the last report, not each step in between. [old] starts as the value at the time of this call.
     * Throws [IllegalArgumentException] if the variable isn't declared. Stop it with [unobserve].
     */
    fun observe(name: String, observer: (old: Any?, new: Any?) -> Unit): VariableObservation {
        require(variable(name) != null) { "'$name' is not a variable declared in the story" }
        val observation = VariableObservation(name, observer, this)
        observation.last = variable(name)
        observations += observation
        attach(observation)
        return observation
    }

    private fun attach(observation: VariableObservation) {
        observation.observer = Story.VariableObserver { _, new ->
            val old = observation.last
            observation.last = new
            observation.block(old, new)
        }
        story.observeVariable(observation.name, observation.observer)
    }

    /** Stops [observation]. Does nothing if it already stopped. */
    fun unobserve(observation: VariableObservation) {
        if (!observation.active) return
        observation.active = false
        observations.remove(observation)
        story.removeVariableObserver(observation.observer, observation.name)
    }

    /** Stops every observation of ink variable [name]. */
    fun unobserve(name: String) {
        for (observation in observations.filter { it.name == name }) unobserve(observation)
    }

    private fun reattachObservations() {
        for (observation in observations.toList()) {
            if (variable(observation.name) == null) {
                log?.log("Observer on '${observation.name}' dropped: the new story doesn't declare it")
                observation.active = false
                observations.remove(observation)
                continue
            }
            observation.last = variable(observation.name)
            attach(observation)
        }
    }

    private fun rebaseObservations() {
        for (observation in observations) observation.last = variable(observation.name)
    }

    companion object {
        private const val SEED_FUNCTION = "hdgife_seed"
        private const val SPEAKER_VARIABLE = "hdgife_speaker"
        private val NOT_NAME = Regex("[^A-Za-z0-9_]")
        private val REQUIREMENT = Regex("""(?:([\w-]+)\.)?([\w-]+)\s*(>=|<=|==|!=|>|<)\s*(.+)""")

        /** Builds `entityId_statName`, replacing characters outside `[A-Za-z0-9_]` with `_`. */
        fun defaultVariableName(entityId: String, statName: String): String =
            "${entityId}_$statName".replace(NOT_NAME, "_")
    }
}
