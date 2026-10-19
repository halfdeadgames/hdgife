package com.github.halfdeadgames.hdgife.story

import com.bladecoder.ink.runtime.Container
import com.bladecoder.ink.runtime.SimpleJson
import com.bladecoder.ink.runtime.Story

/** What [SaveEnvelope.read] found in a save written by `StoryManager.saveState`. */
internal class SavedState(
    val inkJson: String,
    val styles: List<Style>,
    val speaker: String?,
    val storyId: String?,
    val fingerprint: String?,
)

/** The versioned save format around ink's own state JSON, and the structure fingerprint saves can carry. */
internal object SaveEnvelope {
    const val VERSION = 2

    /** Writes the envelope. Version 2 when [storyId] or [fingerprint] is given, else version 1. */
    fun write(inkJson: String, styles: List<Style>, speaker: String?, storyId: String?, fingerprint: String?): String {
        val writer = SimpleJson.Writer()
        writer.writeObjectStart()
        writer.writeProperty("v", if (storyId != null || fingerprint != null) 2 else 1)
        writer.writeProperty("ink", inkJson)
        writer.writePropertyStart("styles")
        writer.writeArrayStart()
        for (style in styles) {
            writer.writeObjectStart()
            writer.writeProperty("name", style.name)
            style.value?.let { writer.writeProperty("value", it) }
            if (style.params.isNotEmpty()) {
                writer.writePropertyStart("params")
                writer.writeObjectStart()
                for ((key, value) in style.params) writer.writeProperty(key, value)
                writer.writeObjectEnd()
                writer.writePropertyEnd()
            }
            writer.writeObjectEnd()
        }
        writer.writeArrayEnd()
        writer.writePropertyEnd()
        speaker?.let { writer.writeProperty("speaker", it) }
        storyId?.let { writer.writeProperty("storyId", it) }
        fingerprint?.let { writer.writeProperty("fingerprint", it) }
        writer.writeObjectEnd()
        return writer.toString()
    }

    /**
     * Reads [json]. Null when it is a plain ink state (it has no `v` and `ink` keys). Throws [SaveStateException]
     * for a missing or unsupported version, a missing ink state or a broken style entry.
     */
    fun read(json: String): SavedState? {
        val envelope = parse(json) ?: return null
        val version = (envelope["v"] as? Number)?.toInt() ?: throw SaveStateException("Save has no version")
        if (version !in 1..VERSION) {
            throw SaveStateException("Save version $version is not supported (this engine reads up to $VERSION)")
        }
        val inkJson = envelope["ink"] as? String ?: throw SaveStateException("Save has no ink state")
        val styles = readStyles(envelope["styles"])
        return SavedState(
            inkJson,
            styles,
            envelope["speaker"] as? String,
            envelope["storyId"] as? String,
            envelope["fingerprint"] as? String,
        )
    }

    private fun parse(json: String): Map<String, Any?>? {
        val parsed = try {
            SimpleJson.textToDictionary(json)
        } catch (e: Exception) {
            return null // not ours; ink reports the problem when it loads it
        }
        return if (parsed.containsKey("v") && parsed.containsKey("ink")) parsed else null
    }

    private fun readStyles(raw: Any?): List<Style> {
        val list = raw as? List<*> ?: return emptyList()
        return list.map { item ->
            val map = item as? Map<*, *> ?: throw SaveStateException("Save has a broken style entry")
            val params = (map["params"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }
            Style(
                map["name"] as? String ?: throw SaveStateException("Save has a style without a name"),
                map["value"] as? String,
                params ?: emptyMap(),
            )
        }
    }

    /** A hash of the sorted names of the story's containers (knots, stitches, labels) and variables. */
    fun fingerprint(story: Story): String {
        val names = ArrayList<String>()
        fun walk(container: Container, prefix: String) {
            val named = container.namedContent ?: return
            for ((name, content) in named) {
                names += prefix + name
                if (content is Container) walk(content, "$prefix$name.")
            }
        }
        walk(story.mainContentContainer, "")
        for (variable in story.variablesState) names += "$ $variable"
        names.sort()
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (name in names) {
            for (c in name) hash = (hash xor c.code.toLong()) * 0x100000001b3L
            hash = (hash xor 10L) * 0x100000001b3L
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}
