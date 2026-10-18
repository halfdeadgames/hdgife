package com.github.halfdeadgames.hdgife

/** Receives warnings and notes from the engine. The engine logs nothing unless you set one. */
public fun interface HdgifeLog {
    public fun log(message: String)
}
