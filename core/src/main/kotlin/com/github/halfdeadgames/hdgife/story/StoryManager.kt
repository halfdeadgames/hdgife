package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.HdgifeStory
import com.bladecoder.ink.runtime.InkList
import com.bladecoder.ink.runtime.InkNativePatch
import com.bladecoder.ink.runtime.Story
import com.bladecoder.ink.runtime.StoryException
import com.github.halfdeadgames.hdgife.ExperimentalHdgifeApi
import com.github.halfdeadgames.hdgife.HdgifeInk
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
public fun cleanLine(raw: String): String =
    raw.replace(SPACES, " ").replace(SPACE_BEFORE_PUNCT, "$1").trim()

/**
 * Wraps a [Story]: live stat access from ink, a current speaker, clean [DialogStep] output, game-defined ink
 * functions, an event channel and variable access. The engine reports and the game decides: it hides nothing,
 * blocks nothing and renders nothing.
 *
 * ### Stats
 * With `INCLUDE hdgife.ink` in the story, ink can call `stat(name)` (current speaker), `stat_of(entity, name)`
 * `has_stat(name)` and `has_stat_of(entity, name)`. They read [stateProvider] live. A stat can be any Kotlin value, see
 * [NarrativeStateProvider] for the mapping to ink types. Without a provider (or with a missing stat) `stat`
 * gives 0 and `has_stat` / `has_stat_of` false. These functions are lookahead safe, see "Glue and lookahead" below.
 *
 * ### Speakers
 * [speaker] is the persistent speaker. Ink changes it with `speak("guard")`, a line can name a speaker for itself
 * with a tag (`# speaker: guard`, key set by [speakerTagKey]). A tag speaker applies to that line only.
 * Each [DialogLine] reports the speaker it had when its text was produced: a `speak()` that comes after the line
 * (before a choice, the end or the next line) does not change it. Within one line (glue), the last `speak()` before the
 * line ends wins. This works in glue lookahead and across [saveState]. On a plain [Story] (see "Glue and lookahead")
 * `speak()` is not lookahead safe, so it is also correct there, but it ends glue lookahead.
 * `stat(name)` always reads the persistent speaker.
 *
 * ### Requirements
 * Choices can declare requirements in a tag inside the choice text, for example
 * `* [Lift the boulder # requires: strength >= 12, faction == rebels, guard.mood > 30]`. They are evaluated against
 * [stateProvider] and reported on each [DialogChoice]. Nothing is hidden or blocked here.
 * Commas separate clauses that must all hold (AND). Inside a clause `|` or the word `or` separates alternatives (OR)
 * and a leading `!` or `not ` negates one (ink needs `\|` for a bar inside a choice's brackets, so `or` is easier there): `# requires: strength >= 12 or charm >= 8, !is_injured == true`. `!` binds tighter than `|`,
 * which binds tighter than `,`. There are no parentheses. A `|`, `,` or ` or ` inside quotes is literal. See [requirementsTagKey].
 *
 * ### Styles
 * Style markers (`<em>`, `<color=red>`, `<shake amp=2>`) and point markers (`<pause=0.5/>`) in lines and choices
 * come back as [TextSpan]s and [TextMarker]s. An open style stays open until its closing marker: across the
 * lines of a step and across [next] calls, also over a choice into the chosen branch (unless [closeStylesAtChoice]).
 * All of it is closed when the story ends or the manager is reset. [DialogStep.openStyles] tells what is open at the
 * end of a step. At most [maxOpenStyles] stay open, so a stray `<word>` that is never closed cannot grow without limit.
 * Unbalanced closing markers, dropped styles and styles opened in a step and still open at its end are logged.
 *
 * ### Number display
 * `stat_text(name)` and `stat_text_of(entity, name)` print a stat using [numberFormat] (or the [formatOverride]
 * for that stat), and `fmt(x, decimals, mode)` formats any number explicitly.
 *
 * ### Game functions and events
 * [bindFunction] binds a Kotlin lambda to an ink `EXTERNAL` function. `~ emit("name", arg)` (hdgife.ink) is an event
 * channel that needs no `EXTERNAL` of your own: the calls come back on [DialogStep.events] and through [onEvent].
 * [onEvent] runs for each event before the step's choices are read, so what the game changes in it (spending a coin)
 * is seen by the requirements of that step's choices. Each event has a stable [StoryEvent.id]. If [onEvent] throws,
 * the events after the one that threw stay pending and the next [next] delivers them first (see [onEvent]).
 * Events are lookahead safe: each one is reported exactly once and in order, also across glue (see "Glue and lookahead").
 *
 * ### Variables
 * [getVariable], [variableAs], [setVariable], [observe], [variableNames].
 *
 * ### Checking a story
 * [validateStory] lists what is wrong or risky in the story and its bindings, without throwing. For a story that has
 * no manager use `StoryValidator.validate(story)`.
 *
 * ### Glue and lookahead
 * Ink runs ahead past a line break to see if glue (`<>`) joins the next line. A function that is not lookahead
 * safe ends that run ahead, which can stop glue from joining. Blade Ink 1.3.2 has a bug that makes safe functions
 * fail inside choice text and `"{...}"` strings. [HdgifeStory] fixes it. The constructor and [replaceStory] take an
 * [HdgifeStory] (what `InkCompilerService` returns) and bind the stat functions lookahead safe. A plain [Story]
 * (built another way) goes through [forPlainStory] and [replacePlainStory]: the stat functions are bound as not
 * lookahead safe, which works everywhere except that they end glue lookahead. [isLookaheadSafe] tells which mode the
 * manager is in; the entry point decides it.
 * Events are the exception: they may run in lookahead either way. Ink counts them in `hdgife_event_seq`, which ink
 * undoes together with the lookahead, and [next] only keeps events that the count still includes.
 * A story whose hdgife.ink is not the version this engine expects (`HdgifeInk.VERSION`; a missing version counts as
 * 0) is refused with an [IncompatibleLibraryException] (and a [log] message), because its events could never be kept.
 *
 * ### Saving and resetting
 * [saveState] and [loadState] cover everything ink holds, including [speaker] and the random seed, plus the open
 * styles, in one opaque string. Ink state needs `INCLUDE hdgife.ink`, which keeps the speaker in an ink variable.
 * Your own stat values live in your game, so save those yourself. [loadState] also reads a plain ink state JSON
 * (styles are then closed). [reset]
 * restarts the story. [storyId] and [saveFingerprint] tie a save to its story, see [saveState]. To run different sources, compile a new story and make a new manager (or call [replaceStory]).
 *
 * ### Configuring
 * Settings are properties. Set them in the optional trailing lambda of the constructor, so the manager is ready to use
 * when it is returned, or any time later:
 *
 * ```kotlin
 * val manager = StoryManager(story) {
 *     stateProvider = myProvider
 *     log = HdgifeLog { println(it) }
 *     numberFormat = NumberFormat(decimals = 1)
 *     onEvent = StoryEventListener { event -> handle(event) }
 * }
 * ```
 *
 * ### Defaults
 * Every default below is a game decision and can be replaced: [numberFormat], [formatOverride], [variableNamer],
 * [textCleaner], [speakerTagKey], [requirementsTagKey], [allowExternalFunctionFallbacks], [log], [valueConverter],
 * [onEvent].
 *
 * ### Closing
 * [close] unbinds this manager from the story, so the story can go to a fresh manager. A closed manager throws
 * [IllegalStateException] from calls that use the story.
 *
 * Errors: invalid API input throws, bad story content is logged or reported (see [validateStory]).
 *
 * Story is not thread-safe. Call this from one thread.
 */
