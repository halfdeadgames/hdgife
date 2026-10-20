package com.github.halfdeadgames.hdgife.story

import kotlin.reflect.KClass

private val supportedArgumentTypes: Set<KClass<*>> = setOf(String::class, Int::class, Float::class, Double::class, Boolean::class)

public class EventRouter internal constructor(manager: StoryManager) : AutoCloseable {
    private val handlers = HashMap<String, (StoryEvent) -> Unit>()
    private var unhandled: (StoryEvent) -> Unit = { event -> manager.log?.log("Unhandled story event '${event.name}'") }
    internal var registration: AutoCloseable? = null

    public fun on(name: String, handler: () -> Unit) {
        addHandler(name, emptyList()) { handler() }
    }

    public inline fun <reified A : Any> on(name: String, crossinline handler: (A) -> Unit) {
        addHandler(name, listOf(A::class)) { event -> handler(event.arg<A>(0)) }
    }

    public inline fun <reified A : Any, reified B : Any> on(name: String, crossinline handler: (A, B) -> Unit) {
        addHandler(name, listOf(A::class, B::class)) { event -> handler(event.arg<A>(0), event.arg<B>(1)) }
    }

    public inline fun <reified A : Any, reified B : Any, reified C : Any> on(name: String, crossinline handler: (A, B, C) -> Unit) {
        addHandler(name, listOf(A::class, B::class, C::class)) { event -> handler(event.arg<A>(0), event.arg<B>(1), event.arg<C>(2)) }
    }

    public fun fallback(handler: (StoryEvent) -> Unit) {
        unhandled = handler
    }

    @PublishedApi
    internal fun addHandler(name: String, argumentTypes: List<KClass<*>>, handler: (StoryEvent) -> Unit) {
        for ((index, type) in argumentTypes.withIndex()) {
            require(type in supportedArgumentTypes) {
                "Event '$name': argument $index as ${type.simpleName} is not supported, use String, Int, Float, Double or Boolean"
            }
        }
        require(handlers.put(name, handler) == null) { "Event '$name' already has a handler" }
    }

    internal fun dispatch(event: StoryEvent) {
        (handlers[event.name] ?: unhandled)(event)
    }

    override fun close() {
        registration?.close()
        registration = null
    }
}

public fun StoryManager.events(configure: EventRouter.() -> Unit): EventRouter {
    val router = EventRouter(this).apply(configure)
    router.registration = addEventListener(StoryEventListener(router::dispatch))
    return router
}

public inline fun <reified T : Any> StoryEvent.arg(index: Int): T = argAs(index, T::class)

public fun <T : Any> StoryEvent.argAs(index: Int, type: KClass<T>): T {
    val converted: Any = when (type) {
        String::class -> string(index)
        Int::class -> int(index)
        Float::class -> float(index)
        Double::class -> float(index).toCleanDouble()
        Boolean::class -> bool(index)
        else -> throw IllegalArgumentException("event '$name': argument $index cannot be read as ${type.simpleName}")
    }
    @Suppress("UNCHECKED_CAST")
    return converted as T
}
