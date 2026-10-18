# HDGEIFE

HDG Interactive Fiction Engine. It's an interactive fiction library for [libKTX](https://github.com/libktx/ktx)/[libGDX](https://libgdx.com) games in Kotlin, built on [Blade Ink](https://github.com/bladecoder/blade-ink-java). It doesn't care what your UI looks like or whether you use an ECS.

The idea is that ink handles all the narrative. That means the branching story, and also the procedurally generated ad-lib flavor text, which you get out of ink variables, functions and alternatives. The Kotlin side is just a bridge to your game plus a runtime compiler.

**The engine reports, the game decides.** It receives raw data (ink source, stats) and returns story elements (lines, spans, markers, choices, events). It never renders, hides a choice, blocks one, or acts on an event. Everything that is a decision (number format, colors, which sources to load, what a style name means) is a default you can replace, listed under "Defaults".

It's early. The API will change.

## What I'm going for

Drop it into any libKTX project from JitPack, write the whole narrative and all the flavor text in ink, and hook it up to your own game state through one small interface. The game chooses which ink files it feeds the engine and in what order, so extending or replacing content is the game's call.

## What works right now

`InkCompilerService` compiles the ink sources you hand it, in the order you hand them. Compiling is plain blocking, so you pick the thread. You can also load a precompiled story from JSON. Broken ink gives an `InkCompileException` with ink's own messages, which name the source and line.

`NarrativeStateProvider` is the only interface your game has to implement, and it is a `fun interface`:

```kotlin
fun getStat(entityId: String, statName: String): Any? // null = no such stat
```

A stat can be any Kotlin value (`Int`, `Float`, `Boolean`, `String`, an enum...). See "NarrativeStateProvider" for the mapping.

With `INCLUDE hdgife.ink` in your story, ink can read stats live: `stat("sanity")` for the current speaker, `stat_of("guard", "mood")` for anyone, `has_stat("luck")`. No `VAR` per stat needed. Ink can also call your Kotlin functions (`bindFunction`), tell your game things (`~ emit("give_item", "sword")`), and your game can read, write and observe ink variables.

`StoryManager.syncStatsToInk(entityId, statNames)` pulls those stats and pushes them into ink variables named `entity_stat`, so `player_hp` for example. The naming rule can be swapped out. Variables that aren't declared in the ink are skipped and returned to you.


## API

Everything lives under `com.github.halfdeadgames.hdgife`.

### Hdgife

```kotlin
Hdgife.VERSION // const val String, currently "0.2.0"
```

### NarrativeStateProvider

Package `state`. Implement this in your game.

```kotlin
fun interface NarrativeStateProvider {
    fun getStat(entityId: String, statName: String): Any?
}
```

Null means the entity has no such stat: `stat`/`stat_of` give 0, `has_stat` is false, requirements on it are unmet. A simple numeric provider is a lambda: `StoryManager(story, NarrativeStateProvider { _, _ -> 10 })`, and an override may narrow the type (`override fun getStat(e: String, s: String): Float = 10f`).

**Why `Any?` and not a sealed `StatValue`.** Games already hold plain Kotlin values. A wrapper type means wrapping every return, gives no extra safety (the ink side is dynamically typed anyway) and makes the one-line provider a ten-line one. With `Any?` the mapping lives in one place in the engine and is documented, and what does not fit a requirement's type is reported as a mismatch instead of crashing. The price is no compile-time check of the returned type.

| Kotlin value | Ink value |
| --- | --- |
| `Int`, `Short`, `Byte`, `Long` that fits an Int | int |
| `Float`, `Double`, `Long` outside the Int range | float |
| `Boolean` | bool |
| `String`, `CharSequence`, `Char` | string |
| `Enum` | string, its `name` |
| `null` | missing |
| anything else | `toString()`, or what `valueConverter` returns |

`StoryManager.toInk(value)` does this mapping, and you can call it yourself. `valueConverter` is the hook for your own types: `manager.valueConverter = { if (it is Faction) it.id else null }` (the result goes through the table above once more, null falls back to `toString()`).


### InkCompilerService

Package `compiler`. No coroutines inside, so run it wherever you like.

```kotlin
class InkSource(name: String, loader: () -> String, sibling: ((String) -> InkSource?)? = null)
InkSource(name: String, text: String)
InkSource.of(file: FileHandle): InkSource

class InkCompilerService {
    var log: HdgifeLog?
    fun compile(sources: List<InkSource>): Story
    fun compile(main: InkSource, extras: List<InkSource> = emptyList()): Story
    fun compile(main: FileHandle, extras: List<FileHandle> = emptyList()): Story
    fun compileFolder(folder: FileHandle, recursive: Boolean = true): Story
    fun loadCompiled(json: String): Story
}
```

The first source is the root. Each extra is appended to it as an `INCLUDE`, in list order, so list order is load order. Ink can't override a knot defined earlier. A duplicate is an error. `name` is what shows up in errors and what other sources `INCLUDE`. The text is read lazily, so it can come from a file, the network or an archive.

