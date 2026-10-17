package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.HdgifeLog
import com.github.halfdeadgames.hdgife.state.NarrativeStateProvider
private val SPACES = Regex("[ \\t]+")
private val SPACE_BEFORE_PUNCT = Regex(" +([,.;:!?])")

/** Collapses repeated spaces, removes spaces before punctuation and trims the ends. */
fun cleanLine(raw: String): String =
    raw.replace(SPACES, " ").replace(SPACE_BEFORE_PUNCT, "$1").trim()

/**
 * Wraps a [Story]: live stat access from ink, a current speaker, and clean [DialogStep] output.
 *
 * With `INCLUDE hdgife.ink` in the story, ink can call `stat(name)` (current speaker),
 * `stat_of(entity, name)` and `has_stat(name)`. They read [stateProvider] live and always give ink a float,
 * so ink math is the same for whole and fractional values. Without a provider they return 0 / false.
 *
 * Number display is up to the game and the story: `stat_text(name)` and `stat_text_of(entity, name)` print a
 * stat using [numberFormat] (or the [formatOverride] for that stat), and `fmt(x, decimals, mode)` formats any
 * number explicitly. Stories that don't use the library are not affected.
 *
 * Saving: [saveState] and [loadState] cover everything, including [speaker] and the random seed, in one
 * string. That needs `INCLUDE hdgife.ink`, which keeps the speaker in an ink variable. Without it the
 * speaker is a plain field that the game has to keep itself (ink can't read it without the library anyway).
 * Your own stat values live in your game, so save those yourself.
 *
 * Story is not thread-safe. Call this from one thread.
 */
