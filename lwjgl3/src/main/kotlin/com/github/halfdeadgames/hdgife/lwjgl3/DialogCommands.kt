package com.github.halfdeadgames.hdgife.lwjgl3

import com.github.halfdeadgames.hdgife.state.NarrativeStateProvider
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.strongjoshua.console.CommandExecutor

/** Mutable stat map for testing. Keys are "entity.stat". */
class MapStateProvider : NarrativeStateProvider {
    private val stats = HashMap<String, Float>()

    fun setStat(entityId: String, statName: String, value: Float) {
        stats["$entityId.$statName"] = value
    }

    override fun getStat(entityId: String, statName: String): Float = stats["$entityId.$statName"] ?: 0f
}

/** Sets the stat, syncs it to ink and returns the ink variable name. Whole numbers go in as Int so they print without ".0". */
fun applyStat(manager: StoryManager, provider: MapStateProvider, entity: String, stat: String, value: Float): String {
    provider.setStat(entity, stat, value)
    manager.syncStatsToInk(entity, listOf(stat))
    val name = StoryManager.defaultVariableName(entity, stat)
    val variables = manager.story.variablesState
    if (value % 1f == 0f && variables.get(name) != null) variables.set(name, value.toInt())
    return name
}

/** What the console commands need from the screen. */
interface DialogControl {
    fun log(message: String)
    fun listVariables(): List<String>
    fun getVariable(name: String): Any?
    fun setVariable(name: String, value: Any)
    fun setStat(entity: String, stat: String, value: Float)
    fun choose(index: Int)
    fun divert(path: String)
    fun reload()
    fun restart()
}

/** Console commands. `help` comes from the base class. Args arrive as strings and are parsed here. */
class DialogCommands(private val control: DialogControl) : CommandExecutor() {

    fun vars() {
        val names = control.listVariables()
        if (names.isEmpty()) control.log("No variables") else names.forEach { control.log("$it = ${control.getVariable(it)}") }
    }

    fun get(name: String) {
        val value = control.getVariable(name)
        control.log(if (value == null) "$name is not a declared variable" else "$name = $value")
    }

    fun set(name: String, value: String) {
        try {
            control.setVariable(name, parseValue(value))
            control.log("$name = ${control.getVariable(name)}")
        } catch (e: Exception) {
            control.log("Cannot set $name: ${e.message}")
        }
    }

    fun stat(entity: String, stat: String, value: String) {
        val number = value.toFloatOrNull()
        if (number == null) control.log("Stat value must be a number") else control.setStat(entity, stat, number)
    }

    /** Shortcut for `stat player hp <value>`. */
    fun hp(value: String) {
        val number = value.toFloatOrNull()
        if (number == null) control.log("HP must be a number") else control.setStat("player", "hp", number)
    }

    fun choose(n: String) {
        val index = n.toIntOrNull()
        if (index == null) control.log("Choice must be a number") else control.choose(index)
    }

    fun divert(path: String) = control.divert(path)

    fun reload() = control.reload()

    fun restart() = control.restart()

    private fun parseValue(text: String): Any =
        text.toIntOrNull() ?: text.toFloatOrNull() ?: when (text.lowercase()) {
            "true" -> true
            "false" -> false
            else -> text
        }
}
