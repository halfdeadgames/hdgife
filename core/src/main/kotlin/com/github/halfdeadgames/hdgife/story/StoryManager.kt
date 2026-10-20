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

private val repeatedSpaces = Regex("[ \\t]+")
private val spaceBeforePunctuation = Regex(" +([,.;:!?])")

public fun cleanLine(raw: String): String =
    raw.replace(repeatedSpaces, " ").replace(spaceBeforePunctuation, "$1").trim()

public class StoryManager private constructor(
    story: Story,
    lookaheadSafeMode: Boolean,
    stateProvider: NarrativeStateProvider?,
    configure: StoryManager.() -> Unit,
) : AutoCloseable {
    @JvmOverloads
    public constructor(
        story: HdgifeStory,
        stateProvider: NarrativeStateProvider? = null,
        configure: StoryManager.() -> Unit = {},
    ) : this(story, true, stateProvider, configure)

    public var stateProvider: NarrativeStateProvider? = stateProvider

    public var variableNamer: VariableNamer = VariableNamer(::defaultVariableName)

    private var current: Story = story

    public val story: Story get() = current

    public fun replaceStory(story: HdgifeStory): Unit = swapStory(story, true)

    public fun replacePlainStory(story: Story): Unit = swapStory(story, false)

    private fun swapStory(newStory: Story, lookaheadSafe: Boolean) {
        ensureOpen()
        checkLibrary(newStory)
        val previousStory = current
        val keptSpeaker = speaker
        closeStyles("the story was replaced")
        detachObservations()
        unbindOwn(previousStory)
        current = newStory
        isLookaheadSafe = lookaheadSafe
        newStory.setAllowExternalFunctionFallbacks(allowExternalFunctionFallbacks)
        bind(newStory)
        lastSeed = null
        fingerprintCache = null
        dropEvents()
        speaker = keptSpeaker
        reattachObservations()
    }

    public var log: HdgifeLog? = null

    public var speakerTagKey: String = "speaker"

    public var requirementsTagKey: String = "requires"

    public var textCleaner: TextCleaner = TextCleaner(::cleanLine)

    public var numberFormat: NumberFormat = NumberFormat(decimals = 0, rounding = Rounding.Round)

    public var formatOverride: NumberFormatOverride = NumberFormatOverride { _, _ -> null }

    public var allowExternalFunctionFallbacks: Boolean = true
        set(value) {
            field = value
            story.setAllowExternalFunctionFallbacks(value)
        }

    public var valueConverter: ValueConverter = ValueConverter { null }

    public var onEvent: StoryEventListener = StoryEventListener { }

    public var maxOpenStyles: Int = 16
        set(value) {
            require(value >= 1) { "maxOpenStyles must be at least 1" }
            field = value
        }

    public var closeStylesAtChoice: Boolean = false

    public var storyId: String? = null

    public var saveFingerprint: Boolean = false

    private var closed = false
    private var fingerprintCache: String? = null
    private val speakRecords = ArrayList<SpeakRecord>()
    private val undelivered = ArrayList<StoryEvent>()
    private var fallbackSpeaker: String? = null
    public var lastSeed: Int? = null
        private set
    private var openStyles: List<Style> = emptyList()
    private var eventLine = 0
    private val pendingEvents = ArrayList<PendingEvent>()
    private val unconfirmedEvents = ArrayList<CandidateEvent>()
    private val userFunctions = LinkedHashMap<String, UserFunction>()
    private val attached = HashMap<String, Story.ExternalFunction<Any?>>()
    private val observations = ArrayList<VariableObservation>()

    private class PendingEvent(val event: StoryEvent, val late: Boolean)

    private class SpeakRecord(val name: String?, val outputAtCall: String)

    private class CandidateEvent(val seq: Int, val event: StoryEvent, val outputAtEmit: String)

    private class UserFunction(val lookaheadSafe: Boolean, val block: (FunctionArgs) -> Any?)

    public var isLookaheadSafe: Boolean = lookaheadSafeMode
        private set

    public var speaker: String?
        get() {
            ensureOpen()
            return if (hasSpeakerVariable()) (story.variablesState.get(speakerVariable) as? String)?.ifEmpty { null } else fallbackSpeaker
        }
        set(value) {
            ensureOpen()
            if (hasSpeakerVariable()) story.variablesState.set(speakerVariable, value ?: "") else fallbackSpeaker = value
        }

    private fun hasSpeakerVariable() = story.variablesState.get(speakerVariable) != null

    init {
        InkNativePatch.apply()
        checkLibrary(story)
        story.setAllowExternalFunctionFallbacks(allowExternalFunctionFallbacks)
        bind(story)
    }

    init {
        configure()
    }

    public val isClosed: Boolean get() = closed

    private fun ensureOpen() = check(!closed) { "This StoryManager is closed" }

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

    private fun bind(target: Story) {
        bindLibrary(target, "stat") { args -> statInk(speaker, args.getOrNull(0)) ?: 0f }
        bindLibrary(target, "stat_of") { args -> statInk(args.getOrNull(0)?.toString(), args.getOrNull(1)) ?: 0f }
        bindLibrary(target, "stat_text") { args -> statText(speaker, args.getOrNull(0)) }
        bindLibrary(target, "stat_text_of") { args -> statText(args.getOrNull(0)?.toString(), args.getOrNull(1)) }
        bindLibrary(target, "fmt") { args -> fmtValue(args) }
        bindLibrary(target, "has_stat") { args -> statInk(speaker, args.getOrNull(0)) != null }
        bindLibrary(target, "has_stat_of") { args -> statInk(args.getOrNull(0)?.toString(), args.getOrNull(1)) != null }
        bindLibrary(target, "hdgife_emit0") { args -> record(args); null }
        bindLibrary(target, "hdgife_emit1") { args -> record(args); null }
        bindLibrary(target, "hdgife_emit2") { args -> record(args); null }
        bindLibrary(target, "hdgife_emit3") { args -> record(args); null }
        bindLibrary(target, "hdgife_speak") { args -> recordSpeak(args); null }
        for ((name, userFunction) in userFunctions) {
            if (target.getExternalFunction(name) != null) target.unbindExternalFunction(name)
            bindUser(target, name, userFunction)
        }
    }

    private fun unbindOwn(target: Story) {
        for ((name, function) in attached) {
            if (target.getExternalFunction(name) === function) target.unbindExternalFunction(name)
        }
        attached.clear()
    }

    private fun checkLibrary(target: Story) {
        val problem = libraryProblem(target) ?: return
        log?.log(problem.message.orEmpty())
        throw problem
    }

    private fun bindLibrary(target: Story, name: String, block: (Array<out Any?>) -> Any?) {
        if (target.getExternalFunction(name) != null) target.unbindExternalFunction(name)
        attach(target, name, true, Story.ExternalFunction<Any?> { args -> block(args) })
    }

    private fun attach(target: Story, name: String, safe: Boolean, function: Story.ExternalFunction<Any?>) {
        if (safe && isLookaheadSafe) (target as HdgifeStory).bindLookaheadSafe(name, function)
        else target.bindExternalFunction(name, function, false)
        attached[name] = function
    }

    private fun bindUser(target: Story, name: String, userFunction: UserFunction) {
        attach(target, name, userFunction.lookaheadSafe, Story.ExternalFunction<Any?> { args ->
            toInk(userFunction.block(FunctionArgs(name, args.asList())))
        })
    }

    @JvmOverloads
    public fun bindFunction(name: String, lookaheadSafe: Boolean = false, function: (FunctionArgs) -> Any?) {
        ensureOpen()
        if (name in userFunctions) unbindFunction(name)
        require(story.getExternalFunction(name) == null) { "'$name' is already bound" }
        val userFunction = UserFunction(lookaheadSafe, function)
        userFunctions[name] = userFunction
        bindUser(story, name, userFunction)
    }

    public fun unbindFunction(name: String) {
        ensureOpen()
        if (userFunctions.remove(name) == null) return
        val boundByThisManager = attached.remove(name)
        if (boundByThisManager != null && story.getExternalFunction(name) === boundByThisManager) story.unbindExternalFunction(name)
    }

    private fun recordSpeak(args: Array<out Any?>) {
        speakRecords += SpeakRecord(args.getOrNull(0)?.toString()?.ifEmpty { null }, (story as? HdgifeStory)?.outputText().orEmpty())
    }

    // The last speak() during the Continue wins, except one that ink ran ahead past the line's newline: that call is for
    // the lines after. If glue later joined more text onto the line, the newline is gone and the call counts here.
    private fun speakerAt(raw: String, speakerBefore: String?): String? {
        var speakerAtLine = speakerBefore
        for (record in speakRecords) {
            if (record.outputAtCall.endsWithLineBreakAfter(raw)) continue
            speakerAtLine = record.name
        }
        return speakerAtLine
    }

    private fun String.endsWithLineBreakAfter(line: String): Boolean = endsWith('\n') && trimEnd('\n', '\r') == line

    private fun record(args: Array<out Any?>) {
        val seq = args.getOrNull(0) as? Int ?: return
        val event = StoryEvent(args.getOrNull(1)?.toString() ?: "", args.drop(2), eventLine, seq)
        unconfirmedEvents += CandidateEvent(seq, event, (story as? HdgifeStory)?.outputText().orEmpty())
    }

    // Ink undoes variables when it undoes a glue lookahead, so hdgife_event_seq only counts emits that really happened.
    // Candidates above it ran in undone lookahead and will run again on the next Continue. An event that saw the whole
    // produced line plus its newline happened after that line (late), so it belongs before the next one.
    private fun confirmEvents(producedLine: String) {
        if (unconfirmedEvents.isEmpty()) return
        val confirmedSeq = story.variablesState.get(eventSeqVariable) as? Int ?: Int.MAX_VALUE
        for (candidate in unconfirmedEvents) {
            if (candidate.seq > confirmedSeq) continue
            pendingEvents += PendingEvent(candidate.event, candidate.outputAtEmit.endsWithLineBreakAfter(producedLine))
        }
        unconfirmedEvents.clear()
    }

    private fun placeLateEvents(lineAdded: Boolean) {
        for (i in pendingEvents.indices) {
            val pending = pendingEvents[i]
            if (!pending.late) continue
            pendingEvents[i] = PendingEvent(if (lineAdded) pending.event.atLine(pending.event.lineIndex + 1) else pending.event, false)
        }
    }

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
        val rounding = Rounding.parse(modeName) ?: Rounding.Round.also {
            log?.log("fmt: unknown mode '$modeName', using round")
        }
        return NumberFormat(decimals, rounding).format(value)
    }

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

    public fun seedRandom(seed: Int) {
        ensureOpen()
        if (!story.hasFunction(seedFunction)) {
            throw IncompatibleLibraryException("Seeding needs INCLUDE hdgife.ink in the story", 0)
        }
        story.evaluateFunction(seedFunction, arrayOf<Any>(seed))
        lastSeed = seed
    }

    public fun saveState(): String {
        ensureOpen()
        val fingerprintToSave = if (saveFingerprint) fingerprint() else null
        return SaveEnvelope.write(story.state.toJson(), openStyles, fallbackSpeaker, storyId, fingerprintToSave)
    }

    public fun loadState(json: String) {
        ensureOpen()
        val saved = SaveEnvelope.read(json)
        if (saved != null) checkIdentity(saved)
        val inkJson = saved?.inkJson ?: json
        try {
            story.state.loadJson(inkJson)
        } catch (e: Exception) {
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
        val managerStoryId = storyId
        if (managerStoryId != null && saved.storyId != null && saved.storyId != managerStoryId) {
            throw SaveStateException("This save belongs to story '${saved.storyId}', but this manager is for story '$managerStoryId'")
        }
        if (saveFingerprint && saved.fingerprint != null && saved.fingerprint != fingerprint()) {
            throw SaveStateException("The story has changed since this save was written (its structure fingerprint differs)")
        }
    }

    private fun fingerprint(): String = fingerprintCache ?: SaveEnvelope.fingerprint(story).also { fingerprintCache = it }

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

    public fun next(): DialogStep {
        ensureOpen()
        val inkStory = story
        val lines = ArrayList<DialogLine>()
        val stepTags = ArrayList<String>()
        var tagsCarriedToNextLine: ArrayList<String>? = null
        val choices = ArrayList<DialogChoice>()
        val startStyles = openStyles
        val undeliveredEvents = takeUndelivered()
        var deliveredEvents: List<StoryEvent>? = null
        var notified = false
        try {
            while (inkStory.canContinue()) {
                eventLine = lines.size
                val speakerBefore = speaker
                speakRecords.clear()
                val raw = inkStory.Continue().trimEnd('\n', '\r')
                confirmEvents(raw)
                val lineSpeaker = speakerAt(raw, speakerBefore)
                val lineTags = inkStory.currentTags
                stepTags.addAll(lineTags)
                val styled = parseStyles(raw, openStyles, textCleaner, log, maxOpenStyles)
                openStyles = styled.open
                if (styled.text.isBlank()) {
                    placeLateEvents(false)
                    if (lineTags.isNotEmpty()) (tagsCarriedToNextLine ?: ArrayList<String>().also { tagsCarriedToNextLine = it }).addAll(lineTags)
                    continue
                }
                val tags = Tags.parse(tagsCarriedToNextLine?.let { it + lineTags } ?: lineTags)
                tagsCarriedToNextLine = null
                val tagSpeaker = tags[speakerTagKey]?.ifBlank { null }
                lines += DialogLine(styled.text, tagSpeaker ?: lineSpeaker, tags, styled.spans, styled.markers)
                placeLateEvents(true)
            }
            confirmEvents("")
            placeLateEvents(false)
            eventLine = lines.size
            // Events go to the game first: choice requirements below must see what onEvent changed.
            val confirmed = if (undeliveredEvents.isEmpty()) takeEvents() else undeliveredEvents + takeEvents()
            deliveredEvents = confirmed
            notified = true
            notifyEvents(confirmed)
            val inkChoices = inkStory.currentChoices
            for (i in inkChoices.indices) {
                val inkChoice = inkChoices[i]
                val styled = parseStyles(inkChoice.text, emptyList(), textCleaner, log)
                if (styled.open.isNotEmpty()) {
                    log?.log("Style: <${styled.open.joinToString("> <") { it.name }}> never closed in choice \"${inkChoice.text}\"")
                }
                val tags = Tags.parse(inkChoice.tags ?: emptyList())
                choices += DialogChoice(inkChoice.index, styled.text, styled.spans, tags, requirementsOf(tags), styled.markers)
            }
        } catch (e: Exception) {
            unconfirmedEvents.clear()
            placeLateEvents(false)
            eventLine = lines.size
            val kept = deliveredEvents ?: (if (undeliveredEvents.isEmpty()) takeEvents() else undeliveredEvents + takeEvents())
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
        return DialogStep(lines, Tags.parse(stepTags), choices, ended, deliveredEvents.orEmpty(), openStyles)
    }

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

    private fun takeUndelivered(): List<StoryEvent> {
        if (undelivered.isEmpty()) return emptyList()
        val events = undelivered.map { it.atLine(0) }
        undelivered.clear()
        return events
    }

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

    public fun choose(index: Int): DialogStep {
        ensureOpen()
        val count = story.currentChoices.size
        require(index in 0 until count) { "No choice $index, there are $count" }
        story.chooseChoiceIndex(index)
        return next()
    }

    private fun requirementsOf(tags: Tags): List<RequirementResult> {
        val clauses = tags.all(requirementsTagKey)
        if (clauses.isEmpty()) return emptyList()
        return RequirementEvaluator(::statInk, speaker, log).evaluate(clauses)
    }

    public val eventCount: Int get() = getVariable(eventSeqVariable) as? Int ?: 0

    @ExperimentalHdgifeApi
    public fun validateStory(): StoryReport = validateStory(
        story.also { ensureOpen() },
        boundFunctions = attached.keys + userFunctions.keys,
        observedVariables = observations.map { it.name },
        requirementsTagKey = requirementsTagKey,
    )

    public val variableNames: List<String> get() = story.also { ensureOpen() }.variablesState.toList().sorted()

    public fun getVariable(name: String): Any? {
        ensureOpen()
        return story.variablesState.get(name)
    }

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

    public inline fun <reified T : Any> variableAs(name: String): T? = getVariable(name, T::class)

    public fun setVariable(name: String, value: Any) {
        ensureOpen()
        require(getVariable(name) != null) { "'$name' is not a variable declared in the story" }
        story.variablesState.set(name, if (value is InkList) value else toInk(value) ?: value)
    }

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

    public fun unobserve(observation: VariableObservation) {
        if (!observation.isActive) return
        observation.isActive = false
        observations.remove(observation)
        story.removeVariableObserver(observation.observer, observation.name)
    }

    public fun unobserve(name: String) {
        for (observation in observations.filter { it.name == name }) unobserve(observation)
    }

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
        private const val seedFunction = "hdgife_seed"
        private const val speakerVariable = "hdgife_speaker"
        private const val eventSeqVariable = "hdgife_event_seq"
        private val invalidNameCharacters = Regex("[^A-Za-z0-9_]")

        @JvmStatic
        @JvmOverloads
        public fun forPlainStory(
            story: Story,
            stateProvider: NarrativeStateProvider? = null,
            configure: StoryManager.() -> Unit = {},
        ): StoryManager = StoryManager(story, false, stateProvider, configure)

        @JvmStatic
        public fun defaultVariableName(entityId: String, statName: String): String =
            "${entityId}_$statName".replace(invalidNameCharacters, "_")
    }
}