`INCLUDE x.ink` inside a source is resolved relative to that source's name (`..` works). A listed source with that name wins, then `sibling` is asked. `InkSource.of(file)` sets `sibling` to look next to the file, with the same file type. A source loads at most once per compile, so listing a file that another source includes is fine. `INCLUDE hdgife.ink` gives the bundled helper library from the classpath. It is never added for you.

`compileFolder` takes the `.ink` files of a folder, compares their paths as plain text, uses `main.ink` as root if present (else the first file) and includes the rest in that order. Order it with file names, like `000_base.ink` and `010_extra.ink`, or pass your own list to `compile`.

The returned story is an `HdgifeStory` (see "Glue and lookahead"): a normal Blade Ink `Story` that fixes a bug with lookahead-safe functions. Precompiled: `story.toJson()` is the compiled story. Store it and later call `loadCompiled(json)`, which skips the compiler. That's the way to go on Android and web. The JSON is of the story, not its state.

`log` gets ink warnings. Errors throw `InkCompileException(errors, source)`. Unreadable sources and missing includes are named in `errors`.

### StoryManager

Package `story`.

```kotlin
class StoryManager(
    story: Story,
    var stateProvider: NarrativeStateProvider? = null,
    var variableNamer: (entityId: String, statName: String) -> String = ::defaultVariableName,
)
var story: Story
var speaker: String?
val lookaheadSafe: Boolean            // true when the story is an HdgifeStory (the compiler service makes those)
var seed: Int?
// settings, all with documented defaults, see "Defaults"
var numberFormat: NumberFormat
var formatOverride: ((entityId: String, statName: String) -> NumberFormat?)?
var textCleaner: (String) -> String
var speakerTagKey: String
var requirementsTagKey: String
var allowExternalFunctionFallbacks: Boolean
var valueConverter: ((Any) -> Any?)?
var onEvent: ((StoryEvent) -> Unit)?
var log: HdgifeLog?
```

`stateProvider` is optional. Without one, `stat`/`stat_of` return 0, `has_stat` returns false and `syncStatsToInk` does nothing. Assigning `story` binds the stat functions, your bound functions and your observers to the new story (after a reload, say). Not thread-safe. Keep it on one thread.

```kotlin
fun beginScene(knotPath: String, speakerId: String? = null)
fun next(): DialogStep
fun choose(index: Int): DialogStep
fun reset()
fun seedRandom(seed: Int): Boolean
fun saveState(): String
fun loadState(json: String)
fun syncStatsToInk(entityId: String, statNames: List<String>): List<String>
fun toInk(value: Any?): Any?

fun bindFunction(name: String, lookaheadSafe: Boolean = false, function: (FunctionArgs) -> Any?)
fun unbindFunction(name: String)

val variableNames: List<String>
fun variable(name: String): Any?
inline fun <reified T : Any> variable(name: String): T?
fun setVariable(name: String, value: Any)
fun observe(name: String, observer: (old: Any?, new: Any?) -> Unit): VariableObservation
fun unobserve(observation: VariableObservation)
fun unobserve(name: String)
```

`beginScene` sets `speaker` and diverts to the knot (`knot` or `knot.stitch`). `next` continues until a choice or the end. `choose` picks a choice and then does the same. `seedRandom` makes ink's RANDOM and shuffles repeatable. It needs `INCLUDE hdgife.ink` (returns false without it) and a reset reseeds randomly, so call it again after that.

`saveState()` and `loadState(json)` wrap ink's own state JSON. With `INCLUDE hdgife.ink` the speaker lives in the ink variable `hdgife_speaker` and the seed is in the ink state (ink's `SEED_RANDOM` plus the position in the random sequence), so one string is all the game stores. After `loadState`, ink continues with the same random results. Load it only into the same compiled story. Without the library, `speaker` is a plain field that you keep yourself. Your own stat values aren't included, and neither are open styles (they are closed on load). blade-ink's seed setter is package-private, so `seed = n` (same as `seedRandom`) goes through ink and needs the library. The `seed` getter only returns the last value set through this manager.

#### Choosing sources, resetting

The engine knows no scenes and no load order. The intended pattern: the game decides which ink sources to use and in what order (a base story, then extra files, whatever its rules are), compiles them with `InkCompilerService`, and builds a **new `StoryManager`** from the result, optionally with `loadState(saved)`. Replacing a base scene is then just the game choosing which file to feed, since ink itself can't override a knot defined earlier. `manager.story = newStory` does the same on an existing manager and keeps its settings, bindings and observers. `reset()` starts the *same* compiled story over: ink state (variables, position, visits, seed, speaker), open styles and pending events are cleared. It does not recompile, and your observers are told about variables that changed back.

