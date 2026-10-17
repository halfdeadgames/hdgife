package com.github.halfdeadgames.hdgife

/** Receives warnings and notes from the engine. The engine logs nothing unless you set one. */
fun interface HdgifeLog {
    fun log(message: String)
}
