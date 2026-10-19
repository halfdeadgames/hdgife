// hdgife core library. Add `INCLUDE hdgife.ink` at the top of your main ink file. Optional math helpers are in
// hdgife_util.ink.
//
// The stat, number-format and event functions are EXTERNAL: StoryManager binds them to your
// NarrativeStateProvider, number format settings and event channel. The ink bodies below are fallbacks that run
// when nothing is bound (no StoryManager), so a story using this library still runs on its own:
// stats read as 0, has_stat and has_stat_of are false and emit does nothing.
//
// stat and stat_of give the type the game stores: an int stat is an ink int, a float stat a float, and a
// string, bool or enum stat a string or bool. Missing stats give 0.0. Ink integer division truncates, so use
// "/ 2.0" when averaging whole numbers.
//
// This file contains no scenes and no story content. It is a helper library only.

// All parameters and temps in this file start with hdg_, so your story can declare VARs like n, x or name.
// Don't declare your own knots or variables starting with hdg_ or hdgife_.
//
// Reserved names. Every function in this file is reserved, and so is every function of hdgife_util.ink. They are not
// prefixed, so don't define your own with these names, in a story that includes the library or one that does not
// (StoryManager takes over the external ones, and validateStory reports the rest):
//   stat, stat_of, has_stat, has_stat_of, stat_text, stat_text_of, fmt, speak, emit, emit1, emit2, emit3,
//   and everything starting with hdgife_.
//
// The library has a version, below. StoryManager and validateStory compare it with the version of the engine and
// refuse a story whose hdgife.ink is another one (recompile it). A missing version counts as 0, which is too old.
CONST hdgife_version = 2
//
// Current speaker, kept here so it is part of the saved ink state. Set it with StoryManager.speaker or
// speak() below.
VAR hdgife_speaker = ""
// Counts emit calls. It lets StoryManager keep each event once when ink runs ahead for glue. Leave it alone.
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

// Events for the game. Use as statements: ~ emit1("give_item", "sword")
// The name and arguments come back, in order, on DialogStep.events and StoryManager.onEvent. The engine does
// nothing with them, the game decides. emit takes just the name, emit1 one argument, emit2 two, emit3 three (ink
// has no overloads). Each call is reported exactly once, also between a line and a `<>` glue line, and also inside a
// function. Without a StoryManager they do nothing. In choice text they fire when the choice is created,
// which is not a good place for them, so keep them in statements.
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

// Fallbacks for the externals above, used when nothing is bound.
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

// Stat of the current speaker (see StoryManager.beginScene). 0 if missing.
=== function stat(hdg_name) ===
~ return 0.0

=== function stat_of(hdg_entity, hdg_name) ===
~ return 0.0

// Makes this entity the current speaker until changed: ~ speak("guard"). Use "" for no speaker.
// The speaker lives in hdgife_speaker, is saved with the story and is safe during ink's lookahead. It changes who
// `stat()` reads, and the speaker reported on following lines. A `# speaker: name` tag only names one line.
// It also tells StoryManager (hdgife_speak) when it happened, so a speak() after a line does not change the speaker
// of that line. Without a StoryManager the call does nothing.
=== function speak(hdg_name) ===
~ hdgife_speaker = hdg_name
~ hdgife_speak(hdg_name)

// Number display. Use these inline: {stat_text("strength")} or {fmt(stat("strength"), 1, "round")}.
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

=== function has_stat(hdg_name) ===
~ return false

=== function has_stat_of(hdg_entity, hdg_name) ===
~ return false

// Used by StoryManager.seedRandom. Seeds RANDOM and shuffles.
=== function hdgife_seed(hdg_n) ===
~ SEED_RANDOM(hdg_n)
~ return 0

// The library version, for the engine to read from the compiled story. Leave it alone.
=== function hdgife_lib_version() ===
~ return hdgife_version