public class StoryManager private constructor(
    story: Story,
    lookaheadSafeMode: Boolean,
    stateProvider: NarrativeStateProvider?,
    configure: StoryManager.() -> Unit,
) : AutoCloseable {
    /**
     * Wraps [story], which stat functions bound lookahead safe (see "Glue and lookahead"). Throws
     * [IncompatibleLibraryException] if the story includes a hdgife.ink of another version. Use [forPlainStory] for
     * a story that is not an [HdgifeStory]. [configure] runs last and sets properties (see "Configuring").
     */
    @JvmOverloads
    public constructor(
        story: HdgifeStory,
        stateProvider: NarrativeStateProvider? = null,
        configure: StoryManager.() -> Unit = {},
    ) : this(story, true, stateProvider, configure)

    /** Source of stat values. Null means no stats: `stat` gives 0, `has_stat` false, requirements unmet. */
    public var stateProvider: NarrativeStateProvider? = stateProvider

    /**
     * Builds the ink variable name that [syncStats] writes a stat to. Default [defaultVariableName]:
     * `entityId_statName` with anything outside `[A-Za-z0-9_]` replaced by `_` (`player_hp`).
     */
    public var variableNamer: VariableNamer = VariableNamer(::defaultVariableName)

    private var current: Story = story

    /**
     * The wrapped story. It is fixed for the life of the manager; [replaceStory] swaps it. Everything ink-side you
     * do through the story itself (`choosePathString`, `variablesState`) is allowed, but prefer the manager's API.
     */
    public val story: Story get() = current

    /**
     * Swaps in [story] (after a reload, say). It is checked first, and a refused story leaves the manager exactly as
     * it was. Then it binds the stat functions, your [bindFunction]s and your [observe]rs to it, and resets what the
     * manager holds (open styles, pending events; [speaker] is kept). The old story is unbound from this manager.
     * Giving it the story it already has is allowed and rebinds it. Observers whose variable the new story does not
     * declare are dropped (and logged). Sets [isLookaheadSafe] to true.
     *
     * Throws [IncompatibleLibraryException] if the story includes a hdgife.ink of another version.
     *
     * One story should have one manager. A manager takes over the library names (`stat`, `fmt`, `hdgife_emit0` ...)
     * and its own [bindFunction] names on the story it gets, replacing any earlier binding, such as the one of a
     * previous manager. Functions another manager bound under other names stay with that manager.
     */
    public fun replaceStory(story: HdgifeStory): Unit = swapStory(story, true)

    /** Same as [replaceStory] for a plain [Story]: [isLookaheadSafe] becomes false. */
    public fun replacePlainStory(story: Story): Unit = swapStory(story, false)

    private fun swapStory(value: Story, safeMode: Boolean) {
        ensureOpen()
        // Validate first: a refused story leaves the manager exactly as it was.
        checkLibrary(value)
        val old = current
        val keep = speaker
        closeStyles("the story was replaced")
        detachObservations()
        unbindOwn(old)
        current = value
        isLookaheadSafe = safeMode
        value.setAllowExternalFunctionFallbacks(allowExternalFunctionFallbacks)
        bind(value)
        lastSeed = null
        fingerprintCache = null
        dropEvents()
        speaker = keep
        reattachObservations()
    }

    /** Gets warnings such as an unknown `fmt` mode or an unbalanced style marker. Default null: the engine logs nothing. */
    public var log: HdgifeLog? = null

    /** Tag key that names the speaker of a single line. Compared case-sensitively. Default `"speaker"`. */
    public var speakerTagKey: String = "speaker"

    /** Tag key that declares choice requirements. Default `"requires"`. */
    public var requirementsTagKey: String = "requires"

    /**
     * Cleans the plain text of each output line and choice, after style markers are removed. Default [cleanLine]
     * (English punctuation): collapse spaces, no space before `, . ; : ! ?`, trim. Replace it for other languages
     * or styles. Return the text unchanged to turn cleaning off. Spans and markers follow the cleaned text; a
     * cleaner that only removes characters keeps them exact (see `parseStyles`).
     */
    public var textCleaner: TextCleaner = TextCleaner(::cleanLine)

    /** Format used by ink's `stat_text` and `stat_text_of` when [formatOverride] has nothing for the stat. Default 0 decimals, round half away from zero. */
    public var numberFormat: NumberFormat = NumberFormat(decimals = 0, rounding = Rounding.ROUND)

    /**
     * Per-stat format. Its `formatFor` returns null to use [numberFormat]. Default: always null. Called each time ink
     * prints a stat, so keep it cheap. Assign `NumberFormatOverride { entity, stat -> ... }`.
     */
    public var formatOverride: NumberFormatOverride = NumberFormatOverride { _, _ -> null }

    /**
     * Whether ink functions declared `EXTERNAL` may fall back to the ink function of the same name when
     * nothing is bound. Default true, which hdgife.ink relies on (its stat and number functions have ink
     * fallbacks). If you set it to false, every `EXTERNAL` in the story needs a Kotlin binding. Applied to [story] now
     * and to any story assigned later.
     */
    public var allowExternalFunctionFallbacks: Boolean = true
        set(value) {
            field = value
            story.setAllowExternalFunctionFallbacks(value)
        }

    /**
     * Turns a value of a type the engine doesn't map itself (anything but numbers, booleans, strings, chars and
     * enums) into something ink can hold: an `Int`, `Float`, `Boolean` or `String` (other results go through the
     * normal mapping once more, without this hook). Used for stats, [setVariable] and the results of
     * [bindFunction] functions. Default: converts nothing, so such values become `toString()`. Returning null also
     * means `toString()`. Assign `ValueConverter { value -> ... }`.
     */
    public var valueConverter: ValueConverter = ValueConverter { null }

    /**
     * Called for each `emit` event, in order, while [next] builds a step: after the story has run to the next choice
     * or the end, and before the choices are read. A requirement on a choice therefore sees what the game changed
     * here. (A condition that ink itself evaluates, like `{coin >= 3} [Buy]`, is decided inside the story before the
     * event arrives, so keep such state in ink variables or use `# requires:`.) The same events are on
     * [DialogStep.events]. Default: does nothing. The engine does nothing with events itself.
     *
     * An exception thrown here comes out of [next] as a [StoryStepException] whose step holds the lines and all the
     * events. The event that threw counts as delivered. The events after it stay pending: the next [next] delivers them
     * first, in order and once, and lists them at the front of its [DialogStep.events] with `lineIndex` 0. If the
     * listener throws again, the rest stays pending again. [loadState], [reset], [replaceStory] and [close] drop them.
     */
    public var onEvent: StoryEventListener = StoryEventListener { }

    /**
     * How many styles may be open at once. Default 16. When a style opens beyond the limit the oldest open style is
     * dropped and a message is logged, so a stray `<word>` or an unclosed style in a looping knot cannot grow the
     * spans and the saves without limit. Must be at least 1.
     */
    public var maxOpenStyles: Int = 16
        set(value) {
            require(value >= 1) { "maxOpenStyles must be at least 1" }
            field = value
        }

    /**
     * When true, the styles still open at a choice are closed (and logged) instead of carrying into the chosen branch,
     * so [DialogStep.openStyles] is empty at every step end. Default false: they carry over.
     */
    public var closeStylesAtChoice: Boolean = false

    /**
     * Names the story in saves. When set, [saveState] writes it and [loadState] refuses a save that carries a
     * different id with a [SaveStateException]. A save without an id (older, or written with no id) is accepted.
     * Default null.
     */
    public var storyId: String? = null

    /**
     * When true, [saveState] also writes a fingerprint of the story structure (a hash of the names of its knots,
     * stitches, labels and variables) and [loadState] refuses a save with another fingerprint with a
     * [SaveStateException]: the story changed since the save. Saves without a fingerprint are accepted. The fingerprint
     * is computed on first use and kept until [replaceStory]. It sees structure, not text: an edited line passes.
     * Default false.
     */
    public var saveFingerprint: Boolean = false

    private var closed = false
    private var fingerprintCache: String? = null
    private val speakRecords = ArrayList<SpeakRecord>()
    private val undelivered = ArrayList<StoryEvent>()
    private var fallbackSpeaker: String? = null
    /**
     * The last seed set through [seedRandom], or null if none since the story was assigned, [loadState] or [reset]
     * (the seed itself still lives in the loaded state). There is no way to unseed: call [reset] and don't seed.
     */
    public var lastSeed: Int? = null
        private set
    private var openStyles: List<Style> = emptyList()
    private var eventLine = 0
    private val pendingEvents = ArrayList<PendingEvent>()
    private val unconfirmedEvents = ArrayList<CandidateEvent>()
    private val userFunctions = LinkedHashMap<String, UserFunction>()
    /** What this manager bound on the story, by name, so it only ever unbinds its own functions. */
    private val attached = HashMap<String, Story.ExternalFunction<Any?>>()
    private val observations = ArrayList<VariableObservation>()

    /** An event kept by [confirmEvents]. [late] means it happened after the line just added (see [placeLateEvents]). */
    private class PendingEvent(val event: StoryEvent, val late: Boolean)

    /** A `speak()` call: the new speaker and the text ink had in its output when it was called. */
    private class SpeakRecord(val name: String?, val seen: String)

    private class CandidateEvent(val seq: Int, val event: StoryEvent, val textAtEmit: String)

    private class UserFunction(val lookaheadSafe: Boolean, val block: (FunctionArgs) -> Any?)

    /**
     * True when the stat functions of the current story are bound lookahead safe (see "Glue and lookahead"). It is
     * a mode, set by the entry point: true for the constructor and [replaceStory], false for [forPlainStory] and
     * [replacePlainStory].
     */
    public var isLookaheadSafe: Boolean = lookaheadSafeMode
        private set

    /**
     * Entity that ink's `stat` and `has_stat` read from. Null means none, so they give 0 / false.
     * Stored in the ink variable `hdgife_speaker` when the story includes hdgife.ink, so it is part of
     * [saveState]. Otherwise it is a plain field and not saved.
     */
    public var speaker: String?
        get() {
            ensureOpen()
            return if (hasSpeakerVariable()) (story.variablesState.get(SPEAKER_VARIABLE) as? String)?.ifEmpty { null } else fallbackSpeaker
        }
        set(value) {
            ensureOpen()
            if (hasSpeakerVariable()) story.variablesState.set(SPEAKER_VARIABLE, value ?: "") else fallbackSpeaker = value
        }

    private fun hasSpeakerVariable() = story.variablesState.get(SPEAKER_VARIABLE) != null

    init {
        InkNativePatch.apply()
        checkLibrary(story)
        story.setAllowExternalFunctionFallbacks(allowExternalFunctionFallbacks)
        bind(story)
    }

    init {
        configure()
    }

    /** True after [close]. */
    public val isClosed: Boolean get() = closed

    private fun ensureOpen() = check(!closed) { "This StoryManager is closed" }

    /**
     * Detaches this manager from its story: unbinds the library functions and your [bindFunction]s, removes the
     * observers, closes the styles and drops pending events. The story keeps its ink state and can go to a fresh
     * manager. After this, calls that use the story throw [IllegalStateException]; closing again does nothing. The
     * [VariableObservation]s become inactive.
     */
    override fun close() {
        if (closed) return
        closed = true
        detachObservations()
        for (observation in observations) observation.isActive = false
        observations.clear()
        unbindOwn(story)
        userFunctions.clear()
        dropEvents()
        closeStyles("the manager was closed")
    }

    private fun dropEvents() {
        pendingEvents.clear()
        unconfirmedEvents.clear()
        undelivered.clear()
    }

    // ---- ink binding -------------------------------------------------------------------------------------------

    private fun bind(target: Story) {
        bindLibrary(target, "stat") { args -> statInk(speaker, args.getOrNull(0)) ?: 0f }
        bindLibrary(target, "stat_of") { args -> statInk(args.getOrNull(0)?.toString(), args.getOrNull(1)) ?: 0f }
        bindLibrary(target, "stat_text") { args -> statText(speaker, args.getOrNull(0)) }
        bindLibrary(target, "stat_text_of") { args -> statText(args.getOrNull(0)?.toString(), args.getOrNull(1)) }
        bindLibrary(target, "fmt") { args -> fmtValue(args) }
        bindLibrary(target, "has_stat") { args -> statInk(speaker, args.getOrNull(0)) != null }
        bindLibrary(target, "has_stat_of") { args -> statInk(args.getOrNull(0)?.toString(), args.getOrNull(1)) != null }
        // Events may run during lookahead and run again after it is undone. next() keeps each one once (see record).
        bindLibrary(target, "hdgife_emit0") { args -> record(args); null }
        bindLibrary(target, "hdgife_emit1") { args -> record(args); null }
        bindLibrary(target, "hdgife_emit2") { args -> record(args); null }
        bindLibrary(target, "hdgife_emit3") { args -> record(args); null }
        bindLibrary(target, "hdgife_speak") { args -> recordSpeak(args); null }
        for ((name, def) in userFunctions) {
            if (target.getExternalFunction(name) != null) target.unbindExternalFunction(name)
            bindUser(target, name, def)
        }
    }

    /** Unbinds the functions this manager bound on [target], leaving anything else alone. */
    private fun unbindOwn(target: Story) {
        for ((name, function) in attached) {
            if (target.getExternalFunction(name) === function) target.unbindExternalFunction(name)
        }
        attached.clear()
    }

    /**
     * A story that includes hdgife.ink must carry the version this engine expects. An old one has no version (0): its
     * events could never be kept. A story that does not include hdgife.ink is not checked.
     */
    private fun checkLibrary(target: Story) {
        val problem = libraryProblem(target) ?: return
        log?.log(problem.message.orEmpty())
        throw problem
    }

    /** Library functions are always rebound to this manager, replacing whatever was bound under the name. */
    private fun bindLibrary(target: Story, name: String, block: (Array<out Any?>) -> Any?) {
        if (target.getExternalFunction(name) != null) target.unbindExternalFunction(name)
        attach(target, name, true, Story.ExternalFunction<Any?> { args -> block(args) })
    }

    private fun attach(target: Story, name: String, safe: Boolean, function: Story.ExternalFunction<Any?>) {
        if (safe && isLookaheadSafe) (target as HdgifeStory).bindLookaheadSafe(name, function)
        else target.bindExternalFunction(name, function, false)
        attached[name] = function
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
     * used as a statement. An exception from [function] comes out of [next] as a [StoryStepException].
     *
     * [lookaheadSafe] says the function may be called early or twice because ink runs ahead for glue. Leave it false
     * (default) for anything with a side effect, and know it then ends glue lookahead. Make it true only for
     * pure reads. Safe functions only work inside strings in [isLookaheadSafe] mode. Bindings
     * survive [replaceStory]. Throws [IllegalArgumentException] if [name] is already bound by something else.
     */
    @JvmOverloads
    public fun bindFunction(name: String, lookaheadSafe: Boolean = false, function: (FunctionArgs) -> Any?) {
        ensureOpen()
        if (name in userFunctions) unbindFunction(name)
        require(story.getExternalFunction(name) == null) { "'$name' is already bound" }
        val def = UserFunction(lookaheadSafe, function)
        userFunctions[name] = def
        bindUser(story, name, def)
    }

    /** Removes a function bound with [bindFunction]. Does nothing if there is none. */
    public fun unbindFunction(name: String) {
        ensureOpen()
        if (userFunctions.remove(name) == null) return
        val own = attached.remove(name)
        if (own != null && story.getExternalFunction(name) === own) story.unbindExternalFunction(name)
    }

    /** Called by `speak()` after it set the speaker. [speakerAt] sorts out later which line each call belongs to. */
    private fun recordSpeak(args: Array<out Any?>) {
        speakRecords += SpeakRecord(args.getOrNull(0)?.toString()?.ifEmpty { null }, (story as? HdgifeStory)?.outputText().orEmpty())
    }

    /**
     * The speaker of the line [raw] that [next] just got from Continue. [before] is the speaker when the Continue began.
     * The last `speak()` call during it wins, except a call that ink ran ahead past the line's newline (it saw the whole
     * line plus the newline): that one is for the lines after, and the ink variable already holds it for them. If glue
     * later joined the next text onto the line, the newline is gone and the call counts for the line.
     */
    private fun speakerAt(raw: String, before: String?): String? {
        var result = before
        for (record in speakRecords) {
            val seen = record.seen
            if (seen.endsWith('\n') && seen.trimEnd('\n', '\r') == raw) continue
            result = record.name
        }
        return result
    }

    /**
     * Called by the bound emit functions with `(seq, name, args...)`. The event is only a candidate until
     * [confirmEvents] sees that ink kept the state it was emitted in.
     */
    private fun record(args: Array<out Any?>) {
        val seq = args.getOrNull(0) as? Int ?: return
        val event = StoryEvent(args.getOrNull(1)?.toString() ?: "", args.drop(2), eventLine, seq)
        unconfirmedEvents += CandidateEvent(seq, event, (story as? HdgifeStory)?.outputText().orEmpty())
    }

    /**
     * Each emit counts up the ink variable `hdgife_event_seq` just before it calls Kotlin. Ink variables are part of
     * the state that ink restores when it undoes a glue lookahead, so after a Continue the variable only counts
     * emits that are really behind us. Candidates above it were run in undone lookahead and will run again, in the
     * right place, on the next Continue. The rest are final. This keeps each event exactly once, in order, with
     * the line index of the Continue that really produced it.
     */
    private fun confirmEvents(produced: String) {
        if (unconfirmedEvents.isEmpty()) return
        val seq = story.variablesState.get(EVENT_SEQ_VARIABLE) as? Int ?: Int.MAX_VALUE
        for (candidate in unconfirmedEvents) {
            if (candidate.seq > seq) continue
            // Ink can keep a lookahead that ran past the end of the line. An event that saw the whole produced
            // text already happened after that line, so it belongs before the next one.
            val seen = candidate.textAtEmit
            pendingEvents += PendingEvent(candidate.event, seen.endsWith('\n') && seen.trimEnd('\n', '\r') == produced)
        }
        unconfirmedEvents.clear()
    }

    /** Moves the events that happened after the line just added to the index of the line after it. */
    private fun placeLateEvents(lineAdded: Boolean) {
        for (i in pendingEvents.indices) {
            val p = pendingEvents[i]
            if (!p.late) continue
            pendingEvents[i] = PendingEvent(if (lineAdded) p.event.atLine(p.event.lineIndex + 1) else p.event, false)
        }
    }

    // ---- stats -------------------------------------------------------------------------------------------------

    /**
     * Maps [value] to the ink type the engine would use: `Int`, `Float`, `Boolean` or `String`, or null for null.
     * Rules are in [NarrativeStateProvider]. Anything unmapped goes through [valueConverter], then `toString()`.
     */
    internal fun toInk(value: Any?): Any? = when (value) {
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
            val converted = valueConverter.convert(value)
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
        val format = if (entity != null && stat != null) formatOverride.formatFor(entity, stat.toString()) else null
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
    public fun syncStats(entityId: String, statNames: List<String>): List<String> {
        ensureOpen()
        val provider = stateProvider ?: return emptyList()
        val variables = story.variablesState
        var skipped: ArrayList<String>? = null
        for (stat in statNames) {
            val name = variableNamer.nameFor(entityId, stat)
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
     * Sets the current [speaker] and jumps to [path] (`knot` or `knot.stitch`).
     * Call [next] afterwards to read the output. Throws [StoryPathException] if ink cannot find the path (ink's
     * `StoryException` is its cause); the speaker is then left as it was.
     */
    @JvmOverloads
    public fun beginScene(path: String, speaker: String? = null) {
        ensureOpen()
        try {
            story.choosePathString(path)
        } catch (e: StoryException) {
            throw StoryPathException(path, e)
        }
        this.speaker = speaker
    }

    /**
     * Seeds ink's RANDOM and shuffles so output is repeatable. Needs `INCLUDE hdgife.ink`: throws
     * [IncompatibleLibraryException] if the library is missing. Call it again after [reset], which reseeds randomly.
     *
     * The seed is stored in the ink state (ink's own SEED_RANDOM, together with the position in the random
     * sequence), so [saveState] and [loadState] carry it and the sequence continues exactly. The engine
     * can't set the seed directly: blade-ink keeps that setter package-private.
     */
    public fun seedRandom(seed: Int) {
        ensureOpen()
        if (!story.hasFunction(SEED_FUNCTION)) {
            throw IncompatibleLibraryException("Seeding needs INCLUDE hdgife.ink in the story", 0)
        }
        story.evaluateFunction(SEED_FUNCTION, arrayOf<Any>(seed))
        lastSeed = seed
    }

    /**
     * The whole story state as one JSON string: variables, position, visit counts, the random seed, [speaker]
     * (with hdgife.ink) and the styles that are open. Store it as is and only load it into the same compiled
     * story. It is a small versioned envelope around ink's own state JSON:
     * `{"v":1,"ink":"<ink state>","styles":[{"name":"color","value":"red","params":{}}]}`, plus `"speaker"` when the
     * speaker is kept outside ink (no hdgife.ink). Treat the format as opaque.
     *
     * With [storyId] set, or [saveFingerprint] on, the envelope is version 2 and also carries `"storyId"` and
     * `"fingerprint"`, which [loadState] checks. Without them it stays version 1, so older engines read it too.
     * This engine reads both versions.
     */
    public fun saveState(): String {
        ensureOpen()
        val id = storyId
        val print = if (saveFingerprint) fingerprint() else null
        return SaveEnvelope.write(story.state.toJson(), openStyles, fallbackSpeaker, id, print)
    }

    /**
     * Restores a string from [saveState], or a plain ink state JSON (an older save). Output continues from there,
     * and styles that were open at save time are open again. A plain ink JSON has no style information, so open
     * styles are closed. Throws [SaveStateException] for a save from a newer version or a broken envelope, and for
     * a state that ink refuses (the story doesn't match; ink's exception is the cause), and for a save whose `storyId`
     * differs from [storyId], or, with [saveFingerprint] on, whose fingerprint differs. Stat values are not part of
     * it. Pending events are dropped.
     *
     * A refused envelope (version, ids, damage) is checked before anything is touched, so the manager stays as it was.
     * If ink itself fails to load the state, ink's story may be half-updated, so the manager still closes the open
     * styles, drops pending events and resets the observer baselines and [lastSeed], and keeps the speaker field as it
     * is. It is then consistent with itself; load a good save or [reset].
     */
    public fun loadState(json: String) {
        ensureOpen()
        val saved = SaveEnvelope.read(json)
        if (saved != null) checkIdentity(saved)
        val inkJson = saved?.inkJson ?: json
        try {
            story.state.loadJson(inkJson)
        } catch (e: Exception) {
            // Ink may have changed part of the story. Leave the manager's own bookkeeping consistent with that.
            lastSeed = null
            closeStyles("loading the state failed")
            dropEvents()
            rebaseObservations()
            throw SaveStateException("Ink could not load the state: ${e.message}", e)
        }
        lastSeed = null
        if (saved == null) {
            closeStyles("state was loaded")
        } else {
            openStyles = saved.styles
            fallbackSpeaker = saved.speaker
        }
        dropEvents()
        rebaseObservations()
    }

    private fun checkIdentity(saved: SavedState) {
        val id = storyId
        if (id != null && saved.storyId != null && saved.storyId != id) {
            throw SaveStateException("This save belongs to story '${saved.storyId}', but this manager is for story '$id'")
        }
        if (saveFingerprint && saved.fingerprint != null && saved.fingerprint != fingerprint()) {
            throw SaveStateException("The story has changed since this save was written (its structure fingerprint differs)")
        }
    }

    /** The structure fingerprint of the current story. Computed once, kept until [replaceStory]. */
    private fun fingerprint(): String = fingerprintCache ?: SaveEnvelope.fingerprint(story).also { fingerprintCache = it }

    /**
     * Starts the story over: ink state back to the beginning (variables, position, visit counts, random seed and
     * [speaker]), open styles closed, pending events dropped. Bindings, observers and settings stay. It does not
     * recompile. To run different sources, let your game choose and order them, compile with `InkCompilerService`,
     * and build a new `StoryManager` (or call [replaceStory]) and optionally [loadState] a saved state.
     */
    public fun reset() {
        ensureOpen()
        story.resetState()
        lastSeed = null
        fallbackSpeaker = null
        closeStyles("the story was reset")
        dropEvents()
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
     *
     * If ink fails while continuing (a bound function throws, or an ink runtime error), the lines built so far are
     * not lost: a [StoryStepException] is thrown that carries them as a partial [DialogStep] (no choices, not
     * ended) and has the original exception as its cause. The manager stays consistent with those lines: open
     * styles and events cover exactly what the partial step holds, and the events of the failed Continue are dropped.
     * The ink story itself is in whatever state ink left it in, so after a failure the usual move is [loadState] or [reset].
     */
    public fun next(): DialogStep {
        ensureOpen()
        val s = story
        val lines = ArrayList<DialogLine>()
        val stepTags = ArrayList<String>()
        var carried: ArrayList<String>? = null
        val choices = ArrayList<DialogChoice>()
        val startStyles = openStyles
        val leftover = takeUndelivered()
        var events: List<StoryEvent>? = null
        var notified = false
        try {
            while (s.canContinue()) {
                eventLine = lines.size
                val before = speaker
                speakRecords.clear()
                val raw = s.Continue().trimEnd('\n', '\r')
                confirmEvents(raw)
                val lineSpeaker = speakerAt(raw, before)
                val lineTags = s.currentTags
                stepTags.addAll(lineTags)
                val styled = parseStyles(raw, openStyles, textCleaner, log, maxOpenStyles)
                openStyles = styled.open
                if (styled.text.isBlank()) {
                    // a line with no text hands its tags to the next line, as ink does
                    placeLateEvents(false)
                    if (lineTags.isNotEmpty()) (carried ?: ArrayList<String>().also { carried = it }).addAll(lineTags)
                    continue
                }
                val tags = Tags.parse(carried?.let { it + lineTags } ?: lineTags)
                carried = null
                val tagSpeaker = tags[speakerTagKey]?.ifBlank { null }
                // The speaker when the line's text was produced, not after ink ran ahead (see recordSpeak).
                lines += DialogLine(styled.text, tagSpeaker ?: lineSpeaker, tags, styled.spans, styled.markers)
                placeLateEvents(true)
            }
            confirmEvents("")
            placeLateEvents(false)
            eventLine = lines.size
            // The game hears every event first, so the requirements below see the state it changed.
            val confirmed = if (leftover.isEmpty()) takeEvents() else leftover + takeEvents()
            events = confirmed
            notified = true
            notifyEvents(confirmed)
            val rawChoices = s.currentChoices
            for (i in rawChoices.indices) {
                val c = rawChoices[i]
                val styled = parseStyles(c.text, emptyList(), textCleaner, log)
                if (styled.open.isNotEmpty()) {
                    log?.log("Style: <${styled.open.joinToString("> <") { it.name }}> never closed in choice \"${c.text}\"")
                }
                val tags = Tags.parse(c.tags ?: emptyList())
                choices += DialogChoice(c.index, styled.text, styled.spans, tags, requirementsOf(tags), styled.markers)
            }
        } catch (e: Exception) {
            // Keep what was built. Events from the failed Continue are unconfirmed and dropped; the styles and
            // events that belong to the returned lines are kept and handed over, so nothing is half-applied.
            unconfirmedEvents.clear()
            placeLateEvents(false)
            eventLine = lines.size
            val kept = events ?: (if (leftover.isEmpty()) takeEvents() else leftover + takeEvents())
            val partial = DialogStep(lines, Tags.parse(stepTags), emptyList(), false, kept, openStyles)
            if (!notified) {
                try {
                    notifyEvents(kept)
                } catch (listenerFailure: Exception) {
                    e.addSuppressed(listenerFailure)
                }
            }
            throw StoryStepException("The story failed while continuing: ${e.message}", e, partial)
        }
        val ended = choices.isEmpty()
        if (ended) {
            closeStyles("the story ended")
        } else if (closeStylesAtChoice) {
            closeStyles("a choice was reached")
        } else {
            logUnclosed(startStyles)
        }
        return DialogStep(lines, Tags.parse(stepTags), choices, ended, events.orEmpty(), openStyles)
    }

    /** Logs the styles that this step opened and left open (they carry on into the choice). Only works when [log] is set. */
    private fun logUnclosed(startStyles: List<Style>) {
        val sink = log ?: return
        if (openStyles.isEmpty()) return
        val added = openStyles.filter { style -> startStyles.none { it === style } }
        if (added.isNotEmpty()) sink.log("Style: <${added.joinToString("> <") { it.name }}> opened and not closed by the end of the step")
    }

    private fun takeEvents(): List<StoryEvent> {
        val events = if (pendingEvents.isEmpty()) emptyList() else pendingEvents.map { it.event }
        pendingEvents.clear()
        return events
    }

    /** The events a throwing [onEvent] left behind, placed before the first line of this step. */
    private fun takeUndelivered(): List<StoryEvent> {
        if (undelivered.isEmpty()) return emptyList()
        val events = undelivered.map { it.atLine(0) }
        undelivered.clear()
        return events
    }

    /** Hands the events to [onEvent]. The one that throws counts as delivered; the ones after it stay for the next [next]. */
    private fun notifyEvents(events: List<StoryEvent>) {
        val listener = onEvent
        for (i in events.indices) {
            try {
                listener.onEvent(events[i])
            } catch (e: Exception) {
                for (j in i + 1 until events.size) undelivered += events[j]
                throw e
            }
        }
    }

    /** Picks choice [index] (as in [DialogChoice.index]) and returns the following [DialogStep]. Open styles carry over. */
    public fun choose(index: Int): DialogStep {
        ensureOpen()
        val count = story.currentChoices.size
        require(index in 0 until count) { "No choice $index, there are $count" }
        story.chooseChoiceIndex(index)
        return next()
    }

    // ---- requirements ------------------------------------------------------------------------------------------

    /**
     * Reads the requirement tags of a choice and checks them against [stateProvider]. Each term is
     * `[entity.]stat op value`, ops `> >= < <= == !=`, and the tag may repeat. Clauses are separated by commas
     * (all must hold); a clause is one term or several joined with `|` or `or` (any may hold, an [RequirementResult.AnyOf]);
     * a term can start with `!` or `not ` (a [RequirementResult.Not]). `!` binds first, then `|`, then `,`.
     * Without an entity the current [speaker] is used. Numbers compare with every op. Strings (and enum
     * names) and booleans only with `==` and `!=`; the value is written bare (`faction == rebels`) or in quotes, and a
     * value with `|`, `,` or ` or ` in it must be quoted.
     * A value or operator that doesn't fit the stat's type is a mismatch: unmet, and logged.
     * Terms that can't be read are logged and returned unmet with their raw text.
     */
    private fun requirementsOf(tags: Tags): List<RequirementResult> {
        val clauses = tags.all(requirementsTagKey)
        if (clauses.isEmpty()) return emptyList()
        return RequirementEvaluator(::statInk, speaker, log).evaluate(clauses)
    }

    // ---- variables ---------------------------------------------------------------------------------------------

    /**
     * How many `emit` calls the story has made so far: the [StoryEvent.id] of the latest event, 0 before the first.
     * Part of the ink state, so [loadState] sets it back. Save it next to your own handled-events mark if you dedupe by id.
     */
    public val eventCount: Int get() = getVariable(EVENT_SEQ_VARIABLE) as? Int ?: 0

    /**
     * Checks the story and this manager's bindings and returns what it found, without throwing (see [StoryReport]).
     * Run it after binding your functions and observers, for example in a debug build or a test. The unbound
     * `EXTERNAL`s it reports are only those the story calls. The report also covers [observe]d variable names and
     * the `requires` tags of [requirementsTagKey].
     */
    @ExperimentalHdgifeApi
    public fun validateStory(): StoryReport = validateStory(
        story.also { ensureOpen() },
        boundFunctions = attached.keys + userFunctions.keys,
        observedVariables = observations.map { it.name },
        requirementsTagKey = requirementsTagKey,
    )

    /**
     * Names of all global variables declared in the story (`VAR` and `CONST` are not distinguished), sorted.
     * Includes the library's own, which start with `hdgife_`.
     */
    public val variableNames: List<String> get() = story.also { ensureOpen() }.variablesState.toList().sorted()

    /**
     * The current value of ink variable [name]: an `Int`, `Float`, `Boolean` or `String` (an `InkList` for lists),
     * or null if the story declares no such variable.
     */
    public fun getVariable(name: String): Any? {
        ensureOpen()
        return story.variablesState.get(name)
    }

    /**
     * Typed read: `getVariable("player_hp", Int::class)`, or `variableAs<Int>("player_hp")` from Kotlin. Null when
     * [name] is not declared. An ink int is accepted as a `Float`, `Double` or `Long`, a float as a `Double`. Any
     * other mismatch throws [IllegalArgumentException].
     */
    public fun <T : Any> getVariable(name: String, type: KClass<T>): T? {
        val value = getVariable(name) ?: return null
        val converted: Any? = when {
            type.isInstance(value) -> value
            value is Int && type == Float::class -> value.toFloat()
            value is Int && type == Double::class -> value.toDouble()
            value is Int && type == Long::class -> value.toLong()
            value is Float && type == Double::class -> value.toCleanDouble()
            else -> null
        }
        @Suppress("UNCHECKED_CAST")
        return (converted as T?) ?: throw IllegalArgumentException(
            "$name is ${value.javaClass.simpleName} ($value), not ${type.simpleName}",
        )
    }

    /** Typed read with the type as a type argument: `variableAs<Int>("player_hp")`. Same rules as [getVariable]. */
    public inline fun <reified T : Any> variableAs(name: String): T? = getVariable(name, T::class)

    /**
     * Sets ink variable [name] to [value], mapped like stats (see [NarrativeStateProvider]; an `InkList` is kept).
     * Throws [IllegalArgumentException] if the variable isn't declared. Observers fire as for any change.
     */
    public fun setVariable(name: String, value: Any) {
        ensureOpen()
        require(getVariable(name) != null) { "'$name' is not a variable declared in the story" }
        story.variablesState.set(name, if (value is InkList) value else toInk(value) ?: value)
    }

    /**
     * Calls [observer] with the old and new value each time ink variable [name] changes, whether ink or
     * [setVariable] changed it. Ink reports changes in a batch when a [next] call finishes, so the old value is
     * the one at the last report, not each step in between. [old] starts as the value at the time of this call.
     * Throws [IllegalArgumentException] if the variable isn't declared. Stop it with [unobserve].
     */
    public fun observe(name: String, observer: (old: Any?, new: Any?) -> Unit): VariableObservation {
        ensureOpen()
        require(getVariable(name) != null) { "'$name' is not a variable declared in the story" }
        val observation = VariableObservation(name, observer, this)
        observation.last = getVariable(name)
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
    public fun unobserve(observation: VariableObservation) {
        if (!observation.isActive) return
        observation.isActive = false
        observations.remove(observation)
        story.removeVariableObserver(observation.observer, observation.name)
    }

    /** Stops every observation of ink variable [name]. */
    public fun unobserve(name: String) {
        for (observation in observations.filter { it.name == name }) unobserve(observation)
    }

    /** Removes our observers from the current story, before it is replaced. */
    private fun detachObservations() {
        for (observation in observations) story.removeVariableObserver(observation.observer, observation.name)
    }

    private fun reattachObservations() {
        for (observation in observations.toList()) {
            if (getVariable(observation.name) == null) {
                log?.log("Observer on '${observation.name}' dropped: the new story doesn't declare it")
                observation.isActive = false
                observations.remove(observation)
                continue
            }
            observation.last = getVariable(observation.name)
            attach(observation)
        }
    }

    private fun rebaseObservations() {
        for (observation in observations) observation.last = getVariable(observation.name)
    }

    public companion object {
        private const val SEED_FUNCTION = "hdgife_seed"
        private const val SPEAKER_VARIABLE = "hdgife_speaker"
        private const val EVENT_SEQ_VARIABLE = "hdgife_event_seq"
        private val NOT_NAME = Regex("[^A-Za-z0-9_]")

        /**
         * A manager for a plain [Story] that is not an [HdgifeStory]: the stat functions are bound as not lookahead
         * safe, so they end glue lookahead (see "Glue and lookahead"). [isLookaheadSafe] is false. Prefer an
         * [HdgifeStory] from `InkCompilerService` and the public constructor. [configure] runs last and sets properties.
         */
        @JvmStatic
        @JvmOverloads
        public fun forPlainStory(
            story: Story,
            stateProvider: NarrativeStateProvider? = null,
            configure: StoryManager.() -> Unit = {},
        ): StoryManager = StoryManager(story, false, stateProvider, configure)

        /** Builds `entityId_statName`, replacing characters outside `[A-Za-z0-9_]` with `_`. */
        @JvmStatic
        public fun defaultVariableName(entityId: String, statName: String): String =
            "${entityId}_$statName".replace(NOT_NAME, "_")
    }
}
