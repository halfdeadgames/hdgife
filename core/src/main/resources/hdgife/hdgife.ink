// hdgife helper library. Add `INCLUDE hdgife.ink` at the top of your main ink file.
//
// The three stat functions are EXTERNAL: StoryManager binds them to your NarrativeStateProvider.
// The ink bodies below are fallbacks that run when nothing is bound (no StoryManager, or no provider),
// so a story using this library still runs on its own: stats read as 0 and has_stat is false.

EXTERNAL stat(name)
EXTERNAL stat_of(entity, name)
EXTERNAL has_stat(name)

// Stat of the current speaker (see StoryManager.beginScene). 0 if missing.
=== function stat(name) ===
~ return 0

// Stat of any entity. 0 if missing.
=== function stat_of(entity, name) ===
~ return 0

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
