// hdgife helper library. Add `INCLUDE hdgife.ink` at the top of your main ink file.
//
// The stat and number-format functions are EXTERNAL: StoryManager binds them to your
// NarrativeStateProvider and number format settings. The ink bodies below are fallbacks that run when
// nothing is bound (no StoryManager), so a story using this library still runs on its own:
// stats read as 0 and has_stat is false.
//
// stat and stat_of always give a float, so ink math does not depend on whether a value is whole.

// All parameters and temps in this file start with hdg_, so your story can declare VARs like n, x or name.
// Don't declare your own knots or variables starting with hdg_ or hdgife_.
//
// Current speaker, kept here so it is part of the saved ink state. Set it with StoryManager.speaker or
// speak() below.
VAR hdgife_speaker = ""

EXTERNAL stat(hdg_name)
EXTERNAL stat_of(hdg_entity, hdg_name)
EXTERNAL has_stat(hdg_name)
EXTERNAL stat_text(hdg_name)
EXTERNAL stat_text_of(hdg_entity, hdg_name)
EXTERNAL fmt(hdg_x, hdg_decimals, hdg_mode)
EXTERNAL speak(hdg_name)

// Stat of the current speaker (see StoryManager.beginScene). 0 if missing.
=== function stat(hdg_name) ===
~ return 0.0

// Stat of any entity. 0 if missing.
=== function stat_of(hdg_entity, hdg_name) ===
~ return 0.0

// Makes this entity the current speaker until changed: ~ speak("guard"). Use "" for no speaker.
// StoryManager does not bind this one. The ink body is the implementation, so the speaker lives in
// hdgife_speaker, is saved with the story and is safe during ink's lookahead. Change who `stat()`
// reads, and the speaker reported on following lines. A `# speaker: name` tag only names one line.
=== function speak(hdg_name) ===
~ hdgife_speaker = hdg_name

// Number display. Use these inline: {stat_text("strength")} or {fmt(avg3(a, b, c), 1, "round")}.
//
// stat_text / stat_text_of print a stat with the game's format for it (StoryManager.numberFormat and
// formatOverride). fmt formats any number: mode is "floor", "ceil", "round" (ties away from zero) or
// "trunc". Bound to a StoryManager they are exact and always use a "." decimal point.
//
// Fallbacks (no StoryManager): the same modes done with ink math. Whole results are exact. With
// decimals the digits can be off in the last place because ink floats are binary. They print their text
// directly, so call them inline and not as `~ temp t = fmt(...)`. Decimals above 6 are not supported.
// stat_text falls back to 0 decimals, round.
=== function stat_text(hdg_name) ===
{fmt(stat(hdg_name), 0, "round")}

=== function stat_text_of(hdg_entity, hdg_name) ===
{fmt(stat_of(hdg_entity, hdg_name), 0, "round")}

// Uses FLOOR, CEILING and INT, which need the fix in InkNativePatch (applied by the compiler service and
// StoryManager). Ties round away from zero.
=== function fmt(hdg_x, hdg_decimals, hdg_mode) ===
~ temp hdg_s = hdgife_pow10(hdg_decimals)
~ temp hdg_v = hdg_x * hdg_s * 1.0
~ temp hdg_n = 0
{
- hdg_mode == "floor": ~ hdg_n = INT(FLOOR(hdg_v))
- hdg_mode == "ceil" or hdg_mode == "ceiling": ~ hdg_n = INT(CEILING(hdg_v))
- hdg_mode == "trunc" or hdg_mode == "truncate": ~ hdg_n = INT(hdg_v)
- hdg_v < 0: ~ hdg_n = INT(CEILING(hdg_v - 0.5))
- else: ~ hdg_n = INT(FLOOR(hdg_v + 0.5))
}
{
- hdg_decimals <= 0: {hdg_n}
- hdg_n < 0: {"-"}{hdgife_digits(0 - hdg_n, hdg_s, hdg_decimals)}
- else: {hdgife_digits(hdg_n, hdg_s, hdg_decimals)}
}

// Rounds down to a whole number and prints it.
=== function floor_text(hdg_x) ===
{fmt(hdg_x, 0, "floor")}

// Rounds to the nearest value with the given decimals and prints it.
=== function round_text(hdg_x, hdg_decimals) ===
{fmt(hdg_x, hdg_decimals, "round")}

=== function hdgife_pow10(hdg_n) ===
{
- hdg_n <= 0: ~ return 1
- else: ~ return 10 * hdgife_pow10(hdg_n - 1)
}

// Prints a non-negative scaled integer as "whole.fraction" with zero padding.
=== function hdgife_digits(hdg_n, hdg_s, hdg_decimals) ===
{hdg_n / hdg_s}.{hdgife_zeros(hdg_decimals, hdg_n mod hdg_s)}{hdg_n mod hdg_s}

// Zeros needed in front of hdg_frac so it has hdg_width digits.
=== function hdgife_zeros(hdg_width, hdg_frac) ===
{
- hdg_width <= 1: ~ return ""
- hdg_frac >= hdgife_pow10(hdg_width - 1): ~ return ""
- else: ~ return "0" + hdgife_zeros(hdg_width - 1, hdg_frac)
}

// True if the current speaker has this stat.
=== function has_stat(hdg_name) ===
~ return false

// True with a pct percent chance (0 or less never, 100 or more always).
=== function chance(hdg_pct) ===
~ return RANDOM(1, 100) <= hdg_pct

// Limits x to the range lo..hi.
=== function clamp(hdg_x, hdg_lo, hdg_hi) ===
{
- hdg_x < hdg_lo: ~ return hdg_lo
- hdg_x > hdg_hi: ~ return hdg_hi
- else: ~ return hdg_x
}

// Plain averages. Values are floats, so no integer truncation.
=== function avg2(hdg_a, hdg_b) ===
~ return (hdg_a + hdg_b) / 2.0

=== function avg3(hdg_a, hdg_b, hdg_c) ===
~ return (hdg_a + hdg_b + hdg_c) / 3.0

// Weighted blend of two values: (a*wa + b*wb) / (wa + wb).
=== function blend(hdg_a, hdg_wa, hdg_b, hdg_wb) ===
~ return (hdg_a * hdg_wa + hdg_b * hdg_wb) / (hdg_wa + hdg_wb + 0.0)

// Flips a 0..100 value, so low becomes high. Handy for "low mood" style checks.
=== function invert(hdg_x) ===
~ return 100 - hdg_x

// Maps x from the range lo..hi onto 0..100, clamped.
=== function scale100(hdg_x, hdg_lo, hdg_hi) ===
~ return clamp((hdg_x - hdg_lo) * 100.0 / (hdg_hi - hdg_lo), 0, 100)

// Used by StoryManager.seedRandom. Seeds RANDOM and shuffles.
=== function hdgife_seed(hdg_n) ===
~ SEED_RANDOM(hdg_n)
~ return 0
