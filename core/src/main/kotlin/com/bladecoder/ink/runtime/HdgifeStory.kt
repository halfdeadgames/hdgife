package com.bladecoder.ink.runtime

// Blade Ink 1.3.2 inverts the guard in Story.callExternalFunction: a lookahead safe function fails inside choice text
// and "{...}" strings, and an unsafe one is let through. This subclass lives in Blade Ink's package so it can override
// the package-private method: safe functions run unguarded, unsafe ones get the upstream rule.
public class HdgifeStory internal constructor(json: String) : Story(json) {
    private val safeFunctions = mutableMapOf<String, ExternalFunction<*>>()

    internal fun bindLookaheadSafe(name: String, function: ExternalFunction<*>) = bindExternalFunction(name, function, true)

    override fun bindExternalFunction(funcName: String, func: ExternalFunction<*>, lookaheadSafe: Boolean) {
        super.bindExternalFunction(funcName, func, lookaheadSafe)
        if (lookaheadSafe) safeFunctions[funcName] = func
    }

    internal fun outputText(): String = state.currentText

    override fun unbindExternalFunction(funcName: String) {
        super.unbindExternalFunction(funcName)
        safeFunctions.remove(funcName)
    }

    override fun callExternalFunction(funcName: String, numberOfArguments: Int) {
        val function = safeFunctions[funcName]
        if (function == null) {
            if (getExternalFunction(funcName) != null && state.inStringEvaluation()) {
                error(
                    "External function $funcName could not be called because 1) it wasn't marked as lookaheadSafe when it was bound " +
                        "and 2) the story is in the middle of string generation, either because choice text is being generated, " +
                        "or because you have ink like \"hello {func()}\". Generate the result into a temporary variable before " +
                        "the string or choice: ~ temp x = $funcName()",
                )
                return
            }
            super.callExternalFunction(funcName, numberOfArguments)
            return
        }
        val arguments = arrayOfNulls<Any>(numberOfArguments)
        for (i in numberOfArguments - 1 downTo 0) {
            arguments[i] = (state.popEvaluationStack() as Value<*>).valueObject
        }
        val returned = function.call(*arguments)
        state.pushEvaluationStack(if (returned == null) Void() else AbstractValue.create(returned)
            ?: throw StoryException("Could not create an ink value from ${returned.javaClass.name} returned by $funcName"))
    }

    public companion object {
        @JvmStatic
        public fun from(story: Story): HdgifeStory = story as? HdgifeStory ?: HdgifeStory(story.toJson())
    }
}
