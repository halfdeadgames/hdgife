package com.github.halfdeadgames.hdgife.harness

import com.github.halfdeadgames.hdgife.story.NumberFormat
import com.github.halfdeadgames.hdgife.story.Rounding

enum class Demeanor { Calm, Wary, Friendly, Erratic, Hostile }

const val startCoin = 12

fun MapStateProvider.loadShowcaseStats() {
    clear()
    setStat("varga", "sanity", 70f)
    setStat("varga", "tiredness", 55f)
    setStat("varga", "mood", 40f)
    setStat("varga", "trust", 25.5f)
    setStat("varga", "faction", "crown")
    setStat("varga", "is_injured", false)
    setStat("varga", "stance", Demeanor.Wary)
    setStat("ilse", "sanity", 85f)
    setStat("ilse", "tiredness", 30f)
    setStat("ilse", "mood", 65f)
    setStat("ilse", "trust", 60.25f)
    setStat("ilse", "faction", "guild")
    setStat("ilse", "is_injured", true)
    setStat("ilse", "stance", Demeanor.Friendly)
    setStat("tobin", "sanity", 22f)
    setStat("tobin", "tiredness", 80f)
    setStat("tobin", "mood", 35f)
    setStat("tobin", "trust", 10)
    setStat("tobin", "faction", "lamplighters")
    setStat("tobin", "is_injured", false)
    setStat("tobin", "stance", Demeanor.Erratic)
    setStat("player", "hp", 10)
    setStat("player", "grit", 7)
    setStat("player", "wits", 6)
    setStat("player", "charm", 4.5f)
    setStat("player", "coin", startCoin)
    setStat("player", "faction", "drifter")
    setStat("player", "is_injured", false)
    setStat("player", "rank", 2)
    setStat("guard", "sanity", 80f)
    setStat("guard", "tiredness", 20f)
    setStat("guard", "mood", 60f)
    setStat("guard", "strength", 12f)
    setStat("guard", "faction", "crown")
    setStat("merchant", "mood", 70f)
    setStat("merchant", "strength", 4f)
    setStat("merchant", "faction", "guild")
}

val playerSyncedStats = listOf("hp", "coin")

fun harnessFormatOverride(entityId: String, statName: String): NumberFormat? =
    if (statName == "trust" && entityId != "tobin") NumberFormat(1, Rounding.Round) else null
