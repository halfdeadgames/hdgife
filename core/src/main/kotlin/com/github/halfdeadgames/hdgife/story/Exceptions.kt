package com.github.halfdeadgames.hdgife.story

import com.github.halfdeadgames.hdgife.HdgifeException

public class SaveStateException internal constructor(message: String, cause: Throwable? = null) : HdgifeException(message, cause)

public class IncompatibleLibraryException internal constructor(
    message: String,
    public val found: Int,
    public val expected: Int = com.github.halfdeadgames.hdgife.HdgifeInk.version,
) : HdgifeException(message)

public class StoryPathException internal constructor(public val path: String, cause: Throwable) :
    HdgifeException("Cannot start the scene at '$path': ${cause.message}", cause)
