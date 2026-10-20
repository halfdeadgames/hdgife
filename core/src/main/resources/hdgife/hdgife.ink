// Parameters and temps start with hdg_. Do not declare your own knots, functions or variables starting with hdg_ or hdgife_.
// Reserved function names, not prefixed, so do not define your own: stat, stat_of, has_stat, has_stat_of, stat_text,
// stat_text_of, fmt, speak, emit, emit1, emit2, emit3, and everything in hdgife_util.ink.
// StoryManager and validateStory refuse a story whose version below differs from the engine's. A missing version is 0.
CONST hdgife_version = 2
VAR hdgife_speaker = ""
VAR hdgife_event_seq = 0

EXTERNAL stat(hdg_name)
EXTERNAL stat_of(hdg_entity, hdg_name)
EXTERNAL has_stat(hdg_name)
EXTERNAL has_stat_of(hdg_entity, hdg_name)
EXTERNAL stat_text(hdg_name)
EXTERNAL stat_text_of(hdg_entity, hdg_name)
EXTERNAL fmt(hdg_x, hdg_decimals, hdg_mode)
EXTERNAL hdgife_emit0(hdg_seq, hdg_name)
EXTERNAL hdgife_emit1(hdg_seq, hdg_name, hdg_a)
EXTERNAL hdgife_emit2(hdg_seq, hdg_name, hdg_a, hdg_b)
EXTERNAL hdgife_emit3(hdg_seq, hdg_name, hdg_a, hdg_b, hdg_c)
EXTERNAL hdgife_speak(hdg_name)

// Events: call as statements, ~ emit1("give_item", "sword"). Not in choice text, where they fire when the choice is created.
=== function emit(hdg_name) ===
~ hdgife_event_seq++
~ hdgife_emit0(hdgife_event_seq, hdg_name)

=== function emit1(hdg_name, hdg_a) ===
~ hdgife_event_seq++
~ hdgife_emit1(hdgife_event_seq, hdg_name, hdg_a)

=== function emit2(hdg_name, hdg_a, hdg_b) ===
~ hdgife_event_seq++
~ hdgife_emit2(hdgife_event_seq, hdg_name, hdg_a, hdg_b)

=== function emit3(hdg_name, hdg_a, hdg_b, hdg_c) ===
~ hdgife_event_seq++
~ hdgife_emit3(hdgife_event_seq, hdg_name, hdg_a, hdg_b, hdg_c)

=== function hdgife_emit0(hdg_seq, hdg_name) ===
~ return 0

=== function hdgife_emit1(hdg_seq, hdg_name, hdg_a) ===
~ return 0

=== function hdgife_emit2(hdg_seq, hdg_name, hdg_a, hdg_b) ===
~ return 0

=== function hdgife_emit3(hdg_seq, hdg_name, hdg_a, hdg_b, hdg_c) ===
~ return 0

=== function hdgife_speak(hdg_name) ===
~ return 0

=== function stat(hdg_name) ===
~ return 0.0

=== function stat_of(hdg_entity, hdg_name) ===
~ return 0.0

=== function speak(hdg_name) ===
~ hdgife_speaker = hdg_name
~ hdgife_speak(hdg_name)

=== function stat_text(hdg_name) ===
{fmt(stat(hdg_name), 0, "round")}

=== function stat_text_of(hdg_entity, hdg_name) ===
{fmt(stat_of(hdg_entity, hdg_name), 0, "round")}

// TODO: the ink fallback of fmt supports at most 6 decimals and prints directly, so call it inline, not as `~ temp t = fmt(...)`.
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

=== function hdgife_pow10(hdg_n) ===
{
- hdg_n <= 0: ~ return 1
- else: ~ return 10 * hdgife_pow10(hdg_n - 1)
}

=== function hdgife_digits(hdg_n, hdg_s, hdg_decimals) ===
{hdg_n / hdg_s}.{hdgife_zeros(hdg_decimals, hdg_n mod hdg_s)}{hdg_n mod hdg_s}

=== function hdgife_zeros(hdg_width, hdg_frac) ===
{
- hdg_width <= 1: ~ return ""
- hdg_frac >= hdgife_pow10(hdg_width - 1): ~ return ""
- else: ~ return "0" + hdgife_zeros(hdg_width - 1, hdg_frac)
}

=== function has_stat(hdg_name) ===
~ return false

=== function has_stat_of(hdg_entity, hdg_name) ===
~ return false

=== function hdgife_seed(hdg_n) ===
~ SEED_RANDOM(hdg_n)
~ return 0

=== function hdgife_lib_version() ===
~ return hdgife_version
