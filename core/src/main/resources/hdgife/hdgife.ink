// hdgife helper library. Add `INCLUDE hdgife.ink` at the top of your main ink file.
//
// The stat and number-format functions are EXTERNAL: StoryManager binds them to your
// NarrativeStateProvider and number format settings. The ink bodies below are fallbacks that run when
// nothing is bound (no StoryManager), so a story using this library still runs on its own:
// stats read as 0 and has_stat is false.
//
// stat and stat_of always give a float, so ink math does not depend on whether a value is whole.

EXTERNAL stat(name)
EXTERNAL stat_of(entity, name)
EXTERNAL has_stat(name)
EXTERNAL stat_text(name)
EXTERNAL stat_text_of(entity, name)
EXTERNAL fmt(x, decimals, mode)

// Stat of the current speaker (see StoryManager.beginScene). 0 if missing.
=== function stat(name) ===
~ return 0.0

// Stat of any entity. 0 if missing.
=== function stat_of(entity, name) ===
~ return 0.0

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
=== function stat_text(name) ===
{fmt(stat(name), 0, "round")}

=== function stat_text_of(entity, name) ===
{fmt(stat_of(entity, name), 0, "round")}

=== function fmt(x, decimals, mode) ===
~ temp s = hdgife_pow10(decimals)
~ temp v = x * s * 1.0
~ temp n = 0
{
- mode == "floor": ~ n = hdgife_floor(v)
- mode == "ceil" or mode == "ceiling": ~ n = (0 - hdgife_floor(0 - v))
- mode == "trunc" or mode == "truncate": ~ n = hdgife_trunc(v)
- v < 0: ~ n = 0 - hdgife_floor(0.5 - v)
- else: ~ n = hdgife_floor(v + 0.5)
}
{
- decimals <= 0: {n}
- n < 0: {"-"}{hdgife_digits(0 - n, s, decimals)}
- else: {hdgife_digits(n, s, decimals)}
}

// Rounds down to a whole number and prints it.
=== function floor_text(x) ===
{fmt(x, 0, "floor")}

// Rounds to the nearest value with the given decimals and prints it.
=== function round_text(x, decimals) ===
{fmt(x, decimals, "round")}

// Floor as an int, using only comparisons and arithmetic: FLOOR, CEILING and INT all crash on floats in
// Blade Ink 1.3.2. Finds the largest power of two not above v, then adds smaller powers while they fit.
=== function hdgife_floor(v) ===
{
- v >= 0: ~ return hdgife_floor_pos(v, hdgife_top_step(v, 1), 0)
- else:
    ~ temp whole = hdgife_floor_pos(0 - v, hdgife_top_step(0 - v, 1), 0)
    { whole < 0 - v:
        ~ return 0 - whole - 1
    - else:
        ~ return 0 - whole
    }
}

=== function hdgife_trunc(v) ===
{
- v < 0: ~ return 0 - hdgife_floor(0 - v)
- else: ~ return hdgife_floor(v)
}

=== function hdgife_top_step(v, p) ===
{
- p * 2 <= v: ~ return hdgife_top_step(v, p * 2)
- else: ~ return p
}

=== function hdgife_floor_pos(v, step, acc) ===
{
- step < 1: ~ return acc
- acc + step <= v: ~ return hdgife_floor_pos(v, step / 2, acc + step)
- else: ~ return hdgife_floor_pos(v, step / 2, acc)
}

=== function hdgife_pow10(n) ===
{
- n <= 0: ~ return 1
- else: ~ return 10 * hdgife_pow10(n - 1)
}

// Prints a non-negative scaled integer n as "whole.fraction" with zero padding.
=== function hdgife_digits(n, s, decimals) ===
{n / s}.{hdgife_zeros(decimals, n mod s)}{n mod s}

// Zeros needed in front of frac so it has `width` digits.
=== function hdgife_zeros(width, frac) ===
{
- width <= 1: ~ return ""
- frac >= hdgife_pow10(width - 1): ~ return ""
- else: ~ return "0" + hdgife_zeros(width - 1, frac)
}

// True if the current speaker has this stat.
=== function has_stat(name) ===
~ return false

// True with a pct percent chance (0 or less never, 100 or more always).
=== function chance(pct) ===
~ return RANDOM(1, 100) <= pct

// Limits x to the range lo..hi.
=== function clamp(x, lo, hi) ===
{
- x < lo: ~ return lo
- x > hi: ~ return hi
- else: ~ return x
}

// Plain averages. Values are floats, so no integer truncation.
=== function avg2(a, b) ===
~ return (a + b) / 2.0

=== function avg3(a, b, c) ===
~ return (a + b + c) / 3.0

// Weighted blend of two values: (a*wa + b*wb) / (wa + wb).
=== function blend(a, wa, b, wb) ===
~ return (a * wa + b * wb) / (wa + wb + 0.0)

// Flips a 0..100 value, so low becomes high. Handy for "low mood" style checks.
=== function invert(x) ===
~ return 100 - x

// Maps x from the range lo..hi onto 0..100, clamped.
=== function scale100(x, lo, hi) ===
~ return clamp((x - lo) * 100.0 / (hi - lo), 0, 100)

// Used by StoryManager.seedRandom. Seeds RANDOM and shuffles.
=== function hdgife_seed(n) ===
~ SEED_RANDOM(n)
~ return 0
