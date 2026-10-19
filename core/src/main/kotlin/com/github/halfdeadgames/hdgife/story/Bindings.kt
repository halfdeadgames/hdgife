package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Story

/**
 * The arguments ink passed to a function bound with `StoryManager.bindFunction`. Values arrive as ink gave
 * them: `Int`, `Float`, `Boolean` or `String`. The typed getters read one argument and coerce the obvious cases
 * (an int where a float is wanted, anything where a string is wanted) and throw [IllegalArgumentException]
 * naming the function and the position when an argument is missing or of the wrong type.
 */
public class FunctionArgs internal constructor(public val function: String, public val values: List<Any?>) {
    /** How many arguments ink passed. */
    public val size: Int get() = values.size

    /** Argument [index] as it came from ink, or null when there is none. */
    public operator fun get(index: Int): Any? = values.getOrNull(index)

    private val reader = ArgReader(function, values)

    /** Argument [index] as a string. Any value is accepted (`toString()`). */
    public fun string(index: Int): String = reader.string(index)

    /** Argument [index] as an int. Only an ink int is accepted. */
    public fun int(index: Int): Int = reader.int(index)

    /** A float. An ink int is accepted. */
    public fun float(index: Int): Float = reader.float(index)

    /** A bool. An ink int is accepted, nonzero is true. */
    public fun bool(index: Int): Boolean = reader.bool(index)

    override fun toString(): String = "$function(${values.joinToString()})"
}

/**
 * A live `StoryManager.observe` subscription. Stop it with `StoryManager.unobserve` or [close].
 * [name] is the observed ink variable.
 */
public class VariableObservation internal constructor(
    public val name: String,
    internal val block: (old: Any?, new: Any?) -> Unit,
    private val owner: StoryManager,
) : AutoCloseable {
    internal var last: Any? = null
    internal lateinit var observer: Story.VariableObserver

    /** False once unobserved. */
    public var isActive: Boolean = true
        internal set

    override fun close(): Unit = owner.unobserve(this)
}

/** The typed argument getters shared by [FunctionArgs] and [StoryEvent]. [label] names the owner in error messages. */
internal class ArgReader(private val label: String, private val values: List<Any?>) {
    fun string(index: Int): String = need(index, "a string") { it.toString() }

    fun int(index: Int): Int = need(index, "an int") { if (it is Int) it else null }

    fun float(index: Int): Float = need(index, "a float") { (it as? Number)?.toFloat() }

    fun bool(index: Int): Boolean = need(index, "a bool") { if (it is Boolean) it else (it as? Int)?.let { n -> n != 0 } }

    private fun <T : Any> need(index: Int, what: String, convert: (Any) -> T?): T {
        val value = values.getOrNull(index)
            ?: throw IllegalArgumentException("$label: argument $index is missing")
        return convert(value) ?: throw IllegalArgumentException(
            "$label: argument $index is ${value.javaClass.simpleName} ($value), expected $what",
        )
    }
}
