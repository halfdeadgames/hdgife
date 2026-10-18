package com.github.halfdeadgames.hdgife

import com.bladecoder.ink.runtime.HdgifeStory
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.compiler.InkSource
import com.github.halfdeadgames.hdgife.state.NarrativeStateProvider
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.StoryManager

/** Stat provider backed by a map. Keys are entity to stat. */
class MapStats : NarrativeStateProvider {
    private val values = HashMap<Pair<String, String>, Any?>()

    operator fun set(entity: String, stat: String, value: Any?) {
        values[entity to stat] = value
    }

    override fun getStat(entityId: String, statName: String): Any? = values[entityId to statName]
}

/** Collects log messages. */
class LogSink : HdgifeLog {
    val messages = ArrayList<String>()
    override fun log(message: String) {
        messages += message
    }
}

val compiler = InkCompilerService()

/** Compiles one ink text. Add `INCLUDE hdgife.ink` yourself when the test needs the library. */
fun compileInk(text: String): HdgifeStory = compiler.compile(InkSource("main.ink", text))

/** Compiles text that starts with `INCLUDE hdgife.ink`. */
fun compileLib(text: String): HdgifeStory = compileInk("INCLUDE hdgife.ink\n$text")

fun managerOf(text: String, stats: NarrativeStateProvider? = null, lib: Boolean = true): StoryManager =
    StoryManager(if (lib) compileLib(text) else compileInk(text), stats)

fun DialogStep.texts(): List<String> = lines.map { it.text }
