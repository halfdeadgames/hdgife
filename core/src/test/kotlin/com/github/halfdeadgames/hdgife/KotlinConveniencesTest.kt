package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.state.mapStatProvider
import com.github.halfdeadgames.hdgife.story.NumberFormat
import com.github.halfdeadgames.hdgife.story.Rounding
import com.github.halfdeadgames.hdgife.story.StoryEvent
import com.github.halfdeadgames.hdgife.story.StoryStepException
import com.github.halfdeadgames.hdgife.story.arg
import com.github.halfdeadgames.hdgife.story.events
import com.github.halfdeadgames.hdgife.story.overrideFormat
import com.github.halfdeadgames.hdgife.story.get
import com.github.halfdeadgames.hdgife.story.inkVariable
import com.github.halfdeadgames.hdgife.story.set
import com.github.halfdeadgames.hdgife.story.convertValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinConveniencesTest {
    private val variableInk = """
        VAR hp = 10
        VAR name = "Ada"
        VAR ratio = 0.5
        VAR alive = true
        Hi
        -> END
    """.trimIndent()

    private val eventInk = """
        Start
        ~ emit1("giveItem", "sword")
        ~ emit2("spend", "gold", 5)
        ~ emit3("mix", "a", 2, 1.5)
        ~ emit("ping")
        ~ emit("stray")
        -> END
    """.trimIndent()

    @Test
    fun `square brackets read and write story variables`() {
        val manager = managerOf(variableInk)
        assertEquals(10, manager["hp"])
        manager["hp"] = 4
        assertEquals(4, manager["hp"])
        assertFailsWith<IllegalArgumentException> { manager["missing"] = 1 }
    }

    @Test
    fun `a delegate falls back to the property name`() {
        val manager = managerOf(variableInk)
        var hp by manager.inkVariable<Int>()
        var ratio by manager.inkVariable<Float>()
        assertEquals(10, hp)
        hp = 3
        assertEquals(3, manager["hp"])
        assertEquals(0.5f, ratio)
        ratio = 2f
        assertEquals(2f, manager["ratio"])
    }

    @Test
    fun `a delegate with an explicit name widens int to double`() {
        val manager = managerOf(variableInk)
        val health by manager.inkVariable<Double>("hp")
        val title by manager.inkVariable<String>("name")
        assertEquals(10.0, health)
        assertEquals("Ada", title)
    }

    @Test
    fun `a delegate throws for an undeclared variable or a wrong type`() {
        val manager = managerOf(variableInk)
        val missing by manager.inkVariable<Int>()
        val wrongType by manager.inkVariable<Boolean>("hp")
        assertFailsWith<IllegalArgumentException> { missing }
        assertFailsWith<IllegalArgumentException> { wrongType }
    }

    @Test
    fun `lambda setters install the listener, format override and converter`() {
        val stats = mapStatProvider(mapOf("hero" to mapOf("gold" to 3.456f, "pet" to Pet)))
        val manager = managerOf("{stat_text_of(\"hero\", \"gold\")} {stat_text_of(\"hero\", \"pet\")}\n-> END", stats)
        val seen = ArrayList<String>()
        manager.addEventListener { seen += it.name }
        manager.overrideFormat { _, statName -> if (statName == "gold") NumberFormat(decimals = 1, rounding = Rounding.Round) else null }
        manager.convertValues { if (it === Pet) "cat" else null }
        assertEquals("3.5 cat", manager.next().lines.single().text)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a router calls typed handlers and the fallback`() {
        val manager = managerOf(eventInk)
        val seen = ArrayList<String>()
        manager.events {
            on("giveItem") { item: String -> seen += "give $item" }
            on("spend") { what: String, amount: Int -> seen += "spend $what $amount" }
            on("mix") { text: String, whole: Int, fraction: Float -> seen += "mix $text $whole $fraction" }
            on("ping") { seen += "ping" }
            fallback { seen += "unhandled ${it.name}" }
        }
        manager.next()
        assertEquals(listOf("give sword", "spend gold 5", "mix a 2 1.5", "ping", "unhandled stray"), seen)
    }

    @Test
    fun `a router rejects a second handler for one event`() {
        val manager = managerOf(eventInk)
        assertFailsWith<IllegalArgumentException> {
            manager.events {
                on("ping") { }
                on("ping") { }
            }
        }
    }

    @Test
    fun `a handler with the wrong argument type fails the step`() {
        val manager = managerOf(eventInk)
        manager.events {
            on("giveItem") { item: Int -> println(item) }
        }
        assertFailsWith<StoryStepException> { manager.next() }
    }

    @Test
    fun `event arguments convert to the requested type`() {
        val manager = managerOf(eventInk)
        val events = ArrayList<StoryEvent>()
        manager.addEventListener { events += it }
        manager.next()
        assertEquals("sword", events[0].arg<String>(0))
        assertEquals(5, events[1].arg<Int>(1))
        assertEquals(1.5, events[2].arg<Double>(2))
        assertFailsWith<IllegalArgumentException> { events[0].arg<Long>(0) }
    }

    @Test
    fun `a closed observation stops reporting`() {
        val manager = managerOf(variableInk)
        val changes = ArrayList<Any?>()
        manager.observe("hp") { _, new -> changes += new }.use { observation ->
            manager["hp"] = 1
            assertTrue(observation.isActive)
        }
        manager["hp"] = 2
        assertEquals(listOf<Any?>(1), changes)
        val observation = manager.observe("hp") { _, _ -> }
        observation.close()
        assertFalse(observation.isActive)
    }

    @Test
    fun `mapStatProvider reads the live map and returns null for unknowns`() {
        val stats = mutableMapOf<String, Map<String, Any?>>("hero" to mapOf("hp" to 7))
        val provider = mapStatProvider(stats)
        assertEquals(7, provider.getStat("hero", "hp"))
        assertEquals(null, provider.getStat("hero", "mana"))
        assertEquals(null, provider.getStat("ghost", "hp"))
        stats["ghost"] = mapOf("hp" to 1)
        assertEquals(1, provider.getStat("ghost", "hp"))
    }

    private object Pet
}
