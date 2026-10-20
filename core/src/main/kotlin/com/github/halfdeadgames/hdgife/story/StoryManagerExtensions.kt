package com.github.halfdeadgames.hdgife.story

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

public operator fun StoryManager.get(name: String): Any? = getVariable(name)

public operator fun StoryManager.set(name: String, value: Any): Unit = setVariable(name, value)

public fun StoryManager.overrideFormat(override: (entityId: String, statName: String) -> NumberFormat?) {
    formatOverride = NumberFormatOverride(override)
}

public fun StoryManager.convertValues(converter: (Any) -> Any?) {
    valueConverter = ValueConverter(converter)
}

public class InkVariable<T : Any> internal constructor(
    private val manager: StoryManager,
    private val variableName: String?,
    private val type: KClass<T>,
) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val name = variableName ?: property.name
        return manager.getVariable(name, type) ?: throw IllegalArgumentException("'$name' is not a variable declared in the story")
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        manager.setVariable(variableName ?: property.name, value)
    }
}

public fun <T : Any> StoryManager.inkVariable(type: KClass<T>, name: String? = null): InkVariable<T> = InkVariable(this, name, type)

public inline fun <reified T : Any> StoryManager.inkVariable(name: String? = null): InkVariable<T> = inkVariable(T::class, name)