```kotlin
data class DialogStep(val lines: List<DialogLine>, val tags: Tags, val choices: List<DialogChoice>, val ended: Boolean,
                      val events: List<StoryEvent>, val openStyles: List<Style>) {
    val text: String // plain text of all lines joined with "\n"
}
data class DialogLine(val text: String, val speaker: String?, val tags: Tags, val spans: List<TextSpan>,
                      val markers: List<TextMarker>)
data class DialogChoice(val index: Int, val text: String, val spans: List<TextSpan>, val tags: Tags,
                        val requirements: List<ChoiceRequirement>, val markers: List<TextMarker>) {
    val available: Boolean // all requirements met, or none
}
data class ChoiceRequirement(val entity: String?, val stat: String, val op: Comparison?, val target: Any?,
                             val actual: Any?, val met: Boolean, val raw: String, val mismatch: Boolean) { val valid: Boolean }
enum class Comparison(val symbol: String) { GREATER, GREATER_EQUAL, LESS, LESS_EQUAL, EQUAL, NOT_EQUAL }
data class StoryEvent(val name: String, val args: List<Any?>, val lineIndex: Int)
data class Tags(val raw: List<String>, val map: Map<String, String>) // get(key), has(key), all(key), isEmpty
data class TextSpan(val text: String, val styles: List<Style>)
data class Style(val name: String, val value: String? = null, val params: Map<String, String> = emptyMap()) {
    val values: List<String>; fun param(key: String): String?; fun hexColor(key: String? = null): Int?
}
data class TextMarker(val name: String, val value: String? = null, val params: Map<String, String> = emptyMap(), val offset: Int)
fun parseHexColor(text: String): Int?
class FunctionArgs { val size: Int; operator fun get(i: Int): Any?; fun string(i: Int): String; fun int(i: Int): Int
                     fun float(i: Int): Float; fun bool(i: Int): Boolean }
class VariableObservation : AutoCloseable { val name: String; val active: Boolean }
```

Each ink line is one `DialogLine`. Empty lines are dropped. `ended` means no choices and nothing left to continue. The engine reports and the game decides: `choose` never refuses a choice, and unavailable ones are not removed. Ink's own conditional choices (`* {cond} [text]`) are still hidden by ink itself.

**Tags.** `key: value` becomes key to value (split at the first colon, trimmed), a bare `#shout` becomes `shout` with an empty value. If the part before the colon is not a plain word, like `Note to self: hi`, the whole tag is a bare key. A repeated key keeps the last value in `map`. `raw` has every tag in order and `all(key)` returns every value of a repeated key. A line gets the tags written on it plus tags on empty lines just above it (the way ink attaches them). `DialogStep.tags` is the union for the step. A choice only gets tags written inside its brackets, see below.

**Speakers.** `StoryManager.speaker` is the persistent speaker. Ink changes it with `~ speak("guard")` (`speak("")` clears it), or the game sets it (`beginScene`, `speaker = ...`). It lives in the ink variable `hdgife_speaker`, so it is part of `saveState`. A line can name a speaker for itself with a tag, `# speaker: guard` (key from `speakerTagKey`). That applies to that one line only and does not change the persistent speaker. `DialogLine.speaker` is the tag speaker if there is one, else the persistent speaker at that line, else null. `stat(name)` and the default requirement entity always use the persistent speaker. `speak` is declared EXTERNAL but the engine does not bind it. The ink body in hdgife.ink does the work, which keeps it in saved state and safe in ink's lookahead.

**Requirements.** Put `requires` in a tag inside the choice brackets:

```ink
* [Lift the boulder # requires: strength >= 12]
* [Haggle # requires: strength >= 5, merchant.mood > 30]
* [Join the cause # requires: faction == rebels, is_injured == false]
```

Each clause is `[entity.]stat op value`, ops `> >= < <= == !=`, separated by commas. The tag may be repeated. Without an entity the current speaker is used. The engine reads the stat from your `NarrativeStateProvider` and fills in `actual` (as an ink value) and `met`. Comparison depends on the type of the stat:

- numbers (`Int`, `Float`...) compare with every op, against a number written bare (`strength >= 12`, `ratio < 0.5`). `10` equals `10.0`.
- strings and enums (by name) and booleans compare only with `==` and `!=`. The value is written bare (`faction == rebels`, `is_injured == false`) or in quotes (`faction == "red guard"`). String comparison is case-sensitive. Because `,` separates clauses and `#` starts a tag, a string value can't contain either.
- a value or operator that doesn't fit the stat's type (`strength >= high`, `faction > 3`, `is_injured == 4`, `is_injured < true`) is a **mismatch**: unmet, `mismatch = true`, and logged.
- a missing stat (no provider, no entity, or null) gives `actual = null` and is unmet, whatever the operator.
- a clause it can't read is logged and returned unmet, with `valid = false` and the text in `raw`.

Tags placed after the brackets (`* [Lift] # requires: ...`) are not choice tags in Blade Ink 1.3.2: ink treats them as part of what happens after the choice is taken, so the engine can't see them in time. Keep the tag inside the brackets, or before them.

### Styles, effects and markers

Writers mark text in ink, and the engine returns it as `TextSpan`s (a style over a stretch of text) and `TextMarker`s (a point in the text), with all markers stripped from `text`. The engine gives names no meaning: `em`, `color`, `shake`, `bounce`, `pause`, `slam` are all just names that the game maps. Why angle brackets: `[` `]` are choice syntax, `{` `}` are logic and `#` starts a tag in ink, so none of those can be used.

