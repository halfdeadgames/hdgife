package com.github.halfdeadgames.hdgife.harness

import com.github.halfdeadgames.hdgife.story.NumberFormat
import com.github.halfdeadgames.hdgife.story.Rounding

enum class TestMode { Steady, Erratic, Hostile }

const val startCoins = 12

const val showcaseMaxOpenStyles = 4

fun MapStateProvider.loadShowcaseStats() {
    clear()
    setStat("tester", "intStat", 7)
    setStat("tester", "floatStat", 2.75f)
    setStat("tester", "textStat", "alpha")
    setStat("tester", "flagStat", true)
    setStat("tester", "modeStat", TestMode.Steady)
    setStat("tester", "energy", 80f)
    setStat("tester", "calm", 60f)
    setStat("tester", "focus", 30f)
    setStat("subject", "intStat", 3)
    setStat("subject", "floatStat", 0.5f)
    setStat("subject", "textStat", "beta")
    setStat("subject", "flagStat", false)
    setStat("subject", "modeStat", TestMode.Erratic)
    setStat("subject", "two words", "a b")
    setStat("player", "coins", startCoins)
    setStat("player", "health", 10)
}

val playerSyncedStats = listOf("coins", "health")

fun harnessFormatOverride(entityId: String, statName: String): NumberFormat? =
    if (entityId == "tester" && statName == "floatStat") NumberFormat(2, Rounding.Round) else null
