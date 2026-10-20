package com.github.halfdeadgames.hdgife.ktx

import com.github.halfdeadgames.hdgife.story.StoryEvent
import com.github.halfdeadgames.hdgife.story.StoryManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlin.reflect.KClass

public fun StoryManager.eventFlow(capacity: Int = Channel.BUFFERED): Flow<StoryEvent> = callbackFlow {
    val registration = addEventListener { trySend(it) }
    awaitClose { registration.close() }
}.buffer(capacity)

// The listener stays registered until the manager is closed, so the flow sees events from the moment of this call.
public fun StoryManager.eventSharedFlow(replay: Int = 0, extraBufferCapacity: Int = 64): SharedFlow<StoryEvent> {
    val shared = MutableSharedFlow<StoryEvent>(replay = replay, extraBufferCapacity = extraBufferCapacity)
    addEventListener { shared.tryEmit(it) }
    return shared.asSharedFlow()
}

public inline fun <reified T : Any> StoryManager.observeAsFlow(name: String): Flow<T?> = observeVariableFlow(name, T::class)

@PublishedApi
internal fun <T : Any> StoryManager.observeVariableFlow(name: String, type: KClass<T>): Flow<T?> = callbackFlow {
    fun current(): T? = try {
        getVariable(name, type)
    } catch (_: IllegalArgumentException) {
        null
    }
    trySend(current())
    val observation = observe(name) { _, _ -> trySend(current()) }
    awaitClose { observation.close() }
}.buffer(Channel.UNLIMITED)