```ink
"<color=ff0000>Quiet!</color> He means <em>every</em> word, and <shake amp=2>it shows</shake>.<pause=0.5/> Then<slam/> silence."
```

Syntax:

| Marker | Meaning |
| --- | --- |
| `<name>` | opens a style |
| `<name=value>` | opens with a value: `<color=red>`, `<shake=2,0.5>` |
| `<name key=value key2="two words">` | opens with named parameters. A value may follow the name too: `<shake=2 speed=0.5>` |
| `</name>` | closes that style (and any inner ones still open) |
| `</>` | closes the most recent |
| `<name/>`, `<name=0.5/>`, `<name key=v/>` | a point marker with no text |
| `<<` | a literal `<` |

Anything that is not a well-formed marker (`a < b`, `<3`, `<b and c>`) is plain text. Names are letters, digits, `_` and `-`, starting with a letter. A value ends at a space or `>` (or the `/` of `/>`), unless it is in quotes (`"` or `'`, removed), so `<color=light blue>` needs `<color="light blue">`. Values are raw strings; `Style.values` splits `value` at commas (`<wave=2,0.5>` gives `["2", "0.5"]`), `Style.param("amp")` reads a named one. I support both forms because they read differently in a script: positional for short effects (`<wave=2,0.5>`), named when meaning matters (`<shake amp=2 speed=0.5>`). Both are plain strings, so the game parses numbers itself.

**Point markers.** `<pause=0.5/>` and `<slam/>` come back on `DialogLine.markers` as `TextMarker(name, value, params, offset)`. `offset` is a character index into `DialogLine.text`: the marker sits just before the character at that index, and `offset == text.length` means after the last one. Several markers can share an offset. The engine does not interpret them: a typewriter effect would wait at `pause` and call a screen shake at `slam`. Choices have `markers` too.

**Colors.** `#` starts an ink tag, so `<color=#ff0000>` can't work. Write hex without it: `<color=ff0000>`, `<color=ff000080>` (with alpha), `<color=0xff0000>` or `<color=f00>`, or a name: `<color=red>`. The engine does not render. `Style.hexColor()` (also `TextMarker.hexColor()`, and `parseHexColor(text)`) returns **RGBA8888** (`0xRRGGBBAA`, red in the top byte) when the value is valid hex, and **null** for anything else, so the game resolves names its own way:

```kotlin
val rgba = style.hexColor() ?: namedColors[style.value]   // hex first, then your own names
```

For a style with a named parameter use `style.hexColor("color")`. 3, 6 and 8 digits are read as hex (with an optional `0x` or `#` in front). A three-letter name made only of a-f (`bad`, `ace`) would read as hex, so use another name or write real hex with `0x`. `\#` is ink's escape for a literal `#`, and I tested it through the real compiler: `<color=\#ff0000>` arrives as `<color=#ff0000>` with no tag created, and the `#` form is accepted too. Plain `ff0000` is simpler.

**Spans carry across lines.** An open style stays open until its closing marker: across the lines of a step and across `next()` calls. The spans of each line start with the styles still open from earlier lines. A style left open at a choice carries into the chosen branch (`choose()` then `next()`), and `DialogStep.openStyles` tells what is open at the end of a step. Everything is closed when the story ends (`ended`), on `reset()`, on `loadState()` and when `story` is replaced; an unclosed style is logged when that happens. A closing marker with nothing to close is dropped and logged, and one that closes an outer style also closes the inner ones (logged). Choice text is read on its own: nothing carries in, and what is left open there is dropped and logged.

**Cleaning and spans.** The `textCleaner` runs on the plain text *after* the markers are removed, then spans and markers are moved onto the cleaned text, so `line.text` always equals the spans' texts joined, and marker offsets always fit `line.text`. This is exact for any cleaner that only removes characters (the default does). A cleaner that rewrites characters still gives consistent text, but span and marker boundaries are then placed proportionally. Cleaners get one line without its line break.

### Events, game functions, variables

**Events.** `hdgife.ink` has an event channel that needs no `EXTERNAL` of your own:

```ink
~ emit("give_item", "sword")
~ emit2("reputation", "guard", -5)
~ emit0("door_opened")
```

`emit0(name)`, `emit(name, a)`, `emit2(name, a, b)`, `emit3(name, a, b, c)`: ink has no overloads, so the number is in the name. Arguments are plain `Int`, `Float`, `Boolean` or `String`. Events come back in order on `DialogStep.events` (`StoryEvent(name, args, lineIndex)`) and, if set, through `manager.onEvent`, which is called after `next()` has built the step. `lineIndex` is the index in `DialogStep.lines` of the line produced right after the call: the event happened just before that line is shown (`lines.size` if no line follows). The engine never acts on an event. Tags (`# kind: value`) are the other way to attach data to a line and need nothing special.

Each call is reported **exactly once**. The functions are bound as not lookahead safe, so ink never runs them during its glue lookahead (it stops before the call and runs it for real afterwards). The price: an `emit` that sits between a line and a glue line (`<>`) that joins to it ends the lookahead, so the glue does not join. Put the `emit` before the first line, or after the glued text. Use `emit` as a statement, not inside `"{...}"` or choice text.

