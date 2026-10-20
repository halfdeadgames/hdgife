// Reserved function names: chance, clamp, avg2, avg3, blend, invert, scale100, and everything starting with hdgife_. Parameters start with hdg_.
=== function chance(hdg_pct) ===
~ return RANDOM(1, 100) <= hdg_pct

=== function clamp(hdg_x, hdg_lo, hdg_hi) ===
{
- hdg_x < hdg_lo: ~ return hdg_lo
- hdg_x > hdg_hi: ~ return hdg_hi
- else: ~ return hdg_x
}

=== function avg2(hdg_a, hdg_b) ===
~ return (hdg_a + hdg_b) / 2.0

=== function avg3(hdg_a, hdg_b, hdg_c) ===
~ return (hdg_a + hdg_b + hdg_c) / 3.0

=== function blend(hdg_a, hdg_wa, hdg_b, hdg_wb) ===
~ return (hdg_a * hdg_wa + hdg_b * hdg_wb) / (hdg_wa + hdg_wb + 0.0)

=== function invert(hdg_x) ===
~ return 100 - hdg_x

=== function scale100(hdg_x, hdg_lo, hdg_hi) ===
~ return clamp((hdg_x - hdg_lo) * 100.0 / (hdg_hi - hdg_lo), 0, 100)

=== function hdgife_util_loaded() ===
~ return true
