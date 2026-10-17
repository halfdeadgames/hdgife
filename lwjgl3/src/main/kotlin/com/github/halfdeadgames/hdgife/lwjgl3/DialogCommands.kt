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

    override fun hasStat(entityId: String, statName: String): Boolean = stats.containsKey("$entityId.$statName")
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
    fun setNpcStat(entity: String, stat: String, value: Float)
    fun talk(knot: String, speaker: String?)
    fun seed(value: Int)
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

    /** Sets the variable using its current type in the story. */
    fun set(name: String, value: String) {
        val current = control.getVariable(name)
        if (current == null) {
            control.log("$name is not a declared variable")
            return
        }
        val type = when (current) {
            is Int -> "int"
            is Float -> "float"
            is Boolean -> "bool"
            is String -> "string"
            else -> {
                control.log("$name is a ${current.javaClass.simpleName}, which set can't handle. Use an explicit type")
                return
            }
        }
        assign(name, value, type)
    }

    /** Sets the variable with an explicit type: int, float, bool or string. */
    fun set(name: String, value: String, type: String) {
        if (control.getVariable(name) == null) {
            control.log("$name is not a declared variable")
            return
        }
        assign(name, value, type)
    }

    private fun assign(name: String, text: String, type: String) {
        val parsed: Any? = when (type.lowercase()) {
            "int" -> text.toIntOrNull()
            "float" -> text.toFloatOrNull()
            "bool", "boolean" -> when (text.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            "string", "str" -> text
            else -> {
                control.log("Unknown type '$type'. Use int, float, bool or string")
                return
            }
        }
        if (parsed == null) {
            control.log("Cannot read '$text' as $type, $name unchanged")
            return
        }
        try {
            control.setVariable(name, parsed)
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

    /** Sets a stat on the provider only. Ink reads it live, no sync needed. */
    fun npc(entity: String, stat: String, value: String) {
        val number = value.toFloatOrNull()
        if (number == null) control.log("Stat value must be a number") else control.setNpcStat(entity, stat, number)
    }

    fun talk(knot: String) = control.talk(knot, null)

    fun talk(knot: String, speaker: String) = control.talk(knot, speaker)

    fun seed(n: String) {
        val value = n.toIntOrNull()
        if (value == null) control.log("Seed must be a whole number") else control.seed(value)
    }

    fun choose(n: String) {
        val index = n.toIntOrNull()
        if (index == null) control.log("Choice must be a number") else control.choose(index)
    }

    fun divert(path: String) = control.divert(path)

    fun reload() = control.reload()

    fun restart() = control.restart()
}