**Game-defined functions.** Bind a lambda to an ink `EXTERNAL` function:

```ink
EXTERNAL give_item(item, count)
=== function give_item(item, count) ===   // fallback, runs when nothing is bound
~ return 0
```

```kotlin
manager.bindFunction("give_item") { args -> inventory.add(args.string(0), args.int(1)) } // returns Unit/null: no value
manager.bindFunction("item_count", lookaheadSafe = true) { args -> inventory.count(args.string(0)) } // returns Int
```

The `EXTERNAL` line is required for the call to compile. The ink fallback function is required unless every run has the binding, because ink checks bindings on the first `Continue` and fails with "Missing function binding" when there is neither a binding nor a fallback (or fallbacks are off). `FunctionArgs` gives typed access (`string`, `int`, `float`, `bool`, `args[i]`) and throws a readable `IllegalArgumentException` for missing or wrong-typed arguments. The result is mapped like a stat (see the table above); null/Unit gives ink no value. Exceptions in your lambda propagate out of `next()`. `lookaheadSafe = true` is only for pure reads (see "Glue and lookahead"); leave it false for anything with a side effect. Bindings survive assigning a new `story`. `unbindFunction(name)` removes one.

**Variables.**

```kotlin
manager.variableNames                 // sorted, includes the library's hdgife_* ones
manager.variable("player_hp")         // Any?: Int, Float, Boolean, String; null if undeclared
manager.variable<Int>("player_hp")    // typed; Int is accepted as Float/Double/Long; other mismatches throw
manager.setVariable("player_name", "Zed")   // mapped like stats; throws if undeclared
val watch = manager.observe("gold") { old, new -> println("gold $old -> $new") }
manager.unobserve(watch)              // or manager.unobserve("gold"), or watch.close()
```

Observers wrap Blade Ink's variable observers and add the old value (the value at the last report, starting with the value when you called `observe`). Ink reports changes in a batch when `next()` finishes, so a variable changed twice in one step is reported once. `setVariable` reports immediately. A `reset()` is also a change for variables that go back to their start value. `observe` and `setVariable` throw `IllegalArgumentException` for undeclared names.


### Stat access and clean output

`main.ink`:

```ink
INCLUDE hdgife.ink

=== guard_chat ===
~ temp mood_gap = avg2(invert(stat("mood")), stat("tiredness"))
"Halt." {mood_gap > 60: He looks wrecked.|He looks fine.} {chance(100 - stat("sanity")): His eye twitches.}
-> END
```

Kotlin:

```kotlin
val manager = StoryManager(story, MyState())
manager.beginScene("guard_chat", speakerId = "guard")
val step = manager.next()
println(step.text)
```

Everything about which stats exist is up to your game and your ink. Nothing is built into the engine, and `hdgife.ink` contains functions only, no scenes. It is only included if you write the `INCLUDE`, so stories without it are untouched. The name is reserved and resolved from the classpath, so it works on desktop and Android. The EXTERNAL functions have ink fallbacks (0 / false / plain ink rounding), so a story using the library still runs without a `StoryManager`.

`stat` and `stat_of` give ink the mapped type of the stat: an `Int` stat is an ink int, a `Float` a float, a `String` or enum a string, a `Boolean` a bool. A missing stat gives `0.0`. Ink integer division truncates, so write `/ 2.0` or use the helpers when averaging whole numbers. The engine does not decide how numbers are shown. That is up to the game (see below) and the story. Printing a float directly (`{stat("ratio")}`) uses ink's own float printing, so prefer the functions below for display.

Library functions: `speak(name)`, `stat(name)`, `stat_of(entity, name)`, `has_stat(name)`, `stat_text(name)`, `stat_text_of(entity, name)`, `fmt(x, decimals, mode)`, `floor_text(x)`, `round_text(x, decimals)`, `chance(pct)`, `clamp(x, lo, hi)`, `avg2(a, b)`, `avg3(a, b, c)`, `blend(a, wa, b, wb)`, `invert(x)` (100 - x), `scale100(x, lo, hi)`, `emit0/emit/emit2/emit3`. Don't declare your own knots with these names. Parameters and temps inside the library start with `hdg_`, so your story is free to declare `VAR n`, `x`, `name` and so on. Keep your own names off `hdg_` and `hdgife_`.

`stat_text(name)` prints any stat: numbers with the number format (below), strings, enums and booleans as text (`rebels`, `false`).

### Glue and lookahead

Ink runs ahead past a line break to see whether glue (`<>`) on the next line joins it. A function that is not *lookahead safe* ends that run ahead, and then glue after it may not join. So the stat functions should be lookahead safe, but Blade Ink 1.3.2 has a bug that makes that fail:

