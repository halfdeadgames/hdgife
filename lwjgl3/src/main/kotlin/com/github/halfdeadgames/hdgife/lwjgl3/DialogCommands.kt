package com.github.halfdeadgames.hdgife.lwjgl3

import com.github.halfdeadgames.hdgife.state.NarrativeStateProvider
import com.github.halfdeadgames.hdgife.story.Rounding
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.strongjoshua.console.CommandExecutor

/** Mutable stat map for testing. Values can be any type; the engine maps them to ink. Keys are "entity.stat". */
class MapStateProvider : NarrativeStateProvider {
    private val stats = mutableMapOf<String, Any>()

    fun setStat(entityId: String, statName: String, value: Any) {
        stats["$entityId.$statName"] = value
    }

    fun clear() = stats.clear()

    override fun getStat(entityId: String, statName: String): Any? = stats["$entityId.$statName"]
}

/** Reads console text as the most specific value: whole number, decimal, `true`/`false`, else a string. */
fun parseValue(text: String): Any =
    text.toIntOrNull() ?: text.toFloatOrNull() ?: when (text.lowercase()) {
        "true" -> true
        "false" -> false
        else -> text
    }

/** Sets the stat, syncs it to ink and returns the ink variable name. */
fun applyStat(manager: StoryManager, provider: MapStateProvider, entity: String, stat: String, value: Any): String {
    provider.setStat(entity, stat, value)
    manager.syncStats(entity, listOf(stat))
    return manager.variableNamer(entity, stat)
}

/** What the console commands need from the screen. */
interface DialogControl {
    fun log(message: String)
    fun listVariables(): List<String>
    fun getVariable(name: String): Any?
    fun setVariable(name: String, value: Any)
    fun observe(name: String)
    fun unobserve(name: String)
    fun setStat(entity: String, stat: String, value: Any)
    fun setNpcStat(entity: String, stat: String, value: Any)
    fun talk(knot: String, speaker: String?)
    fun seed(value: Int)
    fun setNumberFormat(decimals: Int, rounding: Rounding)
    fun choose(index: Int)
    fun divert(path: String)
    fun reload()
    fun restart()
    fun setChoiceMode(mode: ChoiceMode)
    /** Null cycles to the next layout. */
    fun setViewMode(mode: ViewMode?)
    /** Makes the bound `roll(sides)` return [value] every time, or random again when null. */
    fun fixRoll(value: Int?)
    fun resetStats()
    fun save()
    fun load()
    fun validate()
}

/** Console commands. `help` comes from the base class. Args arrive as strings and are parsed here. */
class DialogCommands(private val control: DialogControl) : CommandExecutor() {

    fun vars() {
        val names = control.listVariables()
        if (names.isEmpty()) control.log("No variables") else for (name in names) control.log("$name = ${control.getVariable(name)}")
    }

    /** Reads one ink variable through the engine's variable API and shows its type. */
    fun `var`(name: String) {
        val value = control.getVariable(name)
        control.log(if (value == null) "$name is not a declared variable" else "$name = $value (${value.javaClass.simpleName})")
    }

    /** Logs every change of an ink variable, with the old and new value. */
    fun observe(name: String) = control.observe(name)

    /** Stops logging changes of an ink variable. */
    fun unobserve(name: String) = control.unobserve(name)

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

    /** Sets a stat on the provider and syncs it to ink. The value can be a number, `true`/`false` or text. */
    fun stat(entity: String, stat: String, value: String) = control.setStat(entity, stat, parseValue(value))

    /** Shortcut for `stat player hp <value>`. */
    fun hp(value: String) = control.setStat("player", "hp", parseValue(value))

    /** Sets a stat on the provider only. Ink reads it live, no sync needed. The value can be a number, `true`/`false` or text. */
    fun npc(entity: String, stat: String, value: String) = control.setNpcStat(entity, stat, parseValue(value))

    fun talk(knot: String) = control.talk(knot, null)

    fun talk(knot: String, speaker: String) = control.talk(knot, speaker)

    fun seed(n: String) {
        val value = n.toIntOrNull()
        if (value == null) control.log("Seed must be a whole number") else control.seed(value)
    }

    /** Changes the default number format used by `stat_text`. Mode is floor, ceil, round or trunc. */
    fun numfmt(decimals: String, mode: String) {
        val places = decimals.toIntOrNull()
        val rounding = Rounding.parse(mode)
        when {
            places == null || places !in 0..9 -> control.log("Decimals must be 0 to 9")
            rounding == null -> control.log("Mode must be floor, ceil, round or trunc")
            else -> control.setNumberFormat(places, rounding)
        }
    }

    fun choose(n: String) {
        val index = n.toIntOrNull()
        if (index == null) control.log("Choice must be a number") else control.choose(index)
    }

    fun divert(path: String) = control.divert(path)

    /** How choices with unmet requirements are shown: hide, grey or show. */
    fun choicemode(mode: String) {
        val parsed = ChoiceMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
        if (parsed == null) control.log("Mode must be hide, grey or show") else control.setChoiceMode(parsed)
    }

    /** Cycles the layout: split, raw, rendered. The F2 key does the same. */
    fun view() = control.setViewMode(null)

    /** Sets the layout: split (raw and rendered side by side), raw or rendered. */
    fun view(mode: String) {
        val parsed = ViewMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
        if (parsed == null) control.log("Mode must be split, raw or rendered") else control.setViewMode(parsed)
    }

    /** Makes the game's `roll(sides)` always give this number, or `off` for random again. */
    fun fixroll(value: String) {
        val n = value.toIntOrNull()
        when {
            value.equals("off", ignoreCase = true) -> control.fixRoll(null)
            n == null -> control.log("fixroll takes a number or off")
            else -> control.fixRoll(n)
        }
    }

    /** Puts every stat back to the harness defaults (the story's cast and the player). */
    fun resetstats() = control.resetStats()

    /** Keeps the story state (JSON) in memory. */
    fun save() = control.save()

    /** Restores the last `save`. */
    fun load() = control.load()

    /** Checks the story and its bindings and lists what is wrong or risky. */
    fun validate() = control.validate()

    fun reload() = control.reload()

    fun restart() = control.restart()
}
