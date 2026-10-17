package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Story
import com.github.halfdeadgames.hdgife.state.NarrativeStateProvider
import ktx.log.logger

private val log = logger<StoryManager>()

private val SPACES = Regex("[ \\t]+")
private val SPACE_BEFORE_PUNCT = Regex(" +([,.;:!?])")

/** Collapses repeated spaces, removes spaces before punctuation and trims the ends. */
internal fun cleanLine(raw: String): String =
    raw.replace(SPACES, " ").replace(SPACE_BEFORE_PUNCT, "$1").trim()

/**
 * Wraps a [Story]: live stat access from ink, a current speaker, and clean [DialogStep] output.
 *
 * With `INCLUDE hdgife.ink` in the story, ink can call `stat(name)` (current speaker),
 * `stat_of(entity, name)` and `has_stat(name)`. They read [stateProvider] live. Whole values reach ink as ints, fractions as floats. Without a provider
 * they return 0 / false. Stories that don't use the library are not affected.
 *
 * Story is not thread-safe. Call this from one thread, for example the render thread
 * inside `KtxAsync.launch`.
 */
class StoryManager(
    story: Story,
    var stateProvider: NarrativeStateProvider? = null,
    private val variableNamer: (entityId: String, statName: String) -> String = ::defaultVariableName,
) {
    /** The wrapped story. Assigning a new one (after a reload, say) binds the stat functions to it. */
    var story: Story = story
        set(value) {
            field = value
            bind(value)
        }

    /** Entity that ink's `stat` and `has_stat` read from. Null means none, so they give 0 / false. */
    var speaker: String? = null

    init {
        bind(story)
    }

    private fun bind(target: Story) {
        target.setAllowExternalFunctionFallbacks(true)
        bindOnce(target, "stat") { args -> statValue(speaker, args.getOrNull(0)) }
        bindOnce(target, "stat_of") { args -> statValue(args.getOrNull(0)?.toString(), args.getOrNull(1)) }
        bindOnce(target, "has_stat") { args -> hasStatValue(speaker, args.getOrNull(0)) }
    }

    private fun bindOnce(target: Story, name: String, block: (Array<out Any?>) -> Any) {
        if (target.getExternalFunction(name) != null) return
        target.bindExternalFunction(name) { args -> block(args) }
    }

    /** Whole values go to ink as Int so they print as `7` and not `7.0`. Fractions stay Float. */
    private fun statValue(entity: String?, stat: Any?): Any {
        val provider = stateProvider
        if (provider == null || entity == null || stat == null) return 0
        val name = stat.toString()
        if (!provider.hasStat(entity, name)) return 0
        val value = provider.getStat(entity, name)
        return if (value % 1f == 0f && value > -2.1e9f && value < 2.1e9f) value.toInt() else value
    }

    private fun hasStatValue(entity: String?, stat: Any?): Boolean {
        val provider = stateProvider
        if (provider == null || entity == null || stat == null) return false
        return provider.hasStat(entity, stat.toString())
    }

    /**
     * Copies each stat into the ink variable named by [variableNamer]. Variables not declared
     * with `VAR` in the ink source are skipped. Does nothing without a [stateProvider].
     */
    fun syncStatsToInk(entityId: String, statNames: List<String>) {
        val provider = stateProvider ?: return
        val variables = story.variablesState
        for (stat in statNames) {
            val name = variableNamer(entityId, stat)
            if (variables.get(name) == null) {
                log.debug { "Ink variable '$name' is not declared, skipping" }
                continue
            }
            variables.set(name, provider.getStat(entityId, stat))
        }
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
     * Seeds ink's RANDOM and shuffles so output is repeatable, for tests. Needs `INCLUDE hdgife.ink`
     * (returns false if the library is missing). Call it again after `resetState()`, which reseeds randomly.
     */
    fun seedRandom(seed: Int): Boolean {
        if (!story.hasFunction(SEED_FUNCTION)) return false
        story.evaluateFunction(SEED_FUNCTION, arrayOf<Any>(seed))
        return true
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
            val line = cleanLine(s.Continue())
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
            choices += DialogChoice(c.index, cleanLine(c.text), c.tags?.toList() ?: emptyList())
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

        /** Builds `entityId_statName`, replacing characters outside `[A-Za-z0-9_]` with `_`. */
        fun defaultVariableName(entityId: String, statName: String): String =
            "${entityId}_$statName".replace(Regex("[^A-Za-z0-9_]"), "_")
    }
}
