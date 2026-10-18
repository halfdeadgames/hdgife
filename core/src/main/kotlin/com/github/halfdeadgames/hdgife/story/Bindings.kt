package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Story

/**
 * The arguments ink passed to a function bound with `StoryManager.bindFunction`. Values arrive as ink gave
 * them: `Int`, `Float`, `Boolean` or `String`. The typed getters read one argument and coerce the obvious cases
 * (an int where a float is wanted, anything where a string is wanted) and throw [IllegalArgumentException]
 * naming the function and the position when an argument is missing or of the wrong type.
 */
class FunctionArgs internal constructor(val function: String, val values: List<Any?>) {
    val size: Int get() = values.size

    /** Argument [index] as it came from ink, or null when there is none. */
    operator fun get(index: Int): Any? = values.getOrNull(index)

    fun string(index: Int): String = need(index, "a string") { it.toString() }

    fun int(index: Int): Int = need(index, "an int") { if (it is Int) it else null }

    /** A float. An ink int is accepted. */
    fun float(index: Int): Float = need(index, "a float") { (it as? Number)?.toFloat() }

    /** A bool. An ink int is accepted, nonzero is true. */
    fun bool(index: Int): Boolean = need(index, "a bool") { if (it is Boolean) it else (it as? Int)?.let { n -> n != 0 } }

    private fun <T : Any> need(index: Int, what: String, convert: (Any) -> T?): T {
        val value = values.getOrNull(index)
            ?: throw IllegalArgumentException("$function: argument $index is missing")
        return convert(value) ?: throw IllegalArgumentException(
            "$function: argument $index is ${value.javaClass.simpleName} ($value), expected $what",
        )
    }

    override fun toString() = "$function(${values.joinToString()})"
}

/**
 * A live `StoryManager.observe` subscription. Stop it with `StoryManager.unobserve` or [close].
 * [name] is the observed ink variable.
 */
class VariableObservation internal constructor(
    val name: String,
    internal val block: (old: Any?, new: Any?) -> Unit,
    private val owner: StoryManager,
) : AutoCloseable {
    internal var last: Any? = null
    internal lateinit var observer: Story.VariableObserver

    /** False once unobserved. */
    var active: Boolean = true
        internal set

    override fun close() = owner.unobserve(this)
}
