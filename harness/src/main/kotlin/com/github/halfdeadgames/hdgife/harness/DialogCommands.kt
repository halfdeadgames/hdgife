package com.github.halfdeadgames.hdgife.harness

import com.github.halfdeadgames.hdgife.state.NarrativeStateProvider
import com.github.halfdeadgames.hdgife.story.Rounding
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.strongjoshua.console.CommandExecutor

class MapStateProvider : NarrativeStateProvider {
    private val stats = mutableMapOf<String, Any>()

    fun setStat(entityId: String, statName: String, value: Any) {
        stats["$entityId.$statName"] = value
    }

    fun clear() = stats.clear()

    override fun getStat(entityId: String, statName: String): Any? = stats["$entityId.$statName"]
}

fun parseValue(text: String): Any =
    text.toIntOrNull() ?: text.toFloatOrNull() ?: when (text.lowercase()) {
        "true" -> true
        "false" -> false
        else -> text
    }

fun applyStat(manager: StoryManager, provider: MapStateProvider, entityId: String, statName: String, value: Any): String {
    provider.setStat(entityId, statName, value)
    manager.syncStats(entityId, listOf(statName))
    return manager.variableNamer.nameFor(entityId, statName)
}

interface DialogControl {
    fun log(message: String)
    fun listVariables(): List<String>
    fun getVariable(name: String): Any?
    fun setVariable(name: String, value: Any)
    fun observe(name: String)
    fun unobserve(name: String)
    fun setStat(entityId: String, statName: String, value: Any)
    fun setNpcStat(entityId: String, statName: String, value: Any)
    fun talk(path: String, speaker: String?)
    fun seed(value: Int)
    fun setNumberFormat(decimals: Int, rounding: Rounding)
    fun choose(index: Int)
    fun divert(path: String)
    fun reload()
    fun restart()
    fun setChoiceMode(mode: ChoiceMode)
    fun setViewMode(mode: ViewMode?)
    fun fixRoll(value: Int?)
    fun resetStats()
    fun save()
    fun load()
    fun validate()
}

class DialogCommands(private val control: DialogControl) : CommandExecutor() {

    fun vars() {
        val names = control.listVariables()
        if (names.isEmpty()) control.log("No variables") else for (name in names) control.log("$name = ${control.getVariable(name)}")
    }

    fun `var`(name: String) {
        val value = control.getVariable(name)
        control.log(if (value == null) "$name is not a declared variable" else "$name = $value (${value.javaClass.simpleName})")
    }

    fun observe(name: String) = control.observe(name)

    fun unobserve(name: String) = control.unobserve(name)

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
        } catch (e: IllegalArgumentException) {
            control.log("Cannot set $name: ${e.message}")
        }
    }

    fun stat(entityId: String, statName: String, value: String) = control.setStat(entityId, statName, parseValue(value))

    fun npc(entityId: String, statName: String, value: String) = control.setNpcStat(entityId, statName, parseValue(value))

    fun talk(path: String) = control.talk(path, null)

    fun talk(path: String, speaker: String) = control.talk(path, speaker)

    fun seed(seedText: String) {
        val value = seedText.toIntOrNull()
        if (value == null) control.log("Seed must be a whole number") else control.seed(value)
    }

    fun numfmt(decimals: String, mode: String) {
        val places = decimals.toIntOrNull()
        val rounding = Rounding.parse(mode)
        when {
            places == null || places !in 0..9 -> control.log("Decimals must be 0 to 9")
            rounding == null -> control.log("Mode must be floor, ceil, round or trunc")
            else -> control.setNumberFormat(places, rounding)
        }
    }

    fun choose(indexText: String) {
        val index = indexText.toIntOrNull()
        if (index == null) control.log("Choice must be a number") else control.choose(index)
    }

    fun divert(path: String) = control.divert(path)

    fun choicemode(mode: String) {
        val parsed = ChoiceMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
        if (parsed == null) control.log("Mode must be hide, grey or show") else control.setChoiceMode(parsed)
    }

    fun view() = control.setViewMode(null)

    fun view(mode: String) {
        val parsed = ViewMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
        if (parsed == null) control.log("Mode must be split, raw or rendered") else control.setViewMode(parsed)
    }

    fun fixroll(value: String) {
        val fixed = value.toIntOrNull()
        when {
            value.equals("off", ignoreCase = true) -> control.fixRoll(null)
            fixed == null -> control.log("fixroll takes a number or off")
            else -> control.fixRoll(fixed)
        }
    }

    fun resetstats() = control.resetStats()

    fun save() = control.save()

    fun load() = control.load()

    fun validate() = control.validate()

    fun reload() = control.reload()

    fun restart() = control.restart()
}
