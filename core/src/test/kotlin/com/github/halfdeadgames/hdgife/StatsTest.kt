package com.github.halfdeadgames.hdgife

import com.github.halfdeadgames.hdgife.story.NumberFormat
import com.github.halfdeadgames.hdgife.story.Rounding
import com.github.halfdeadgames.hdgife.story.StoryManager
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class StatsTest {
    private enum class Mood { CALM, ANGRY }

    private data class Money(val amount: Int)

    private val savedLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    private fun stat(value: Any?, expression: String = "{stat_of(\"p\", \"s\")}"): String {
        val stats = MapStats().also { it["p", "s"] = value }
        return managerOf(expression, stats).next().texts().single()
    }

    // ---- value mapping ----

    @Test
    fun `an Int stat is an ink int`() {
        assertEquals("3", stat(7, "{stat_of(\"p\", \"s\") / 2}"))
    }

    @Test
    fun `a Float stat is an ink float`() {
        assertEquals("3.5", stat(7f, "{stat_of(\"p\", \"s\") / 2}"))
    }

    @Test
    fun `a Double stat is an ink float`() {
        assertEquals("3.5", stat(7.0, "{stat_of(\"p\", \"s\") / 2}"))
    }

    @Test
    fun `Long Short and Byte stats in Int range are ink ints`() {
        assertEquals("3", stat(7L, "{stat_of(\"p\", \"s\") / 2}"))
        assertEquals("3", stat(7.toShort(), "{stat_of(\"p\", \"s\") / 2}"))
        assertEquals("3", stat(7.toByte(), "{stat_of(\"p\", \"s\") / 2}"))
    }

    @Test
    fun `a Long outside the Int range is an ink float`() {
        assertEquals("3.0E9", stat(3_000_000_000L))
    }

    @Test
    fun `a Boolean stat is an ink bool`() {
        assertEquals("yes", stat(true, "{stat_of(\"p\", \"s\"): yes|no}"))
        assertEquals("no", stat(false, "{stat_of(\"p\", \"s\"): yes|no}"))
    }

    @Test
    fun `a String stat is an ink string`() {
        assertEquals("crown", stat("crown"))
        assertEquals("yes", stat("crown", "{stat_of(\"p\", \"s\") == \"crown\": yes|no}"))
    }

    @Test
    fun `CharSequence and Char stats are ink strings`() {
        assertEquals("abc", stat(StringBuilder("abc")))
        assertEquals("x", stat('x'))
    }

    @Test
    fun `an Enum stat is its name`() {
        assertEquals("ANGRY", stat(Mood.ANGRY))
    }

    @Test
    fun `an unmapped value uses toString`() {
        assertEquals("Money(amount=5)", stat(Money(5)))
    }

    @Test
    fun `an unmapped value goes through the valueConverter`() {
        val stats = MapStats().also { it["p", "s"] = Money(5) }
        val m = managerOf("{stat_of(\"p\", \"s\") + 1}", stats)
        m.valueConverter = { (it as? Money)?.amount }
        assertEquals("6", m.next().texts().single())
    }

    @Test
    fun `a valueConverter returning null falls back to toString`() {
        val stats = MapStats().also { it["p", "s"] = Money(5) }
        val m = managerOf("{stat_of(\"p\", \"s\")}", stats)
        m.valueConverter = { null }
        assertEquals("Money(amount=5)", m.next().texts().single())
    }

    @Test
    fun `a valueConverter result is mapped once more without the hook`() {
        val m = managerOf("Hi")
        m.valueConverter = { 7L }
        assertEquals(7, m.toInk(Money(1)))
        m.valueConverter = { Money(2) }
        assertEquals("Money(amount=2)", m.toInk(Money(1)))
    }

    @Test
    fun `valueConverter is used for setVariable`() {
        val m = managerOf("VAR gold = 0\n{gold}")
        m.valueConverter = { (it as Money).amount }
        m.setVariable("gold", Money(9))
        assertEquals(9, m.variable("gold"))
    }

    @Test
    fun `valueConverter is used for bound function results`() {
        val m = managerOf("{purse() + 1}\n-> END\nEXTERNAL purse()\n=== function purse() ===\n~ return 0")
        m.valueConverter = { (it as Money).amount }
        m.bindFunction("purse") { Money(4) }
        assertEquals("5", m.next().texts().single())
    }

    // ---- reading stats ----

    @Test
    fun `a missing stat reads as zero and has_stat is false`() {
        assertEquals("0.0 no", stat(null, "{stat_of(\"p\", \"s\")} {has_stat(\"s\"): yes|no}"))
    }

    @Test
    fun `stat and has_stat read the current speaker`() {
        val stats = MapStats().also { it["guard", "mood"] = 60 }
        val m = managerOf("{stat(\"mood\")} {has_stat(\"mood\"): yes|no}", stats)
        m.speaker = "guard"
        assertEquals("60 yes", m.next().texts().single())
    }

    @Test
    fun `stats are read live each time`() {
        val stats = MapStats().also { it["p", "hp"] = 1 }
        val m = managerOf("{stat_of(\"p\", \"hp\")}\n* [Go] {stat_of(\"p\", \"hp\")} -> END", stats)
        assertEquals("1", m.next().texts().single())
        stats["p", "hp"] = 2
        assertEquals("2", m.choose(0).texts().single())
    }

    @Test
    fun `without a provider stat gives zero`() {
        assertEquals("0.0 no", managerOf("{stat_of(\"p\", \"s\")} {has_stat(\"s\"): yes|no}").next().texts().single())
    }

    @Test
    fun `syncStatsToInk copies only declared variables and returns the skipped names`() {
        val stats = MapStats().also { it["player", "hp"] = 12; it["player", "mp"] = 3 }
        val m = managerOf("VAR player_hp = 0\n{player_hp}", stats)
        assertEquals(listOf("player_mp", "player_xp"), m.syncStatsToInk("player", listOf("hp", "mp", "xp")))
        assertEquals(12, m.variable("player_hp"))
    }

    // ---- number display ----

    @Test
    fun `NumberFormat applies every rounding mode to positive values`() {
        fun f(r: Rounding) = NumberFormat(1, r).format(7.65f)
        assertEquals("7.6", f(Rounding.FLOOR))
        assertEquals("7.7", f(Rounding.CEIL))
        assertEquals("7.7", f(Rounding.ROUND))
        assertEquals("7.6", f(Rounding.TRUNCATE))
    }

    @Test
    fun `NumberFormat applies every rounding mode to negative ties`() {
        fun f(r: Rounding) = NumberFormat(0, r).format(-2.5f)
        assertEquals("-3", f(Rounding.FLOOR))
        assertEquals("-2", f(Rounding.CEIL))
        assertEquals("-3", f(Rounding.ROUND))
        assertEquals("-2", f(Rounding.TRUNCATE))
    }

    @Test
    fun `NumberFormat rounds on the shortest decimal form of a float`() {
        assertEquals("7.7", NumberFormat(1).format(7.65f))
        assertEquals("1.01", NumberFormat(2).format(1.005f))
    }

    @Test
    fun `NumberFormat can trim trailing zeros`() {
        assertEquals("7.5", NumberFormat(2, trimTrailingZeros = true).format(7.5f))
        assertEquals("7", NumberFormat(2, trimTrailingZeros = true).format(7.0f))
        assertEquals("700", NumberFormat(2, trimTrailingZeros = true).format(700))
        assertEquals("7.50", NumberFormat(2).format(7.5f))
    }

    @Test
    fun `NumberFormat handles NaN and infinities`() {
        assertEquals("NaN", NumberFormat().format(Float.NaN))
        assertEquals("Infinity", NumberFormat().format(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", NumberFormat().format(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `NumberFormat formats integers exactly`() {
        assertEquals("9007199254740993", NumberFormat().format(9007199254740993L))
        assertEquals("5.00", NumberFormat(2).format(5))
    }

    @Test
    fun `NumberFormat rejects decimals out of range`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { NumberFormat(10) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { NumberFormat(-1) }
    }

    @Test
    fun `NumberFormat output ignores the default locale`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1234.57", NumberFormat(2).format(1234.567f))
        assertEquals("-0.5", NumberFormat(1).format(-0.5f))
    }

    @Test
    fun `Rounding parse accepts the ink names in any case`() {
        assertEquals(Rounding.FLOOR, Rounding.parse("FLOOR"))
        assertEquals(Rounding.CEIL, Rounding.parse("ceiling"))
        assertEquals(Rounding.ROUND, Rounding.parse(" round "))
        assertEquals(Rounding.TRUNCATE, Rounding.parse("trunc"))
        assertEquals(Rounding.TRUNCATE, Rounding.parse("truncate"))
        assertEquals(null, Rounding.parse("nearest"))
    }

    private fun statText(value: Any, format: NumberFormat, locale: Locale? = null): String {
        if (locale != null) Locale.setDefault(locale)
        val stats = MapStats().also { it["p", "s"] = value }
        val m = managerOf("{stat_text_of(\"p\", \"s\")}", stats)
        m.numberFormat = format
        return m.next().texts().single()
    }

    @Test
    fun `stat_text uses the number format for every rounding mode`() {
        assertEquals("7", statText(7.65f, NumberFormat(0, Rounding.FLOOR)))
        assertEquals("8", statText(7.65f, NumberFormat(0, Rounding.CEIL)))
        assertEquals("8", statText(7.65f, NumberFormat(0, Rounding.ROUND)))
        assertEquals("7", statText(7.65f, NumberFormat(0, Rounding.TRUNCATE)))
        assertEquals("-3", statText(-2.5f, NumberFormat(0, Rounding.ROUND)))
    }

    @Test
    fun `stat_text keeps a dot decimal point under a German locale`() {
        assertEquals("7.7", statText(7.65f, NumberFormat(1, Rounding.ROUND), Locale.GERMANY))
        assertEquals("1234.50", statText(1234.5, NumberFormat(2), Locale.GERMANY))
    }

    @Test
    fun `stat_text of a missing stat prints zero`() {
        val m = managerOf("{stat_text_of(\"p\", \"nope\")}", MapStats())
        m.numberFormat = NumberFormat(1)
        assertEquals("0.0", m.next().texts().single())
    }

    @Test
    fun `stat_text of a string stat prints the string`() {
        assertEquals("crown", statText("crown", NumberFormat(2)))
    }

    @Test
    fun `stat_text reads the current speaker`() {
        val stats = MapStats().also { it["guard", "mood"] = 60.26f }
        val m = managerOf("{stat_text(\"mood\")}", stats)
        m.speaker = "guard"
        m.numberFormat = NumberFormat(1)
        assertEquals("60.3", m.next().texts().single())
    }

    @Test
    fun `formatOverride wins for the stats it names`() {
        val stats = MapStats().also { it["p", "a"] = 1.234f; it["p", "b"] = 1.234f }
        val m = managerOf("{stat_text_of(\"p\", \"a\")} {stat_text_of(\"p\", \"b\")}", stats)
        m.formatOverride = { _, stat -> if (stat == "a") NumberFormat(2) else null }
        assertEquals("1.23 1", m.next().texts().single())
    }

    private fun fmt(x: String, decimals: Int, mode: String): String =
        managerOf("{fmt($x, $decimals, \"$mode\")}").next().texts().single()

    @Test
    fun `fmt supports every mode`() {
        assertEquals("7.6", fmt("7.65", 1, "floor"))
        assertEquals("7.7", fmt("7.61", 1, "ceil"))
        assertEquals("7.7", fmt("7.61", 1, "ceiling"))
        assertEquals("7.7", fmt("7.65", 1, "round"))
        assertEquals("7.6", fmt("7.69", 1, "trunc"))
        assertEquals("7.6", fmt("7.69", 1, "truncate"))
    }

    @Test
    fun `fmt rounds ties away from zero for negatives`() {
        assertEquals("-3", fmt("-2.5", 0, "round"))
        assertEquals("3", fmt("2.5", 0, "round"))
    }

    @Test
    fun `fmt with an unknown mode rounds and logs`() {
        val sink = LogSink()
        val m = managerOf("{fmt(2.5, 0, \"sideways\")}")
        m.log = sink
        assertEquals("3", m.next().texts().single())
        assertContains(sink.messages.joinToString(), "sideways")
    }

    @Test
    fun `fmt clamps decimals`() {
        assertEquals("1.000000000", fmt("1", 20, "round"))
    }

    @Test
    fun `fmt is locale independent`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("7.7", fmt("7.65", 1, "round"))
    }

    @Test
    fun `fmt falls back to ink math without a StoryManager`() {
        val story = compileLib("{fmt(2.5, 0, \"round\")} {fmt(-2.5, 0, \"round\")} {fmt(7.25, 1, \"floor\")} {fmt(3, 2, \"round\")}")
        assertEquals("3 -3 7.2 3.00", story.Continue().trim())
    }

    // ---- FLOOR / CEILING / INT patch ----

    private fun math(expression: String): String = compileInk("{$expression}").Continue().trim()

    @Test
    fun `FLOOR works on floats`() {
        assertEquals("2.0", math("FLOOR(2.5)"))
        assertEquals("-3.0", math("FLOOR(-2.5)"))
    }

    @Test
    fun `CEILING works on floats`() {
        assertEquals("3.0", math("CEILING(2.1)"))
        assertEquals("-2.0", math("CEILING(-2.5)"))
    }

    @Test
    fun `INT truncates toward zero`() {
        assertEquals("2", math("INT(2.9)"))
        assertEquals("-2", math("INT(-2.9)"))
    }

    @Test
    fun `FLOOR CEILING and INT work on a story loaded from compiled json`() {
        val story = compiler.loadCompiled(compileInk("{FLOOR(2.5)} {CEILING(2.5)} {INT(2.5)}").toJson())
        assertEquals("2.0 3.0 2", story.Continue().trim())
    }

    @Test
    fun `StoryManager applies the patch itself`() {
        val m = StoryManager(com.bladecoder.ink.runtime.Story(compileInk("{FLOOR(2.5)}").toJson()))
        assertEquals("2.0", m.next().texts().single())
    }
}
