package com.github.halfdeadgames.hdgife.ktx

import com.github.halfdeadgames.hdgife.story.StoryEventListener
import com.github.halfdeadgames.hdgife.story.set
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StoryFlowsTest {
    private val twoStepText = """
        INCLUDE hdgife.ink
        VAR hp = 10
        Hi
        ~ emit("one")
        * [Go]
          ~ emit("two")
          -> END
    """.trimIndent()

    @Test
    fun `eventFlow emits each event of a step in order`() = runBlocking {
        val manager = managerOf()
        val collected = async(start = CoroutineStart.UNDISPATCHED) { manager.eventFlow().take(2).toList() }
        yield()
        manager.next()
        assertEquals(listOf("giveItem", "ping"), collected.await().map { it.name })
    }

    @Test
    fun `eventFlow leaves the primary listener alone while collecting`() = runBlocking {
        val manager = managerOf()
        val direct = ArrayList<String>()
        val original = StoryEventListener { direct += it.name }
        manager.onEvent = original
        val collected = async(start = CoroutineStart.UNDISPATCHED) { manager.eventFlow().take(2).toList() }
        yield()
        assertSame(original, manager.onEvent)
        manager.next()
        assertEquals(listOf("giveItem", "ping"), direct)
        assertEquals(direct, collected.await().map { it.name })
        assertSame(original, manager.onEvent)
    }

    @Test
    fun `eventFlow keeps working when the game replaces the primary listener while collecting`() = runBlocking {
        val manager = managerOf()
        val collected = async(start = CoroutineStart.UNDISPATCHED) { manager.eventFlow().take(2).toList() }
        yield()
        val replacement = ArrayList<String>()
        manager.onEvent = StoryEventListener { replacement += it.name }
        manager.next()
        assertEquals(listOf("giveItem", "ping"), replacement)
        assertEquals(listOf("giveItem", "ping"), collected.await().map { it.name })
    }

    @Test
    fun `two event flows stop independently in either order`() = runBlocking {
        for (cancelFirstFirst in listOf(true, false)) {
            val manager = managerOf(twoStepText)
            val first = ArrayList<String>()
            val second = ArrayList<String>()
            val firstJob = launch(start = CoroutineStart.UNDISPATCHED) { manager.eventFlow().collect { first += it.name } }
            val secondJob = launch(start = CoroutineStart.UNDISPATCHED) { manager.eventFlow().collect { second += it.name } }
            yield()
            manager.next()
            yield()
            val (cancelled, kept) = if (cancelFirstFirst) firstJob to secondJob else secondJob to firstJob
            cancelled.cancelAndJoin()
            manager.choose(0)
            yield()
            kept.cancelAndJoin()
            val (cancelledSeen, keptSeen) = if (cancelFirstFirst) first to second else second to first
            assertEquals(listOf("one"), cancelledSeen)
            assertEquals(listOf("one", "two"), keptSeen)
        }
    }

    @Test
    fun `a cancelled event flow unregisters its listener`() = runBlocking {
        val manager = managerOf(twoStepText)
        val seen = ArrayList<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { manager.eventFlow().collect { seen += it.name } }
        job.cancelAndJoin()
        manager.next()
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `eventSharedFlow sees events emitted before anyone collects`() = runBlocking {
        val manager = managerOf()
        val shared = manager.eventSharedFlow(replay = 2)
        manager.next()
        assertEquals(listOf("giveItem", "ping"), shared.replayCache.map { it.name })
        assertEquals(listOf("giveItem", "ping"), shared.take(2).toList().map { it.name })
    }

    @Test
    fun `eventSharedFlow without replay delivers to a collector that was already waiting`() = runBlocking {
        val manager = managerOf()
        val shared = manager.eventSharedFlow()
        val collected = async(start = CoroutineStart.UNDISPATCHED) { shared.take(2).toList() }
        manager.next()
        assertEquals(listOf("giveItem", "ping"), collected.await().map { it.name })
    }

    @Test
    fun `observeAsFlow emits the current value and then every change`() = runBlocking {
        val manager = managerOf()
        val collected = async(start = CoroutineStart.UNDISPATCHED) { manager.observeAsFlow<Int>("hp").take(3).toList() }
        yield()
        manager["hp"] = 7
        manager["hp"] = 3
        assertEquals(listOf<Int?>(10, 7, 3), collected.await())
    }

    @Test
    fun `observeAsFlow emits null when the value has another type`() = runBlocking {
        val manager = managerOf()
        val collected = async(start = CoroutineStart.UNDISPATCHED) { manager.observeAsFlow<String>("hp").take(1).toList() }
        assertEquals(listOf<String?>(null), collected.await())
    }

    @Test
    fun `observeAsFlow widens an int variable like getVariable does`() = runBlocking {
        val manager = managerOf()
        val collected = async(start = CoroutineStart.UNDISPATCHED) { manager.observeAsFlow<Float>("hp").take(3).toList() }
        yield()
        manager["hp"] = 7
        manager["hp"] = 3
        assertEquals(listOf<Float?>(10f, 7f, 3f), collected.await())
    }
}
