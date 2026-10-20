package com.github.halfdeadgames.hdgife.story

public fun interface NumberFormatOverride {
    public fun formatFor(entityId: String, statName: String): NumberFormat?
}

public fun interface ValueConverter {
    public fun convert(value: Any): Any?
}

public fun interface StoryEventListener {
    public fun onEvent(event: StoryEvent)
}

public fun interface VariableNamer {
    public fun nameFor(entityId: String, statName: String): String
}

public fun interface TextCleaner {
    public fun clean(text: String): String
}
