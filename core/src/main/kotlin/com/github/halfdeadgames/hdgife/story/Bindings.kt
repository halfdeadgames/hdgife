package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Story

public class FunctionArgs internal constructor(public val function: String, public val values: List<Any?>) {
    public val size: Int get() = values.size

    public operator fun get(index: Int): Any? = values.getOrNull(index)

    private val reader = ArgReader(function, values)

    public fun string(index: Int): String = reader.string(index)

    public fun int(index: Int): Int = reader.int(index)

    public fun float(index: Int): Float = reader.float(index)

    public fun bool(index: Int): Boolean = reader.bool(index)

    override fun toString(): String = "$function(${values.joinToString()})"
}

public class VariableObservation internal constructor(
    public val name: String,
    internal val block: (old: Any?, new: Any?) -> Unit,
    private val owner: StoryManager,
) : AutoCloseable {
    internal var last: Any? = null
    internal lateinit var observer: Story.VariableObserver

    public var isActive: Boolean = true
        internal set

    override fun close(): Unit = owner.unobserve(this)
}

internal class ArgReader(private val label: String, private val values: List<Any?>) {
    fun string(index: Int): String = need(index, "a string") { it.toString() }

    fun int(index: Int): Int = need(index, "an int") { if (it is Int) it else null }

    fun float(index: Int): Float = need(index, "a float") { (it as? Number)?.toFloat() }

    fun bool(index: Int): Boolean = need(index, "a bool") { if (it is Boolean) it else (it as? Int)?.let { number -> number != 0 } }

    private fun <T : Any> need(index: Int, what: String, convert: (Any) -> T?): T {
        val value = values.getOrNull(index)
            ?: throw IllegalArgumentException("$label: argument $index is missing")
        return convert(value) ?: throw IllegalArgumentException(
            "$label: argument $index is ${value.javaClass.simpleName} ($value), expected $what",
        )
    }
}