- **Root cause.** In `Story.callExternalFunction` the guard that should refuse an *unsafe* function while ink builds a string (choice text, or `"{...}"`) is written inverted. It reads `funcDef.lookaheadSafe && state.inStringEvaluation()`; upstream ink has `!funcDef.lookaheadSafe`. So a function bound safe errors out ("could not be called because it wasn't marked as lookaheadSafe...") exactly when it is used in choice text or a string, and an unsafe one is let through where it can't work. Binding the stat functions as unsafe was the only way to get `{stat_text("mood")}` in a choice, at the price that every call ended glue lookahead.
- **Fix.** `callExternalFunction` is package-private, so `com.bladecoder.ink.runtime.HdgifeStory` (in this jar, in Blade Ink's own package, like `InkNativePatch`) subclasses `Story` and overrides it. Functions bound through `HdgifeStory.bindLookaheadSafe` run directly, with no guard, in lookahead and in strings; everything else goes to the original code. No reflection. `InkCompilerService` returns `HdgifeStory`s (`HdgifeStory.from(story)` converts any story through its compiled JSON, with a fresh state), and `StoryManager` binds `stat`, `stat_of`, `has_stat`, `stat_text`, `stat_text_of` and `fmt` lookahead safe on them. Both `He keeps his eyes on you,` / `{stat("mood") > 50: <>} patience at {stat_text("mood")}.` (a stat call between the line break and the glue) and `* [Pay {stat_text("gold")} gold]` work.
- **A plain `Story`** (built some other way) still works, but the stat functions are bound unsafe: they work everywhere and end glue lookahead. `manager.lookaheadSafe` says which one you have. Your own functions follow the same rule: `bindFunction(lookaheadSafe = true)` is safe only on an `HdgifeStory`.

The jar therefore has a few classes in the `com.bladecoder.ink.runtime` package, which is fine on the classpath but not in a Java module.


### Number formatting

Package `story`. Text output is locale-independent: always `.` as the decimal separator, no grouping.

```kotlin
enum class Rounding { FLOOR, CEIL, ROUND, TRUNCATE }
data class NumberFormat(val decimals: Int = 0, val rounding: Rounding = Rounding.ROUND, val trimTrailingZeros: Boolean = false)
fun NumberFormat.format(value: Number): String // also Float, Double and Long overloads
```

`decimals` is 0 to 9. `FLOOR` goes toward negative infinity, `CEIL` toward positive infinity, `ROUND` to the nearest with ties away from zero (2.5 gives 3, -2.5 gives -3), `TRUNCATE` toward zero. Rounding works on the shortest decimal form of the float, so 7.65 at one decimal is 7.7. `trimTrailingZeros` turns `7.50` into `7.5` and `7.00` into `7`.

The game sets the default with `manager.numberFormat`, and can vary it per stat with `manager.formatOverride`:

```kotlin
manager.numberFormat = NumberFormat(decimals = 0, rounding = Rounding.FLOOR)
manager.formatOverride = { _, stat -> if (stat == "luck") NumberFormat(1, Rounding.ROUND) else null }
```

Ink side:

- `stat_text(name)` and `stat_text_of(entity, name)` print the stat using the format for that stat.
- `fmt(x, decimals, mode)` formats any number. `mode` is `"floor"`, `"ceil"`, `"round"` or `"trunc"`. For example `{fmt(stat("strength"), 0, "floor")}` or `{fmt(avg3(a, b, c), 1, "round")}`. An unknown mode logs an error and uses round.
- `floor_text(x)` and `round_text(x, decimals)` are shorthands for `fmt`.

Without a `StoryManager`, ink fallbacks do the same rounding with `FLOOR`, `CEILING` and `INT` (`stat_text` is 0 decimals, round). Whole results are exact, but with decimals the last digit can be off because ink floats are binary, and decimals above 6 are not supported. The fallbacks print their text directly, so use them inline (`{fmt(...)}`) and not in `~ temp t = fmt(...)`.

```kotlin
fun syncStatsToInk(entityId: String, statNames: List<String>)
```

For each stat name it builds the ink variable name with `variableNamer`, calls `stateProvider.getStat(entityId, stat)` and sets the variable to the value mapped to an ink type (an Int stat stays an int, a String a string, a Boolean a bool). If the story has no variable with that name (not declared with `VAR`), or the provider returns null, it skips it, and for an undeclared variable it doesn't call `getStat` at all. The skipped variable names are returned.

```kotlin
StoryManager.defaultVariableName(entityId: String, statName: String): String
```

Returns `entityId_statName` with anything outside `[A-Za-z0-9_]` replaced by `_`. So `("player", "hp")` gives `player_hp`.

### Writing stat-driven ink

```ink
INCLUDE hdgife.ink

=== gate ===
~ speak("guard")
"Halt." <em>He does not lower the spear.</em> {stat("mood") < 30: He scowls.|He watches you.}
~ speak("merchant")
"He's with me." Her smile is <color=gold>very</color> practiced. # speaker: merchant
~ speak("guard")
* [Lift the boulder # requires: strength >= 12]
  -> END
* [Stand your ground # requires: strength >= 5, sanity > 30]
  -> END
* [Call in a favor # requires: merchant.mood >= 50]
  -> END
* {stat("strength") >= 10} [Arm-wrestle <em>{stat_text("strength")}</em> vs. you]
  -> END
* [Join them # requires: faction == rebels, player.is_injured == false]
  ~ emit("joined", "rebels")
  -> END
```

Kotlin, with the game choosing to grey out unavailable choices:

```kotlin
val step = manager.next()
for (line in step.lines) draw(line.speaker, line.spans) // map style names to your own rendering
for (choice in step.choices) {
    val unmet = choice.requirements.filter { !it.met } // entity, stat, op, target, actual (null if missing)
    ui.addChoice(choice.spans, enabled = choice.available, hint = unmet)
}
```

Plain `{cond}` choices are hidden by ink. `requires` ones are reported, and `manager.choose(i)` works on any of them.

### Ink float functions

Blade Ink 1.3.2 throws `ClassCastException` on `FLOOR`, `CEILING` and `INT` with a float argument, even `FLOOR(2.5)`. The cause is in its `NativeFunctionCall`: the float versions cast the boxed argument with `(double) val` and `(int) val`, but ink floats are boxed `Float`, so the cast fails. Fixed in `com.bladecoder.ink.runtime.InkNativePatch`, a small object in Blade Ink's own package. Ink looks these operations up in one shared table, and the table has a package-private registration method. The patch registers correct float versions there. No reflection, no copy of the Blade Ink class, and it applies to already compiled stories too. `InkCompilerService` and `StoryManager` apply it for you. If you build a `Story` yourself, call `InkNativePatch.apply()` first. The jar therefore has a few classes in the `com.bladecoder.ink.runtime` package, which is fine on the classpath but not in a Java module. `FLOOR(-2.5)` is -3.0, `CEILING(-2.5)` is -2.0, `INT(-2.7)` is -2.

## Defaults

Every default that is a game decision can be replaced. Nothing here is hard-wired.

| Setting | Default | What it does |
| --- | --- | --- |
| `StoryManager.numberFormat` | `NumberFormat(decimals = 0, rounding = ROUND)` | How `stat_text` prints numbers. Round half away from zero, no decimals. |
| `StoryManager.formatOverride` | `null` | Per-stat `NumberFormat`, falls back to `numberFormat` when null. |
| `StoryManager.variableNamer` | `defaultVariableName`: `entity_stat`, characters outside `[A-Za-z0-9_]` become `_` | Which ink variable `syncStatsToInk` writes to. A settable property. |
| `StoryManager.textCleaner` | `cleanLine`: collapse spaces, no space before `, . ; : ! ?`, trim. **English punctuation.** | Cleans each line and choice (plain text, after markers are removed). Replace for other languages, or `{ it }` to turn off. |
| `StoryManager.speakerTagKey` | `"speaker"` | The tag key that names a line's speaker. |
| `StoryManager.requirementsTagKey` | `"requires"` | The tag key for choice requirements. |
| `StoryManager.allowExternalFunctionFallbacks` | `true` | Whether an `EXTERNAL` without a binding may fall back to the ink function of the same name. hdgife.ink needs it. With `false`, every EXTERNAL needs a binding. Applied to the current and any later story. |
| `StoryManager.log` / `InkCompilerService.log` | `null` | Where warnings go (unknown `fmt` mode, unbalanced styles, requirement mismatches, ink warnings). Nothing is logged unless you set one. |
| `StoryManager.valueConverter` | `null` | The hook for your own types in stats, `setVariable` and function results. Unmapped types become `toString()`. |
| `StoryManager.onEvent` | `null` | Optional listener for `emit` events (they are on `DialogStep.events` either way). |
| Stat mapping | table in "NarrativeStateProvider" | Int/Long to int, Float/Double to float, Boolean to bool, String/CharSequence/Char to string, Enum to name, null missing. |
| Missing stat in ink | `stat` gives `0.0`, `has_stat` false | Fixed by the library's contract; test with `has_stat`. |
| Style names | none | The engine gives no style, effect or marker name a meaning. |
| Choices | all returned | Nothing is hidden or blocked. `available` only reports. |
| `Rounding` ties | away from zero (`ROUND`) | Chosen per format; `Rounding.FLOOR/CEIL/TRUNCATE` are the others. |


### Example

`main.ink`:

```ink
VAR player_hp = 0

You have {player_hp} hp.
-> END
```

Kotlin:

```kotlin
class MyState : NarrativeStateProvider {
    override fun getStat(entityId: String, statName: String): Any? = 10
}

// the game chooses the sources and their order
val sources = listOf(Gdx.files.internal("ink/main.ink")) + extraFiles
val story = try {
    InkCompilerService().compile(sources.first(), sources.drop(1))
} catch (e: InkCompileException) {
    e.errors.forEach { println(it) }
    return
}
val manager = StoryManager(story, MyState()).apply {
    onEvent = { event -> println("ink says ${event.name}") }
    bindFunction("item_count", lookaheadSafe = true) { args -> inventory.count(args.string(0)) }
}
manager.syncStatsToInk("player", listOf("hp"))
val text = manager.next().text
val saved = manager.saveState() // later: manager.loadState(saved)
```

To run a different set of sources, compile them again and build a new `StoryManager`.

## Not done yet

- Ink can't override an existing knot. Ink rejects duplicate knot names, so replacing content means the game choosing which source to feed.
- A tag speaker covers one line only. Open styles are not part of `saveState`.
- The `emit` functions are numbered by argument count because ink has no overloads.
- There's no reusable UI in the library. That's on purpose, though I might add some optional helpers later.
- Web/TeaVM is untested.
- No real unit tests yet.

## Setup

Add JitPack to your repositories:

```groovy
repositories {
  maven { url = 'https://jitpack.io' }
}
```

Then add the dependency to your core module. Use a release tag or a commit hash for the version:

```groovy
dependencies {
  api "com.github.halfdeadgames:hdgife:0.2.0"
}
```

The library depends only on libGDX (`gdx`, for `FileHandle`), Blade Ink (runtime and compiler) and the Kotlin stdlib. No KTX, no coroutines, no logging library. If you add these yourself, keep the versions in line: libGDX 1.14.2, Blade Ink 1.3.2.

The jar contains only the engine and the generic `hdgife/hdgife.ink` helper library, which has functions and no scenes. Demo stories live in the test harness.

## Testing locally

To try changes before pushing, publish to your local Maven repo:

```
./gradlew :core:publishToMavenLocal
```

In the consuming project, add `mavenLocal()` to your repositories and use version `0.2.0`.

## Test harness

The `lwjgl3` module is a small desktop app I use for poking at things. It isn't published. It owns the KTX, scene2d and console dependencies, its own `lwjgl3/assets/` folder (UI skin and the demo ink `ink/main.ink` and `ink/guard.ink`) and its own load policy: `main.ink` plus any `.ink` files in `lwjgl3/assets/extra/`, sorted by path. Run it with:

```
./gradlew lwjgl3:run
```

It opens a dialog tester for `lwjgl3/assets/ink/main.ink`. The hub has a gate scene with two speakers (guard and merchant), colored spans (`em`, `b` and `color` are mapped to libGDX color markup in the harness only, names and hex alike) and choices with requirements, including string and boolean ones (`faction == crown`, `player.is_injured == false`). Effects (`shake`, `wave`) and point markers (`pause`, `slam`) are not drawn: the harness prints them in a dim `fx:` line under the dialog line, with their offsets. A `<em>` spans two lines in the gate scene. A glue line in the guard chat has a stat call between the line break and the glue. `emit` events show as gold `!` lines and in the console, and the harness acts on `give_item` (it keeps an inventory that ink reads back through a bound `inventory_count`). Unavailable choices follow the harness's own policy, set with `choicemode`. The backtick key toggles the console. Commands:

| Command | What it does |
| --- | --- |
| `vars` | Lists every ink variable with its value. |
| `var <name>` | Reads one ink variable through the engine's variable API and shows its type, or says it isn't declared. |
| `observe <name>` | Logs every change of an ink variable (`name: old -> new`) to the console. |
| `unobserve <name>` | Stops that. |
| `set <name> <value>` | Sets an ink variable, keeping its current type. `set player_hp 5` stays an int, `set player_name 5` gives the string "5". |
| `set <name> <value> <type>` | Same, with an explicit type: `int`, `float`, `bool` or `string`. If the value doesn't parse, nothing changes. |
| `stat <entity> <stat> <value>` | Sets a stat on the test state provider and syncs it to ink. The value is read as an int, a float, `true`/`false` or text, so `stat player is_injured true` and `stat player faction empire` work. |
| `hp <value>` | Shortcut for `stat player hp <value>`. |
| `npc <entity> <stat> <value>` | Sets a stat on the test provider. Ink reads it live through `stat()`, no sync. The `guard` NPC starts with sanity 80, tiredness 20, mood 60, strength 12 and faction `crown`, and `merchant` with mood 70, strength 4 and faction `guild`. The player has faction `rebels`, rank 2 and `is_injured` false. |
| `talk <knot> [speaker]` | `beginScene`: sets the speaker and jumps to the knot, for example `talk guard_chat guard`. |
| `seed <n>` | Seeds ink's random. Use `restart` first if you want the same result twice. |
| `numfmt <decimals> <mode>` | Changes the default number format used by `stat_text`. Mode is `floor`, `ceil`, `round` or `trunc`, for example `numfmt 1 floor`. |
| `choose <n>` | Picks a choice by index, starting at 0. |
| `divert <path>` | Jumps to an ink path (`knot` or `knot.stitch`) and continues from there. Bad paths log an error. |
| `reload` | Recompiles `main.ink` plus any `.ink` files in `lwjgl3/assets/extra/`, swaps in the new story and clears the log. Story state starts fresh. |
| `restart` | `manager.reset()`: resets the story state and the harness inventory and clears the log. Doesn't recompile. |
| `choicemode <hide\|grey\|show>` | How choices with unmet requirements are shown. `grey` (default) disables them and lists what is missing, `show` keeps them clickable with the same note, `hide` removes them. `choose <n>` ignores the mode, the engine never blocks. |
| `save` | Keeps the current story state (`saveState` JSON) in memory. Stat values are not part of it. |
| `load` | Restores the last `save` and shows the pending choices again. Speaker comes back with it. |
| `help` | Lists the commands. Comes from the console library. |