class StoryManager(
    story: Story,
    var stateProvider: NarrativeStateProvider? = null,
    private val variableNamer: (entityId: String, statName: String) -> String = ::defaultVariableName,
) {
    /** The wrapped story. Assigning a new one (after a reload, say) binds the stat functions to it. */
    var story: Story = story
        set(value) {
            val keep = speaker
            field = value
            bind(value)
            lastSeed = null
            speaker = keep
        }

    /** Gets warnings such as an unknown `fmt` mode. Null drops them. */
    var log: HdgifeLog? = null

    /** Cleans each line of output text. The default fixes spaces and punctuation spacing, see [cleanLine]. */
    var textCleaner: (String) -> String = ::cleanLine

    /** Format used by ink's `stat_text` and `stat_text_of` when [formatOverride] has nothing for the stat. */
    var numberFormat: NumberFormat = NumberFormat(decimals = 0, rounding = Rounding.ROUND)

    /** Optional per-stat format. Return null to use [numberFormat]. Called each time ink prints a stat, so keep it cheap. */
    var formatOverride: ((entityId: String, statName: String) -> NumberFormat?)? = null

    private var fallbackSpeaker: String? = null
    private var lastSeed: Int? = null

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
        bind(story)
    }

    private fun bind(target: Story) {
        target.setAllowExternalFunctionFallbacks(true)
        bindOnce(target, "stat") { args -> statValue(speaker, args.getOrNull(0)) }
        bindOnce(target, "stat_of") { args -> statValue(args.getOrNull(0)?.toString(), args.getOrNull(1)) }
        bindOnce(target, "stat_text") { args -> statText(speaker, args.getOrNull(0)) }
        bindOnce(target, "stat_text_of") { args -> statText(args.getOrNull(0)?.toString(), args.getOrNull(1)) }
        bindOnce(target, "fmt") { args -> fmtValue(args) }
        bindOnce(target, "has_stat") { args -> hasStatValue(speaker, args.getOrNull(0)) }
    }

    private fun bindOnce(target: Story, name: String, block: (Array<out Any?>) -> Any) {
        if (target.getExternalFunction(name) != null) return
        target.bindExternalFunction(name) { args -> block(args) }
    }

    /** Stats always reach ink as Float. */
    private fun statValue(entity: String?, stat: Any?): Any = statNumber(entity, stat)

    private fun statNumber(entity: String?, stat: Any?): Float {
        val provider = stateProvider
        if (provider == null || entity == null || stat == null) return 0f
        val name = stat.toString()
        if (!provider.hasStat(entity, name)) return 0f
        return provider.getStat(entity, name)
    }

    private fun statText(entity: String?, stat: Any?): Any {
        val value = statNumber(entity, stat)
        val format = if (entity != null && stat != null) formatOverride?.invoke(entity, stat.toString()) else null
        return (format ?: numberFormat).format(value)
    }

    private fun fmtValue(args: Array<out Any?>): Any {
        val value = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
        val decimals = ((args.getOrNull(1) as? Number)?.toInt() ?: 0).coerceIn(0, 9)
        val modeName = args.getOrNull(2)?.toString() ?: "round"
        val rounding = Rounding.parse(modeName) ?: Rounding.ROUND.also {
            log?.log("fmt: unknown mode '$modeName', using round")
        }
        return NumberFormat(decimals, rounding).format(value)
    }

    private fun hasStatValue(entity: String?, stat: Any?): Boolean {
        val provider = stateProvider
        if (provider == null || entity == null || stat == null) return false
        return provider.hasStat(entity, stat.toString())
    }

    /**
     * Copies each stat into the ink variable named by [variableNamer]. Variables not declared
     * with `VAR` in the ink source are skipped. Does nothing without a [stateProvider].
     * Returns the names of the variables that were skipped.
     */
    fun syncStatsToInk(entityId: String, statNames: List<String>): List<String> {
        val provider = stateProvider ?: return emptyList()
        val variables = story.variablesState
        var skipped: ArrayList<String>? = null
        for (stat in statNames) {
            val name = variableNamer(entityId, stat)
            if (variables.get(name) == null) {
                (skipped ?: ArrayList<String>().also { skipped = it }) += name
                continue
            }
            variables.set(name, provider.getStat(entityId, stat))
        }
        return skipped ?: emptyList()
    }

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
     * (returns false if the library is missing). Call it again after `resetState()`, which reseeds randomly.
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
     * [speaker] (with hdgife.ink). Store it as is. Only load it into the same compiled story.
     */
    fun saveState(): String = story.state.toJson()

    /**
     * Restores a string from [saveState]. Output continues from there. Throws if the state doesn't match
     * the story. Stat values are not part of it.
     */
    fun loadState(json: String) {
        story.state.loadJson(json)
        lastSeed = null
    }

    /**
     * Continues until the next choice or the end and returns the cleaned result.
     * With choices pending it returns them again with empty text.
     */
    fun next(): DialogStep {
        val s = story
        val text = StringBuilder()
        val tags = ArrayList<String>()
        while (s.canContinue()) {
            val line = textCleaner(s.Continue())
            if (line.isNotEmpty()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(line)
            }
            tags.addAll(s.currentTags)
        }
        val raw = s.currentChoices
        val choices = ArrayList<DialogChoice>(raw.size)
        for (i in raw.indices) {
            val c = raw[i]
            choices += DialogChoice(c.index, textCleaner(c.text), c.tags?.toList() ?: emptyList())
        }
        return DialogStep(text.toString(), tags, choices, choices.isEmpty())
    }

    /** Picks choice [index] (as in [DialogChoice.index]) and returns the following [DialogStep]. */
    fun choose(index: Int): DialogStep {
        val count = story.currentChoices.size
        require(index in 0 until count) { "No choice $index, there are $count" }
        story.chooseChoiceIndex(index)
        return next()
    }

    companion object {
        private const val SEED_FUNCTION = "hdgife_seed"
        private const val SPEAKER_VARIABLE = "hdgife_speaker"

        /** Builds `entityId_statName`, replacing characters outside `[A-Za-z0-9_]` with `_`. */
        fun defaultVariableName(entityId: String, statName: String): String =
            "${entityId}_$statName".replace(Regex("[^A-Za-z0-9_]"), "_")
    }
}
