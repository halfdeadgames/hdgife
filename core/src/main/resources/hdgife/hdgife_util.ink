// hdgife math helpers. Optional: `INCLUDE hdgife_util.ink`, with or without hdgife.ink and in any order. It needs
// nothing from hdgife.ink and declares nothing that hdgife.ink declares.
//
// Plain ink, no EXTERNAL, so it works without a StoryManager. Parameters start with hdg_.
// Reserved names: chance, clamp, avg2, avg3, blend, invert, scale100 and everything starting with hdgife_.
// Don't define your own with these names. validateStory reports one that is defined without this file.

// True with a pct percent chance (0 or less never, 100 or more always).
=== function chance(hdg_pct) ===
~ return RANDOM(1, 100) <= hdg_pct

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

// Marks that this file is part of the story, so validateStory can tell it from a function of your own. Leave it alone.
=== function hdgife_util_loaded() ===
~ return true
