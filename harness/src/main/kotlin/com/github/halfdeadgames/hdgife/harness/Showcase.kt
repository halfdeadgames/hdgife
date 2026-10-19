package com.github.halfdeadgames.hdgife.harness

import com.github.halfdeadgames.hdgife.story.NumberFormat
import com.github.halfdeadgames.hdgife.story.Rounding

/** An enum-like stat. The engine hands an enum to ink as its name, so ink and requirements see "WARY". */
enum class Demeanor { CALM, WARY, FRIENDLY, ERRATIC, HOSTILE }

/** The player's coin when a game starts. The story's `spend` event takes from it. */
const val START_COIN = 12

/**
 * The harness's game data for the showcase story (ink/main.ink) and the debug scenes (ink/debug.ink).
 * Replaces everything in the provider. `restart` does not call this, `resetstats` does.
 */
fun MapStateProvider.loadShowcaseStats() {
    clear()
    // the three people of the Lantern Pass: the same set of stats on each, with different types
    setStat("varga", "sanity", 70f)
    setStat("varga", "tiredness", 55f)
    setStat("varga", "mood", 40f)
    setStat("varga", "trust", 25.5f)                 // float
    setStat("varga", "faction", "crown")             // string
    setStat("varga", "is_injured", false)            // bool
    setStat("varga", "stance", Demeanor.WARY)        // enum
    setStat("ilse", "sanity", 85f)
    setStat("ilse", "tiredness", 30f)
    setStat("ilse", "mood", 65f)
    setStat("ilse", "trust", 60.25f)
    setStat("ilse", "faction", "guild")
    setStat("ilse", "is_injured", true)
    setStat("ilse", "stance", Demeanor.FRIENDLY)
    setStat("tobin", "sanity", 22f)
    setStat("tobin", "tiredness", 80f)
    setStat("tobin", "mood", 35f)
    setStat("tobin", "trust", 10)                    // int
    setStat("tobin", "faction", "lamplighters")
    setStat("tobin", "is_injured", false)
    setStat("tobin", "stance", Demeanor.ERRATIC)
    // the player: RPG stats and the things the game keeps
    setStat("player", "hp", 10)
    setStat("player", "grit", 7)
    setStat("player", "wits", 6)
    setStat("player", "charm", 4.5f)
    setStat("player", "coin", START_COIN)
    setStat("player", "faction", "drifter")
    setStat("player", "is_injured", false)
    setStat("player", "rank", 2)
    // the older debug scenes (debug.ink)
    setStat("guard", "sanity", 80f)
    setStat("guard", "tiredness", 20f)
    setStat("guard", "mood", 60f)
    setStat("guard", "strength", 12f)
    setStat("guard", "faction", "crown")
    setStat("merchant", "mood", 70f)
    setStat("merchant", "strength", 4f)
    setStat("merchant", "faction", "guild")
}

/** The ink variables that mirror player stats (`VAR player_hp`, `VAR player_coin`). */
val PLAYER_SYNCED_STATS = listOf("hp", "coin")

/**
 * The harness's per-stat number format: trust shows one decimal, except Tobin's whole-number trust. Everything
 * else uses `StoryManager.numberFormat`, which the `numfmt` command changes.
 */
fun harnessFormatOverride(entityId: String, statName: String): NumberFormat? =
    if (statName == "trust" && entityId != "tobin") NumberFormat(1, Rounding.ROUND) else null
