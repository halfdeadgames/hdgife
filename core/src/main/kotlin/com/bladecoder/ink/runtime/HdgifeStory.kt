package com.bladecoder.ink.runtime

/**
 * A [Story] with a working lookahead-safe external function binding. Blade Ink 1.3.2 has a bug there.
 *
 * The bug, in `Story.callExternalFunction`: the guard that is meant to refuse an *unsafe* function while ink
 * is building a string (choice text, or `"{...}"`) is written inverted:
 *
 *     if (funcDef != null && funcDef.lookaheadSafe && state.inStringEvaluation())  // 1.3.2, wrong
 *     if (funcDef != null && !funcDef.lookaheadSafe && state.inStringEvaluation())  // upstream ink
 *
 * So in 1.3.2 a function bound as lookahead safe fails with an error as soon as it is called inside a string,
 * and an unsafe one is let through exactly where it can't be (it returns without pushing a result when ink is
 * in the middle of a glue lookahead). Binding a function as unsafe to dodge the error makes every call end
 * glue lookahead, so `<>` after a line that calls it may not join.
 *
 * Fix: this subclass sits in Blade Ink's own package, so it can override the package-private method. Functions
 * bound through [bindLookaheadSafe] are run here, directly, with no guard at all: they are safe by contract, so
 * they can run in lookahead and in strings. Everything else goes to the original code unchanged.
 * No reflection. A story built any other way is a plain [Story] and keeps the old behavior, so use
 * [from] to get one (the compiler service already does).
 */
class HdgifeStory(json: String) : Story(json) {
    private val safeFunctions = mutableMapOf<String, ExternalFunction<*>>()

    /**
     * Binds [function] for the ink EXTERNAL [name], safe to call during lookahead and inside strings.
     * Only do this for functions that read and have no side effects. Throws if [name] is already bound.
     */
    fun bindLookaheadSafe(name: String, function: ExternalFunction<*>) {
        super.bindExternalFunction(name, function, true)
        safeFunctions[name] = function
    }

    /** The text ink has in its output stream right now, newline included. For a lookahead safe function that is asking where it was called. */
    fun outputText(): String = state.currentText

    override fun unbindExternalFunction(funcName: String) {
        super.unbindExternalFunction(funcName)
        safeFunctions.remove(funcName)
    }

    override fun callExternalFunction(funcName: String, numberOfArguments: Int) {
        val function = safeFunctions[funcName]
        if (function == null) {
            super.callExternalFunction(funcName, numberOfArguments)
            return
        }
        val arguments = arrayOfNulls<Any>(numberOfArguments)
        for (i in numberOfArguments - 1 downTo 0) {
            arguments[i] = (state.popEvaluationStack() as Value<*>).valueObject
        }
        val result = function.call(*arguments)
        state.pushEvaluationStack(if (result == null) Void() else AbstractValue.create(result)
            ?: throw StoryException("Could not create an ink value from ${result.javaClass.name} returned by $funcName"))
    }

    companion object {
        /**
         * [story] as an [HdgifeStory]. A plain story is rebuilt from its compiled JSON, which gives a fresh
         * state (same as `resetState()`). An [HdgifeStory] is returned as it is.
         */
        @JvmStatic
        fun from(story: Story): HdgifeStory = story as? HdgifeStory ?: HdgifeStory(story.toJson())
    }
}
