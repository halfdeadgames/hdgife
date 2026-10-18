# HDGEIFE

HDG Interactive Fiction Engine. A game-agnostic interactive fiction library for Kotlin and [libGDX](https://libgdx.com) games, built on [Blade Ink](https://github.com/bladecoder/blade-ink-java). It doesn't care what your UI looks like or whether you use an ECS.

Ink does all the narrative: the branching story and the generated flavor text, through ink variables, functions and alternatives. The Kotlin side is a bridge to your game plus a runtime compiler.

The engine reports, the game decides. It takes ink source and stats and gives back lines, spans, markers, choices and events. It never renders, hides a choice, blocks one or acts on an event. Everything that is a decision (number format, colors, which sources to load, what a style name means) is a default you can replace, listed under "Defaults".

Version 0.2.0. It's early, the API will change. Parts marked experimental say so.

## Contents

1. [Setup](#setup)
2. [Quick start](#quick-start)
3. [Concepts](#concepts)
   - [Sources and order](#sources-and-order)
   - [Precompiled stories](#precompiled-stories)
   - [Stats](#stats)
   - [Speakers](#speakers)
   - [Tags and typed readers](#tags-and-typed-readers)
   - [Requirements](#requirements)
   - [Styles and markers](#styles-and-markers)
   - [Events](#events)
   - [Game functions](#game-functions)
   - [Variables](#variables)
   - [Number formatting](#number-formatting)
   - [Save, load and seeding](#save-load-and-seeding)
   - [Validation](#validation)
   - [Errors](#errors)
   - [Threading](#threading)
4. [The ink library reference](#the-ink-library-reference)
5. [Defaults](#defaults)
6. [API reference](#api-reference)
7. [Writing stat-driven ink](#writing-stat-driven-ink)
8. [Testing](#testing)
9. [Test harness](#test-harness)
10. [Known limits](#known-limits)

## Setup

Add JitPack to your repositories:

```groovy
repositories {
  maven { url = 'https://jitpack.io' }
}
```

Then add the dependency to your core module. JitPack builds from a git tag or a commit hash, so the version is a tag name or a hash:

```groovy
dependencies {
  api "com.github.halfdeadgames:hdgife:0.2.0"
}
```

What the library pulls in (`core/build.gradle`):

| Dependency | Scope | Why |
| --- | --- | --- |
| `com.badlogicgames.gdx:gdx` | api | `FileHandle` in `InkSource.of` and the `compile` overloads |
| `com.bladecoder.ink:blade-ink` | api | `Story` is in the public signatures |
| `com.bladecoder.ink:blade-ink-compiler` | implementation, so runtime for you | no compiler type is in a public signature, so you get it at runtime and can't import it |
| `org.jetbrains.kotlin:kotlin-stdlib` | api | Kotlin |

No KTX, no coroutines, no logging library. If you add libGDX or Blade Ink yourself, keep them in line: libGDX 1.14.2, Blade Ink 1.3.2. The build uses Kotlin 2.4.20 and a JVM 11 target. JitPack builds with openjdk17 and runs `:core:test` and `:core:publishToMavenLocal` (`jitpack.yml`).

`Hdgife.VERSION` is a `const val String` generated at build time from `projectVersion` in `gradle.properties`. `HdgifeInk.VERSION` is the version of the bundled ink library, see "The ink library reference".

The jar holds the engine, the two ink libraries (`hdgife/hdgife.ink`, `hdgife/hdgife_util.ink`, functions only, no scenes) and two classes in Blade Ink's own package (see "Known limits"). Demo stories are in the test harness, not in the jar.

## Quick start

Compile, build a manager, then `next` and `choose`:

```kotlin
val provider = NarrativeStateProvider { entity, stat -> stats["$entity.$stat"] } // any Kotlin value, or null

val story = InkCompilerService().compile(InkSource("main.ink", """
    INCLUDE hdgife.ink
    -> gate
    === gate ===
    ~ speak("guard")
    "Halt." Strength {stat_text("strength")}.
    + [Push past # requires: player.strength >= 10]
        He steps aside. -> END
    + [Leave] -> END
""".trimIndent()))

val manager = StoryManager(story, provider) {
    log = HdgifeLog { println(it) }
    numberFormat = NumberFormat(decimals = 1)
    onEvent { event -> println("event ${event.name}") }
}

var step = manager.next()
while (true) {
    for (line in step.lines) draw(line.speaker, line.spans)   // map style names to your own rendering
    if (step.isEnded) break
    for (choice in step.choices) addButton(choice.text, enabled = choice.isAvailable)
    step = manager.choose(pickedIndex)                         // works on any choice, available or not
}
```

With libGDX files, import the extensions you use:

```kotlin
import com.github.halfdeadgames.hdgife.compiler.compile
import com.github.halfdeadgames.hdgife.compiler.compileFolder

val story = InkCompilerService().compile(Gdx.files.internal("ink/main.ink"))
```

`StoryManager(story)` takes an `HdgifeStory`, which is what the compiler service returns. A plain Blade Ink `Story` built another way goes through `StoryManager.forPlainStory(story)`, see "Game functions".

## Concepts

### Sources and order

`InkCompilerService` compiles what you give it, in the order you give it. The game owns which files load, from where, in what order and what a mod may replace. The engine has no mod concept.

- `compile(sources: List<InkSource>)`: the first source is the root. Each later one is appended to the root as an `INCLUDE`, in list order. `compile(main, extras)` is the same with the root separate.
- Ink can't override a knot defined earlier. A duplicate knot is a compile error, so replacing content means the game choosing which sources to feed.
- `INCLUDE x.ink` inside a source is resolved relative to that source's own name, `..` allowed. A listed source of that name is used first, then `InkSource.sibling` is asked. A source loads once per compile, so listing a file another source also includes is fine.
- Names use `/` and must be unique within one compile. `hdgife.ink` and `hdgife_util.ink` are reserved for the bundled libraries. Violations are `IllegalArgumentException`, as is an empty list.
- `INCLUDE hdgife.ink` and `INCLUDE hdgife_util.ink` come from the classpath. They work together or alone and are never added for you.
- `InkSource(name, text)` holds text. `InkSource.lazy(name, sibling = null) { text }` reads it when the compiler needs it (a file, a network response, an archive).
- libGDX extensions (GdxSources.kt): `InkSource.of(file)`, `compile(main: FileHandle, extras = emptyList())` and `compileFolder(folder, recursive = true)`. `compileFolder` compares paths as case-sensitive text relative to the folder. The root is `main.ink` in the folder if there is one, else the first path in that order, and the rest follow in that order. Order them with file names (`000_base.ink`, `010_extra.ink`) or build your own list. No `.ink` files gives an `InkCompileException`.

To run different sources, compile again. Then either make a fresh `StoryManager` or call `manager.replaceStory(newStory)`. `replaceStory` checks the story first (a refused story leaves the manager as it was), moves your stat functions, `bindFunction`s and observers onto it, closes open styles, drops pending events and keeps `speaker`. Observers whose variable the new story doesn't declare are dropped and logged. The old story is unbound from the manager. One story should have one manager.

Compiler warnings go to `InkCompilerService.log`. Compile errors are `InkCompileException` with the messages in `errors`, which already name file and line.

### Precompiled stories

`story.toJson()` gives compiled JSON. `InkCompilerService().loadCompiled(json)` turns it back into an `HdgifeStory` with no compiler work. Bad JSON is an `InkCompileException`. A compiled story keeps its `hdgife.ink`, so the version check still applies. `HdgifeStory.from(story)` rebuilds a plain `Story` as an `HdgifeStory` with a fresh state.

### Stats

A stat is any Kotlin value. You implement `NarrativeStateProvider.getStat(entityId, statName): Any?`, a `fun interface`, so a lambda works. The engine reads it live when ink calls `stat`, `stat_of`, `has_stat`, `has_stat_of`, `stat_text`, `stat_text_of`, and when it checks requirements. Keep it cheap and don't throw.

Mapping to ink:

| Kotlin | Ink |
| --- | --- |
| `Int`, `Short`, `Byte`, `Long` in Int range | int |
| `Float`, `Double`, `Long` outside the Int range | float |
| `Boolean` | bool |
| `String`, `CharSequence`, `Char` | string |
| `Enum` | string, its `name` |
| `null` | the stat is missing |
| anything else | `valueConverter` result, else `toString()` |

A missing stat (or no provider) makes `stat` and `stat_of` give 0, `has_stat` and `has_stat_of` false, and requirements `MissingStat`. The same mapping is used for `setVariable`, `syncStats` and the results of bound functions.

`syncStats(entityId, statNames)` copies stats into ink variables named by `variableNamer` (default `entityId_statName`, characters outside `[A-Za-z0-9_]` become `_`, so `player_hp`). It skips, without asking the provider, variables the story doesn't declare with `VAR`, and stats the provider returns null for. It returns the skipped variable names. Use it when ink needs a stat as a plain variable, for `{player_hp <= 0}` style conditions. Stat values are not part of a save, so keep them in your game.

### Speakers

`manager.speaker` is the persistent speaker, the entity `stat()` and `has_stat()` read. Ink changes it with `~ speak("guard")` (use `""` for none), `beginScene(path, speaker)` sets it from Kotlin. With `hdgife.ink` it lives in the ink variable `hdgife_speaker`, so it is saved and safe during lookahead. Without the library it is a plain field and `saveState` still stores it.

A line can name a speaker for itself with a tag, `# speaker: ilse`. The key is `speakerTagKey`, case-sensitive. A tag speaker covers that one line only. `DialogLine.speaker` is the tag speaker, else the persistent speaker when the line was produced, else null. Requirements without an entity use the persistent speaker, not a tag speaker.

### Tags and typed readers

Ink tags reach you as `Tags`: `raw` (every tag, trimmed, in order) and `map`. `key: value` is split at the first colon, both sides trimmed. A bare tag (`shout`) is key `shout` with an empty value. If the text before the first colon isn't a plain word (letters, digits, `_`, `-`, `.`, no spaces), the whole tag is bare, so `Note to self: x` stays one key. Keys are case-sensitive. If a key repeats, the last wins in `map`, and `all(key)` gives every value in order.

- `tags[key]`: String or null. `has(key)`. `isEmpty`.
- `int(key)` / `int(key, default)`: optional sign and ASCII digits only. Null when missing, empty, or not a whole number that fits.
- `float(key)` / `float(key, default)`: optional sign, digits with `.`, optional exponent (`4`, `-0.5`, `.25`, `1e3`). Null for `1,5`, `NaN`, `Infinity`, `1f` and anything too large. Not affected by the default locale.
- `bool(key)` / `bool(key, default)`: `true` or `false` in any case, or `1` or `0`. A bare tag gives null here, use `has` for flags.
- `Tags.parse(list)` and `Tags.EMPTY` make one yourself.

`DialogLine.tags` includes tags written on their own lines just above the line (that is how ink attaches them). A line with no text left after cleaning hands its tags to the next line. `DialogStep.tags` is every tag of the step.

### Requirements

A choice declares requirements in a tag inside the choice text:

```ink
* [Lift the boulder # requires: strength >= 12, faction == rebels, guard.mood > 30]
```

Put the tag inside the brackets. Blade Ink 1.3.2 drops tags written after the closing `]`, so `* [Lift the boulder] # requires: strength >= 12` loses the requirement and the choice is always available. The right form is `* [Lift the boulder # requires: strength >= 12]`.

A clause is `[entity.]stat op value`. Ops are `>`, `>=`, `<`, `<=`, `==`, `!=`. Clauses are separated by commas, and the tag may repeat. Without an entity the persistent speaker is used. They are evaluated against the state provider when `next` builds the step, and come back on `DialogChoice.requirements` as `RequirementResult`s. `DialogChoice.isAvailable` is true when all are met, or there are none. `choose` never refuses an unavailable choice. Whether to hide, grey or allow it is the game's call. Plain ink conditions (`+ {door_open} [Slip out]`) are different: ink hides those itself and the engine never sees them.

Numbers compare with every op. Strings, enum names and booleans only with `==` and `!=`; a boolean value is `true` or `false` in any case; string comparison is exact. Values are bare or quoted.

Quoted names: an entity, stat or value can be wrapped in `"` or `'` to hold spaces, dots and commas:

```ink
+ [Ask # requires: "Old Varga".mood > 30, 'rank name' == "grand, high"]
```

A quote opens at the start of a part or after one of `= < > ! . ,` (so the apostrophe in `O'Brien` isn't a quote) and closes at the same character. Commas inside quotes don't split clauses. A bare value can't contain a comma.

`RequirementResult` is a sealed interface with `raw` (the clause as written) and `isMet`:

| Variant | Meaning | Fields besides `raw` |
| --- | --- | --- |
| `Met` | stat found, comparison holds | `entity`, `stat`, `op`, `target`, `actual` |
| `Unmet` | stat found, comparison fails | `entity`, `stat`, `op`, `target`, `actual` |
| `Mismatch` | stat found but the written value or operator doesn't fit its type (`strength >= high`, `faction > 3`, `is_injured == 4`). Logged, never met | `entity`, `stat`, `op`, `target`, `actual` |
| `MissingStat` | provider has no such stat, or there is no entity and no speaker (`entity` is then null). Never met | `entity: String?`, `stat`, `op`, `target` |
| `Unreadable` | the text isn't `[entity.]stat op value`. Never met | none |

`actual` is an `Int`, `Float`, `Boolean` or `String`. `target` is the written value read as the type of `actual`; a number is an `Int` if it parses as one, else a `Float`. For `Mismatch` and `MissingStat` it is a number if the text is one, else the text. A `when` over the variants should have an `else`, more may come. `op` is a `Comparison`, which is experimental (see "Validation" and the API reference).

### Styles and markers

The engine parses style markers out of lines and choice text and reports names and parameters. It gives no name a meaning.

Syntax:

| Written | Meaning |
| --- | --- |
| `<em>` | open style `em` |
| `<color=red>` | open with a value |
| `<shake amp=2 speed=0.5>` | open with params |
| `<tag key="two words">` | quoted param value (`"` or `'`, removed) |
| `</em>` | close `em` |
| `</>` | close the most recent open style |
| `<pause=0.5/>`, `<slam/>`, `<sfx name=x vol=0.8/>` | point marker, no text, no closing |
| `<<` | a literal `<` |

Names are letters, digits, `_` and `-`, starting with a letter. A value ends at a space or `>` unless quoted, or at a `/` directly before `>`. Anything that isn't a well-formed marker (`a < b`, `<3`, a lone `<`) is plain text. Open styles nest. Closing an outer style also closes the inner ones (logged). A closing marker with nothing to close is dropped and logged.

Colors: `Style.hexColor()` reads the value, or a param with `hexColor("key")`, as RGBA8888 (red in the top byte, alpha in the low byte), or null if it isn't hex. `rgb`, `rrggbb` and `rrggbbaa` are accepted, each with an optional `#` or `0x`, any case. Names like `red` give null, the game resolves them. In ink a bare `#` starts a tag, so write hex without it (`<color=ff8800>`). Ink's escape `\#` gives a literal `#` and is accepted too (`<color=\#9ecbff>`). A three-letter name made of a-f (`bad`, `ace`) reads as hex; put `0x` in front of real hex if you also use such names. `parseHexColor(text)` is the same reader as a function.

Output: `DialogLine.text` is plain text with markers removed (then run through `textCleaner`), and exactly the `spans` texts joined. Each `TextSpan` has the `styles` active over it, outermost first, empty if unstyled. Point markers are `TextMarker`s with an `offset` into the line's plain text: the marker sits before the character at `offset`, and `offset == text.length` means after the last one. `Style` and `TextMarker` have `name`, `value`, `values` (the value split at commas, trimmed), `params`, `param(key)` and `hexColor(key = null)`.

Carry across lines: an open style stays open until its closing marker. That holds across the lines of a step, across `next` calls, and over a choice into the chosen branch. Spans include styles left open by earlier lines. `DialogStep.openStyles` lists what is open at the end of a step. All open styles close, with a log message, when the story ends, on `reset`, on `replaceStory`, and when `loadState` reads a plain ink state. Choice text is the exception: it is read on its own, nothing carries in, and what is left open at its end is dropped (logged).

Save and load: `saveState` stores the open styles and `loadState` opens them again.

### Events

Ink tells the game things with `~ emit("name")`, `emit1("name", a)`, `emit2("name", a, b)` and `emit3("name", a, b, c)`. They are numbered by argument count because ink has no overloads. Arguments come back as `Int`, `Float`, `Boolean` or `String`. No `EXTERNAL` of your own is needed.

Events are on `DialogStep.events` (a `StoryEvent`: `name`, `args`, `lineIndex`, `id`) and go to `onEvent`. The engine never acts on them.

- Exactly once, in order. Ink runs ahead past a line break to see if glue (`<>`) joins the next line, and may run an `emit` and undo it. The engine keeps each event once, also between a line and its glue line. This works on both `HdgifeStory` and plain `Story` entry points.
- `lineIndex` is the index in `DialogStep.lines` of the line produced right after the call, so the event happened just before that line shows. It equals `lines.size` when no line follows in the step.
- `id` counts `emit` calls on the story timeline: the first is 1, then 2. It is the value of the ink variable `hdgife_event_seq` right after the call, so it is saved with the ink state and survives `saveState` and `loadState`. Play on from an earlier save and the event that comes again has the id it had. To not act twice on a replayed event, keep the highest id you handled together with its save and skip ids at or below it. Ids name a timeline position, so a different branch after a load can give another event the same id. `reset` starts again at 1. `manager.eventCount` is the latest id, 0 before the first.
- Timing: `onEvent` runs for each event while `next` builds the step, after ink has run to the next choice or the end and before the choices are read. A `requires` check on a choice sees what the game changed in the handler (spending a coin). A condition ink evaluates itself, like `+ {coin >= 3} [Buy]`, is decided inside ink before the event arrives, so keep such state in ink variables or use `# requires:`.
- An exception thrown in `onEvent` comes out of `next` as a `StoryStepException` whose step holds the lines and events so far.
- Don't put `emit` in choice text. It fires when the choice is created.
- Without a manager the functions do nothing.

### Game functions

`bindFunction(name, lookaheadSafe = false) { args -> result }` binds a Kotlin lambda to an ink `EXTERNAL` function. Declare it in ink and give it a fallback body:

```ink
EXTERNAL give_item(item, count)
=== function give_item(item, count) ===   // runs when nothing is bound
~ return 0
```

- The `EXTERNAL` line is required for ink to compile the call. The fallback body is required unless every run has the binding, or you set `allowExternalFunctionFallbacks = false` (then ink fails on first use with a clear message). Ink checks on the first `Continue`.
- `args` is a `FunctionArgs`: `size`, `args[i]`, `values`, `function`, and typed getters `string(i)`, `int(i)`, `float(i)`, `bool(i)`. `float` accepts an ink int, `bool` accepts an int (nonzero is true), `string` accepts anything, `int` takes only an int. A missing or wrong-typed argument is an `IllegalArgumentException` naming the function and position.
- The result is mapped like a stat. Null gives ink no value, for a function used as a statement.
- A throw inside the lambda comes out of `next` as a `StoryStepException`.
- Binding a name that is already bound by something else, including a library name, is an `IllegalArgumentException`. Binding your own name again replaces it. `unbindFunction(name)` removes it. Bindings survive `replaceStory`.

Lookahead safety. Ink runs ahead to see if glue joins the next line. A function that isn't lookahead safe ends that run ahead, so `<>` after a line that calls it may not join. Bind `lookaheadSafe = true` only for pure reads that may run early or twice. Anything with a side effect stays false. Blade Ink 1.3.2 has an inverted guard that makes safe functions fail inside strings (choice text, `"{...}"`). `HdgifeStory` fixes it:

- On an `HdgifeStory` (constructor and `replaceStory`), the stat functions are bound lookahead safe and your safe functions work in strings and in glue lookahead. `isLookaheadSafe` is true.
- On a plain `Story` (`forPlainStory`, `replacePlainStory`), the stat functions are bound not safe, which works everywhere except that they end glue lookahead. `isLookaheadSafe` is false, and safe functions don't work inside strings.
- A function bound not safe and called while ink builds a string is a story error (ink says to put the result in a `temp` first). It comes out of `next` as a `StoryStepException`.

### Variables

- `getVariable(name)`: an `Int`, `Float`, `Boolean` or `String` (an `InkList` for lists), or null if the story declares no such variable.
- `getVariable(name, Int::class)` and `variableAs<Int>(name)`: typed. Null when undeclared. An ink int is accepted as `Float`, `Double` or `Long`, a float as `Double`. Any other mismatch is an `IllegalArgumentException`.
- `setVariable(name, value)`: mapped like stats (an `InkList` is kept). `IllegalArgumentException` if undeclared. Observers fire as for any change.
- `variableNames`: all declared global variables, sorted, `VAR` and `CONST` not told apart. Includes the library's own, which start with `hdgife_`.
- `observe(name) { old, new -> }` returns a `VariableObservation`. It fires when ink or `setVariable` changes the variable. Ink reports changes in a batch when a `next` finishes, so `old` is the value at the last report, not each step in between. `old` starts as the value when you called `observe`. `IllegalArgumentException` if undeclared. Stop it with `unobserve(observation)`, `unobserve(name)` (every observation of that variable) or `observation.close()`. `isActive` turns false.

### Number formatting

`NumberFormat(decimals = 0, rounding = Rounding.ROUND, trimTrailingZeros = false)` is immutable and locale-independent: the separator is always `.`, no grouping. `decimals` is 0 to 9 (`IllegalArgumentException` otherwise). Rounding works on the shortest decimal form of the number, so `7.65f` is treated as 7.65. `withDecimals`, `withRounding` and `withTrimTrailingZeros` give copies. `format` takes `Float`, `Double`, `Long` or any `Number` (Int, Short, Byte and Long exactly). NaN and infinities print as `NaN`, `Infinity`, `-Infinity`.

`Rounding`: `FLOOR` (toward negative infinity), `CEIL` (toward positive), `ROUND` (nearest, ties away from zero), `TRUNCATE` (toward zero). `Rounding.parse(name)` reads `floor`, `ceil` or `ceiling`, `round`, `trunc` or `truncate`, any case, else null. More modes may come, give a `when` an `else`.

In ink:

- `stat_text(name)` and `stat_text_of(entity, name)` print a stat with `manager.numberFormat`, or the format `formatOverride` returns for that entity and stat (null means use the default). A missing stat prints as 0. A non-number stat prints its mapped value as text.
- `fmt(x, decimals, mode)` formats any number. Decimals are clamped to 0..9. Mode is a name `Rounding.parse` reads. An unknown mode is logged and rounds.
- Without a manager the ink fallbacks do the same modes with ink math. Whole results are exact; with decimals the last digit can be off. Decimals above 6 aren't supported there.

### Save, load and seeding

`saveState()` returns one opaque string: ink's state (variables, position, visit counts, random seed, and the speaker with `hdgife.ink`) plus the open styles, in a small versioned envelope. Store it as is and load it only into the same compiled story. Stat values are not in it, they live in your game.

`loadState(json)` restores it. Output continues from there, open styles are open again, pending events are dropped, observers are rebased and `lastSeed` becomes null (the seed itself is in the loaded state). It also reads a plain ink state JSON, in which case open styles are closed. A newer save version, a broken envelope, or a state ink refuses is a `SaveStateException`.

`reset()` restarts the story: ink state, speaker, open styles, pending events. Bindings, observers and settings stay. It doesn't recompile.

`seedRandom(seed)` makes ink's `RANDOM` and shuffles repeatable. It needs `INCLUDE hdgife.ink` (`IncompatibleLibraryException` otherwise). The seed is stored in the ink state, so `saveState` and `loadState` carry it and the sequence continues exactly. `lastSeed` is the last seed set since the story was assigned, loaded or reset. There is no unseed, `reset` and don't seed. Call `seedRandom` again after `reset`.

### Validation

Experimental, `@ExperimentalHdgifeApi`. `manager.validateStory()` reads the compiled story and its variables and reports what is wrong or risky, without running anything or throwing. It knows the manager's bindings, observers and `requirementsTagKey`. Run it after binding, in a debug build or a test. For a story with no manager, `StoryValidator.validate(story)` does the same with the story's own bindings and the key `"requires"`.

`StoryReport` has `issues` (most serious first), `errors`, `warnings`, `infos`, `isOk` (no errors) and `of(kind)`. A `ValidationIssue` has `severity`, `kind`, `subject` and `message`.

| IssueKind | Severity | When |
| --- | --- | --- |
| `UNBOUND_EXTERNAL` | error | an `EXTERNAL` the story calls has no binding and no ink fallback, or fallbacks are off |
| `EXTERNAL_USES_FALLBACK` | info | no binding, the ink fallback will run |
| `LIBRARY_WITHOUT_INCLUDE` | warning | the story defines a reserved library name without including the file it belongs to |
| `OLD_LIBRARY` | error | the included `hdgife.ink` isn't the engine's version (old, no version, or newer) |
| `UNDECLARED_OBSERVED_VARIABLE` | error | an observed variable isn't declared |
| `UNREADABLE_REQUIREMENT` | warning | a `requires` clause the engine can't read |

The requirement scan is best effort: it sees tags written literally in the source. Tags with `{...}` inside or added at runtime aren't seen, and whether an entity or stat exists is only known at run time. An `EXTERNAL` that is declared but never called isn't in the compiled story and isn't seen.

### Errors

Every failure the engine throws on purpose for something a game can meet at run time extends `HdgifeException` (a `RuntimeException`). Catch it for all, or a subclass.

| Exception | Thrown by | Carries |
| --- | --- | --- |
| `InkCompileException` | `compile`, `compileFolder`, `loadCompiled`: ink didn't compile, a source couldn't be read, an include isn't listed, compiled JSON couldn't load, no `.ink` files in a folder | `errors: List<String>`, `source: String?` (the unreadable source, else null) |
| `StoryStepException` | `next` and `choose`: ink failed while continuing (a bound function threw, the state provider threw, `onEvent` threw, a story runtime error) | `step`: the partial `DialogStep` built so far (no choices, not ended). The original exception is the cause |
| `SaveStateException` | `loadState`: broken envelope, newer save version, or ink refused the state | cause is ink's exception when it was ink |
| `IncompatibleLibraryException` | the constructor, `forPlainStory`, `replaceStory`, `replacePlainStory` when the story's `hdgife.ink` is another version (also logged first); `seedRandom` when the library is missing | `found` (0 for none or an old one with no version), `expected` (`HdgifeInk.VERSION`) |
| `StoryPathException` | `beginScene`: ink can't find the knot or stitch. The speaker is left as it was | `path`, cause is ink's `StoryException` |

Mistakes in how the API is called are plain `IllegalArgumentException`: an unknown choice index in `choose`, an undeclared variable in `setVariable` or `observe`, a bad `getVariable` type, a name already bound in `bindFunction`, a bad `FunctionArgs` read, `NumberFormat` decimals outside 0..9, an empty or duplicate or reserved source list.

After a `StoryStepException` the ink story is in whatever state ink left it. The usual move is `loadState` or `reset`. The manager stays consistent with the partial step: open styles and events cover exactly its lines, and events of the failed `Continue` are dropped.

### Threading

`Story` is not thread-safe, and neither is `StoryManager`. Use a manager and its story from one thread at a time, and every callback runs on the thread that called in: the state provider, bound functions, `onEvent`, observers and `HdgifeLog` all run inside `next`, `choose`, `setVariable` or whichever call triggered them. Compiling is plain blocking and CPU work, so run `InkCompilerService` calls on any thread or coroutine context you like (the harness compiles on `Dispatchers.IO` and uses the manager on the render thread). The finished `HdgifeStory` is then handed over to the thread that uses it. `InkNativePatch` is applied once, synchronized.

## The ink library reference

Two files ship in the jar. Include them yourself at the top of your main ink file:

```ink
INCLUDE hdgife.ink
INCLUDE hdgife_util.ink
```

They contain no scenes or story content. In `hdgife.ink` the stat, number-format and event functions are `EXTERNAL`, bound by `StoryManager`. The ink bodies are fallbacks for when nothing is bound, so a story still runs without a manager (stats read 0, `has_stat` is false, events do nothing).

hdgife.ink:

| Function | Returns / does |
| --- | --- |
| `stat(name)` | the persistent speaker's stat, as the game stores it (int, float, string, bool). 0 if missing |
| `stat_of(entity, name)` | same for any entity |
| `has_stat(name)` | true if the speaker has the stat |
| `has_stat_of(entity, name)` | true if the entity has it |
| `stat_text(name)` | the speaker's stat printed with the game's number format. Use inline: `{stat_text("hp")}` |
| `stat_text_of(entity, name)` | same for any entity |
| `fmt(x, decimals, mode)` | prints a number. Mode is `"floor"`, `"ceil"` (or `"ceiling"`), `"round"`, `"trunc"` (or `"truncate"`). Use inline |
| `speak(name)` | makes the entity the speaker until changed, `""` for none. Plain ink, not an `EXTERNAL`, so it needs no binding |
| `emit(name)`, `emit1(name, a)`, `emit2(name, a, b)`, `emit3(name, a, b, c)` | events for the game, used as statements: `~ emit1("give_item", "sword")` |

Also in the file: `VAR hdgife_speaker`, `VAR hdgife_event_seq`, `CONST hdgife_version`, the externals `hdgife_emit0` to `hdgife_emit3`, and the helpers `hdgife_pow10`, `hdgife_digits`, `hdgife_zeros`, `hdgife_seed` (used by `seedRandom`) and `hdgife_lib_version`. Leave them alone.

`fmt` and the `stat_text` fallbacks print text directly, so call them inline, not as `~ temp t = fmt(...)`. Ink integer division truncates, so use `/ 2.0` when averaging whole numbers. `FLOOR`, `CEILING` and `INT` on floats work because of the engine's patch (applied by the compiler service and `StoryManager`).

hdgife_util.ink (plain ink, no `EXTERNAL`, works without a manager, stands on its own, so including both in any order is fine):

| Function | Returns |
| --- | --- |
| `chance(pct)` | true with a `pct` percent chance (0 or less never, 100 or more always) |
| `clamp(x, lo, hi)` | `x` limited to `lo..hi` |
| `avg2(a, b)`, `avg3(a, b, c)` | float averages |
| `blend(a, wa, b, wb)` | `(a*wa + b*wb) / (wa + wb)` |
| `invert(x)` | `100 - x` |
| `scale100(x, lo, hi)` | `x` mapped from `lo..hi` onto 0..100, clamped |

plus `hdgife_util_loaded()`, which only marks the file as included so `validateStory` can tell it from your own functions.

Reserved names. The function names are not prefixed, so don't define your own with these names, whether your story includes the library or not: `stat`, `stat_of`, `has_stat`, `has_stat_of`, `stat_text`, `stat_text_of`, `fmt`, `speak`, `emit`, `emit1`, `emit2`, `emit3`, `chance`, `clamp`, `avg2`, `avg3`, `blend`, `invert`, `scale100`, and everything starting with `hdgife_`. Parameters and temps inside the libraries start with `hdg_`, so your story can have `VAR n` or `x`; don't declare knots or variables starting with `hdg_` or `hdgife_`. A manager rebinds the external names to its own functions, replacing yours. `HdgifeInk.LIBRARY_FUNCTIONS` lists them all, generated from the two files at build time. `validateStory` warns about one defined without its library.

Versioning. `CONST hdgife_version` in `hdgife.ink` is `HdgifeInk.VERSION` (currently 1). A story that includes a `hdgife.ink` of another version, or an old one with no version (counted as 0), is refused by `StoryManager` with an `IncompatibleLibraryException`, and `validateStory` reports `OLD_LIBRARY`. Recompile the story against the library bundled with the engine you use. A story that doesn't include `hdgife.ink` isn't checked. If you change, add or remove a function in the libraries, bump `hdgife_version` and pin the new hash in `LibraryPinTest`, which fails with "library changed: bump hdgife_version" until you do.

## Defaults

Every default that is a game decision can be replaced.

| Setting | Default | Notes |
| --- | --- | --- |
| `StoryManager` `stateProvider` | `null` | no stats: `stat` gives 0, `has_stat` false, requirements `MissingStat` |
| `variableNamer` | `StoryManager.defaultVariableName`: `entity_stat`, characters outside `[A-Za-z0-9_]` become `_` | for `syncStats` |
| `numberFormat` | `NumberFormat(decimals = 0, rounding = ROUND, trimTrailingZeros = false)` | used by `stat_text` |
| `formatOverride` | always returns null | per-stat `NumberFormat`, null falls back to `numberFormat`. Set with `formatOverride = NumberFormatOverride { entity, stat -> ... }` or `formatOverride { entity, stat -> ... }` |
| `textCleaner` | `cleanLine`: collapse spaces and tabs, no space before `, . ; : ! ?`, trim | English punctuation. Runs on plain text after markers are removed. Replace it for other languages, or `{ it }` to turn it off |
| `speakerTagKey` | `"speaker"` | case-sensitive |
| `requirementsTagKey` | `"requires"` | |
| `allowExternalFunctionFallbacks` | `true` | `hdgife.ink` relies on it. With false every `EXTERNAL` needs a binding (`speak` isn't one). Applied to the current story and any later one |
| `log` (`StoryManager`, `InkCompilerService`) | `null` | the engine logs nothing unless you set one. Covers unknown `fmt` mode, unbalanced styles, requirement mismatches and unreadable clauses, dropped observers, ink warnings, refused libraries |
| `valueConverter` | converts nothing | unmapped types become `toString()`. Set with `ValueConverter { value -> ... }` or `valueConverter { value -> ... }` |
| `onEvent` | does nothing | events are on `DialogStep.events` either way. Set with `StoryEventListener { event -> ... }` or `onEvent { event -> ... }` |
| `bindFunction` `lookaheadSafe` | `false` | true for pure reads only |
| constructor `configure` | `{}` | runs last, sets properties |
| `compileFolder` `recursive` | `true` | also takes files in subfolders |
| stories from the compiler service | external fallbacks allowed | `loadCompiled` too |
| `InkSource.lazy` `sibling` | `null` | everything must be listed |
| `beginScene` `speaker` | `null` | clears the speaker |
| `NumberFormat` | 0 decimals, `ROUND`, keep zeros | |
| libraries | not included for you | `INCLUDE` them |
| seed | none | ink seeds itself randomly until you call `seedRandom` |
| style, effect and marker names | no meaning | the game decides |
| choices | all returned | `isAvailable` only reports |
| save format | version 1 | opaque. `loadState` reads versions 1 up to the current, and a plain ink state |

## API reference

Everything public in `core/api/core.api`, grouped by package. Properties are listed as Kotlin properties. The `is`-prefixed Boolean properties (`isMet`, `isAvailable`, `isEnded`, `isEmpty`, `isOk`, `isActive`, `isEquality`, `isLookaheadSafe`) are `isX()` in Java.

Experimental (`@ExperimentalHdgifeApi`, `RequiresOptIn` level WARNING, so using it compiles with a warning): `Comparison`, `Severity`, `IssueKind`, `ValidationIssue`, `StoryReport`, `StoryValidator` and `StoryManager.validateStory()`. These may change in a minor release. Opt in with `@OptIn(ExperimentalHdgifeApi::class)` or the compiler option `-opt-in=com.github.halfdeadgames.hdgife.ExperimentalHdgifeApi`. `RequirementResult` itself isn't marked, but reading its `op` gives you a `Comparison`. The core module opts in for its own code. The library is built with explicit API mode.

### com.github.halfdeadgames.hdgife

| Declaration | Notes |
| --- | --- |
| `object Hdgife` | `const val VERSION: String` |
| `object HdgifeInk` | `const val VERSION: Int` (library version), `val LIBRARY_FUNCTIONS: List<String>` |
| `fun interface HdgifeLog` | `log(message: String)` |
| `abstract class HdgifeException(message: String, cause: Throwable? = null) : RuntimeException` | base of the engine's exceptions |
| `annotation class ExperimentalHdgifeApi` | the opt-in marker above |

### com.github.halfdeadgames.hdgife.compiler

| Declaration | Notes |
| --- | --- |
| `class InkCompilerService()` | |
| `var log: HdgifeLog?` | ink warnings and notes, null drops them |
| `compile(sources: List<InkSource>): HdgifeStory` | first is the root |
| `compile(main: InkSource, extras: List<InkSource> = emptyList()): HdgifeStory` | |
| `loadCompiled(json: String): HdgifeStory` | from `story.toJson()` |
| `compile(main: FileHandle, extras: List<FileHandle> = emptyList()): HdgifeStory` | extension in GdxSources.kt, UTF-8 |
| `compileFolder(folder: FileHandle, recursive: Boolean = true): HdgifeStory` | extension in GdxSources.kt |
| `class InkSource(name: String, text: String)` | |
| `InkSource.name`, `read(): String`, `toString()` | `read` is called at most once per compile |
| `InkSource.lazy(name: String, sibling: ((relativePath: String) -> InkSource?)? = null, text: () -> String): InkSource` | companion, `@JvmStatic` |
| `InkSource.of(file: FileHandle): InkSource` | extension on the companion. Name is the file's path. Unlisted includes are looked up next to the file |
| `class InkCompileException : HdgifeException` | `errors: List<String>`, `source: String?` |

### com.github.halfdeadgames.hdgife.state

| Declaration | Notes |
| --- | --- |
| `fun interface NarrativeStateProvider` | `getStat(entityId: String, statName: String): Any?` |

### com.github.halfdeadgames.hdgife.story

StoryManager.

| Member | Notes |
| --- | --- |
| `StoryManager(story: HdgifeStory, stateProvider: NarrativeStateProvider? = null, configure: StoryManager.() -> Unit = {})` | throws `IncompatibleLibraryException` for another library version |
| `StoryManager.forPlainStory(story: Story, stateProvider: NarrativeStateProvider? = null, configure: StoryManager.() -> Unit = {})` | companion, `@JvmStatic`. `isLookaheadSafe` is false |
| `StoryManager.defaultVariableName(entityId: String, statName: String): String` | companion, `@JvmStatic` |
| `val story: Story` | the wrapped story |
| `var stateProvider: NarrativeStateProvider?` | |
| `var variableNamer: (entityId: String, statName: String) -> String` | |
| `var log: HdgifeLog?` | |
| `var speakerTagKey: String`, `var requirementsTagKey: String` | |
| `var textCleaner: (String) -> String` | |
| `var numberFormat: NumberFormat` | |
| `var formatOverride: NumberFormatOverride`, `fun formatOverride(override: NumberFormatOverride)` | |
| `var allowExternalFunctionFallbacks: Boolean` | |
| `var valueConverter: ValueConverter`, `fun valueConverter(converter: ValueConverter)` | |
| `var onEvent: StoryEventListener`, `fun onEvent(listener: StoryEventListener)` | |
| `var speaker: String?` | |
| `val lastSeed: Int?` | |
| `val isLookaheadSafe: Boolean` | |
| `val eventCount: Int` | latest event id, 0 before the first |
| `val variableNames: List<String>` | sorted |
| `fun next(): DialogStep` | continues to the next choice or the end. With choices pending it returns them again with no lines |
| `fun choose(index: Int): DialogStep` | `IllegalArgumentException` for an unknown index |
| `fun beginScene(path: String, speaker: String? = null)` | `knot` or `knot.stitch`. Call `next` afterwards |
| `fun replaceStory(story: HdgifeStory)`, `fun replacePlainStory(story: Story)` | see "Sources and order" |
| `fun reset()` | |
| `fun saveState(): String`, `fun loadState(json: String)` | |
| `fun seedRandom(seed: Int)` | |
| `fun syncStats(entityId: String, statNames: List<String>): List<String>` | returns skipped variable names |
| `fun bindFunction(name: String, lookaheadSafe: Boolean = false, function: (FunctionArgs) -> Any?)` | |
| `fun unbindFunction(name: String)` | |
| `fun getVariable(name: String): Any?` | |
| `fun <T : Any> getVariable(name: String, type: KClass<T>): T?` | |
| `inline fun <reified T : Any> variableAs(name: String): T?` | public, but not in `core.api` (see below) |
| `fun setVariable(name: String, value: Any)` | |
| `fun observe(name: String, observer: (old: Any?, new: Any?) -> Unit): VariableObservation` | |
| `fun unobserve(observation: VariableObservation)`, `fun unobserve(name: String)` | |
| `@ExperimentalHdgifeApi fun validateStory(): StoryReport` | |

Top-level functions: `cleanLine(raw: String): String` (the default `textCleaner`) and `parseHexColor(text: String): Int?`.

Output types.

| Class | Members |
| --- | --- |
| `DialogStep` | `lines: List<DialogLine>`, `text: String` (lines joined with `\n`), `tags: Tags`, `choices: List<DialogChoice>`, `isEnded: Boolean`, `events: List<StoryEvent>`, `openStyles: List<Style>` |
| `DialogLine` | `text: String`, `speaker: String?`, `tags: Tags`, `spans: List<TextSpan>`, `markers: List<TextMarker>` |
| `DialogChoice` | `index: Int`, `text: String`, `spans: List<TextSpan>`, `tags: Tags`, `requirements: List<RequirementResult>`, `markers: List<TextMarker>`, `isAvailable: Boolean` |
| `StoryEvent` | `name: String`, `args: List<Any?>`, `lineIndex: Int`, `id: Int` |
| `TextSpan` | `text: String`, `styles: List<Style>` |
| `Style` | `name: String`, `value: String?`, `values: List<String>`, `params: Map<String, String>`, `param(key): String?`, `hexColor(key: String? = null): Int?` |
| `TextMarker` | `name: String`, `offset: Int`, `value: String?`, `values: List<String>`, `params: Map<String, String>`, `param(key): String?`, `hexColor(key: String? = null): Int?` |
| `Tags` | `raw: List<String>`, `map: Map<String, String>`, `get(key): String?`, `has(key)`, `isEmpty`, `all(key): List<String>`, `int(key): Int?`, `int(key, default: Int): Int`, `float(key): Float?`, `float(key, default: Float): Float`, `bool(key): Boolean?`, `bool(key, default: Boolean): Boolean`; companion `Tags.parse(raw: List<String>)`, `Tags.EMPTY` |

All the output classes have value `equals`, `hashCode` and `toString`, and none can be constructed from outside, except `Tags` through `parse`.

Requirements.

| Declaration | Members |
| --- | --- |
| `sealed interface RequirementResult` | `raw: String`, `isMet: Boolean` |
| `RequirementResult.Met`, `.Unmet`, `.Mismatch` | `entity: String`, `stat: String`, `op: Comparison`, `target: Any`, `actual: Any` |
| `RequirementResult.MissingStat` | `entity: String?`, `stat: String`, `op: Comparison`, `target: Any` |
| `RequirementResult.Unreadable` | nothing besides `raw` |
| `enum class Comparison` (experimental) | `GREATER`, `GREATER_EQUAL`, `LESS`, `LESS_EQUAL`, `EQUAL`, `NOT_EQUAL`; `symbol: String`, `isEquality: Boolean`; `Comparison.parse(symbol: String): Comparison?` |

Callbacks and helpers.

| Declaration | Notes |
| --- | --- |
| `fun interface NumberFormatOverride` | `formatFor(entityId: String, statName: String): NumberFormat?` |
| `fun interface ValueConverter` | `convert(value: Any): Any?` |
| `fun interface StoryEventListener` | `onEvent(event: StoryEvent)` |
| `class FunctionArgs` | `function: String`, `values: List<Any?>`, `size: Int`, `get(index): Any?`, `string(index)`, `int(index)`, `float(index)`, `bool(index)` |
| `class VariableObservation : AutoCloseable` | `name: String`, `isActive: Boolean`, `close()` |
| `class NumberFormat(decimals: Int = 0, rounding: Rounding = Rounding.ROUND, trimTrailingZeros: Boolean = false)` | `decimals`, `rounding`, `trimTrailingZeros`, `withDecimals(Int)`, `withRounding(Rounding)`, `withTrimTrailingZeros(Boolean)`, `format(Float)`, `format(Double)`, `format(Long)`, `format(Number)` |
| `enum class Rounding` | `FLOOR`, `CEIL`, `ROUND`, `TRUNCATE`; `Rounding.parse(name: String): Rounding?` |

Exceptions (all extend `HdgifeException`; see "Errors").

| Class | Members |
| --- | --- |
| `StoryStepException` | `step: DialogStep` |
| `SaveStateException` | none |
| `IncompatibleLibraryException` | `found: Int`, `expected: Int` |
| `StoryPathException` | `path: String` |

Validation (all experimental).

| Declaration | Members |
| --- | --- |
| `object StoryValidator` | `validate(story: Story): StoryReport` (`@JvmStatic`) |
| `class StoryReport` | `issues`, `errors`, `warnings`, `infos`: `List<ValidationIssue>`; `isOk: Boolean`; `of(kind: IssueKind): List<ValidationIssue>` |
| `class ValidationIssue` | `severity: Severity`, `kind: IssueKind`, `subject: String`, `message: String` |
| `enum class Severity` | `INFO`, `WARNING`, `ERROR` |
| `enum class IssueKind` | `UNBOUND_EXTERNAL`, `EXTERNAL_USES_FALLBACK`, `LIBRARY_WITHOUT_INCLUDE`, `OLD_LIBRARY`, `UNDECLARED_OBSERVED_VARIABLE`, `UNREADABLE_REQUIREMENT` |

### com.bladecoder.ink.runtime

This is Blade Ink's own package, because the patch needs package-private access.

| Declaration | Notes |
| --- | --- |
| `class HdgifeStory : Story` | a Blade Ink `Story` with the lookahead-safe function fix. Made by the compiler service, or by `HdgifeStory.from(story)` (a companion `@JvmStatic`). A plain story is rebuilt from its compiled JSON, so its state is fresh; an `HdgifeStory` is returned as is |
| `bindExternalFunction(funcName: String, func: ExternalFunction<*>, lookaheadSafe: Boolean)`, `unbindExternalFunction(funcName: String)` | overrides, the normal `Story` API |

## Writing stat-driven ink

A trimmed version of `lwjgl3/assets/ink/main.ink`. The stats live in the game. Ink reads them with `stat()`, builds combined metrics with the util functions, scales flavor text by them, and tells the game things with `emit`.

```ink
INCLUDE hdgife.ink
INCLUDE hdgife_util.ink

EXTERNAL roll(sides)                // bound by the game, not lookahead safe (dice)
=== function roll(sides) ===        // fallback for tools without the game
~ return 1

// Combined metric: invert() and avg3() are from hdgife_util.ink, stat() reads the speaker.
=== function strain() ===
~ return avg3(stat("tiredness"), invert(stat("mood")), invert(stat("sanity")))

=== varga_talk ===
~ speak("varga")
"Papers." {strain() > 60: He sways on his feet.|{strain() > 35: He looks worn but steady.|He stands crisp and alert.}}
Strain {fmt(strain(), 1, "round")}, trust {stat_text("trust")}.
// glue across a stat call: the stat functions are lookahead safe, so the lines still join
He keeps his eyes on you,
{stat("mood") > 50: <>} patience at {stat_text("mood")} percent.
+ [Bluff: "I know the Colonel." # requires: player.wits >= 7, trust > 20]
  ~ temp total = roll(20) + stat_of("player", "wits")
  { total >= 14:
    "The Colonel, is it." He stamps something. <slam/>
    ~ emit1("give_item", "pass")
  - else:
    "You are not him."
    ~ emit2("suspicion_up", "varga", 2)
  }
  -> varga_talk
+ [Buy him a drink for 3 coin # requires: player.coin >= 3]
  ~ emit2("spend", "coin", 3)       // the game takes the coin in onEvent
  -> varga_talk
+ [Back] -> END
```

Things to notice: `requires` uses the speaker when no entity is given (`trust`), and `player.wits` names one. The `spend` event is handled in `onEvent` before the choices are read, so the requirements of the next step already see the lower coin total. The harness's own `main.ink` also shows `<color=ff8800>` and `<color=\#9ecbff>` markers, `{&...}` and `{~...}` alternatives, a style left open over a choice, and an `emit` between a line and its glue line.

## Testing

```
./gradlew :core:test            JUnit 5 unit tests of the engine
./gradlew :core:checkKotlinAbi  compares the public API with core/api/core.api (also part of :core:check and build)
./gradlew :core:updateKotlinAbi rewrites core/api/core.api after an intended API change
./gradlew :core:publishToMavenLocal   to try a build in another project (add mavenLocal(), use version 0.2.0)
```

`core/api/core.api` is committed. `checkKotlinAbi` fails when the code and the dump differ, so after an API change run `updateKotlinAbi` and commit the dump with the change. The JitPack build runs only `:core:test` and `:core:publishToMavenLocal`, which don't run `checkKotlinAbi`, so a stale dump never fails JitPack. Every public declaration says `public` and names its type (explicit API mode).

`LibraryPinTest` pins the sorted function names and arities of the two ink libraries to `hdgife_version`.

The canary tests (`UpstreamCanaryTest`, with `UpstreamProbe`) run the unpatched upstream Blade Ink classes in a separate class loader and expect its bugs:

- `FLOOR(2.5)` on a float still throws `ClassCastException`.
- A lookahead-safe function in a string is still rejected.
- An unsafe function is still let into a string.

When a Blade Ink upgrade fixes one, a canary fails. That is the signal to revisit `InkNativePatch` or `HdgifeStory`, then update or delete the canary.

## Test harness

The `lwjgl3` module is a small desktop app for poking at things. It isn't published. It owns the KTX, scene2d and console dependencies, its own `lwjgl3/assets/` (UI skin and the demo ink) and its own load policy: `ink/main.ink` plus any `.ink` files under `lwjgl3/assets/extra/`, sorted by path. Run it with:

```
./gradlew lwjgl3:run
```

### Display

The harness shows only the latest `DialogStep`, in two panels:

- RAW (left): the exact engine output as text, in a small monospace-ish font, scrollable with the mouse wheel. Every field is there: each line with text, speaker, raw and parsed tags, spans with their styles (hex colors also as `hexColor()` reads them) and markers with offsets; the step tags; each choice with `available`, tags, spans, markers and every requirement (`raw`, entity, stat, op, target and actual with their types, the result class and `isMet`); events with `lineIndex` and `id`; `openStyles`; `ended`. After a `StoryStepException` it shows the partial step with the error. After a `load` a note says the lines were kept by the harness.
- RENDERED (right): the same step the way a game might draw it. Speaker names get a color, spans are colored (names through libGDX's `Colors` table, hex through `hexColor()`, `em` and `b` fixed colors), an effect span is followed by a dim `~name` (a `shake` line shakes for a moment, `wave` and `bounce` bob), point markers are written as `|name=value|` where they sit, tags are dim under their line, events are gold `!` rows where they happened, a style still open is noted, and choices are buttons that follow `choicemode`, with unmet requirements and the current values.

F2 or the `view` command changes the layout: split, raw only, rendered only. An info line under the panels shows the layout, the choice mode and the latest value of the observed ink variables (`suspicion`, `door_open`, `player_coin`). When the story ends a Restart button appears. The backtick key toggles the console. Engine log lines go to the console prefixed `engine:`.

The harness acts on events (the engine never does): `give_item` fills an inventory that ink reads back through the bound `inventory_count`, and `spend` takes coin from the player and mirrors it to the ink variable. `roll(sides)` is bound without `lookaheadSafe` (dice are a side effect), `player_name()` and `inventory_count(item)` with it. Trust has a per-stat number format (one decimal, not for Tobin).

### Console commands

| Command | What it does |
| --- | --- |
| `vars` | lists every ink variable with its value |
| `var <name>` | reads one variable through the engine and shows its type, or says it isn't declared |
| `observe <name>` | logs every change of an ink variable (`name: old -> new`) |
| `unobserve <name>` | stops that |
| `set <name> <value>` | sets an ink variable keeping its current type |
| `set <name> <value> <type>` | same with an explicit type: `int`, `float`, `bool` or `string`. Nothing changes if the value doesn't parse |
| `stat <entity> <stat> <value>` | sets a stat on the test provider and syncs it to ink. The value is read as an int, a float, `true` or `false`, else text |
| `hp <value>` | shortcut for `stat player hp <value>` |
| `npc <entity> <stat> <value>` | sets a stat on the provider only. Ink reads it live, no sync |
| `resetstats` | puts every stat back to the harness defaults (`Showcase.kt`) |
| `talk <knot> [speaker]` | `beginScene`: sets the speaker and jumps to the knot |
| `divert <path>` | jumps to an ink path (`knot` or `knot.stitch`) and continues. Open styles stay open |
| `seed <n>` | seeds ink's random and the harness dice. `restart` first if you want the same result twice |
| `fixroll <n\|off>` | makes the bound `roll(sides)` always give `n`, or random again |
| `numfmt <decimals> <mode>` | changes `manager.numberFormat`. Mode is `floor`, `ceil`, `round` or `trunc`. Decimals 0 to 9 |
| `choose <n>` | picks a choice by index, starting at 0, whatever its requirements say |
| `choicemode <hide\|grey\|show>` | how unmet choices are shown. `grey` (default) disables them and lists what is missing, `show` keeps them clickable with the same note, `hide` removes them |
| `view [split\|raw\|rendered]` | the layout. Without an argument it goes to the next one, like F2 |
| `validate` | runs `validateStory()` and lists the issues |
| `reload` | recompiles `main.ink` plus `extra/`, calls `replaceStory` and starts over |
| `restart` | `manager.reset()`, clears the harness inventory and puts the player's coin back to 12. Other stats stay |
| `save` | keeps `saveState()` and the step on screen in memory. Stat values are not part of it |
| `load` | restores the last `save` and shows its step again, with choices and open styles read from the engine |
| `help` | lists the commands (from the console library) |

### The story and debug.ink

`ink/main.ink` is "The Lantern Pass", a short story that uses the engine's features. Three NPCs (`varga`, `ilse`, `tobin`) have `sanity`, `tiredness`, `mood`, `trust` (a float, an int for Tobin), `faction` (string), `is_injured` (bool) and `stance` (an enum). The player has `grit`, `wits`, `charm`, `coin` and `hp`. It covers combined metrics, `chance()` flavor, `stat_text`, `stat_text_of` and `fmt` in every mode, glue across a stat call and across an `emit`, `speak()` and `# speaker:` tags, requirements of every kind, styles, effects and point markers, events with 0 to 3 arguments, the bound functions, and two endings.

`ink/debug.ink` holds the older test scenes. Reach the menu with `divert debug_menu`. It has a guard chat, a gate exchange (two speakers, a tag speaker, styles spanning lines), an open rumor (an `emit` before a glue line and a style left open over a choice, for save and load), odd requirements (mismatch, unreadable, missing stat, no entity, two `requires` tags), a health check, a coverage lab (`has_stat`, the `fmt` aliases and an unknown `fmt` mode that logs), and an unsafe function lab (`roll(6)` in choice text, which fails: the raw panel shows the line built before the failure and the error).

### UI test

```
./gradlew :lwjgl3:uiTest
./gradlew :lwjgl3:uiTest -PuiShow
```

`uiTest` plays the harness through its real UI and checks what is on screen. It starts `TestGame` in a real LWJGL3 window (1280x720), clicks the choice buttons with real touch events at their stage coordinates, presses F2, scrolls the raw panel, and uses the console for setup. Each scenario asserts on both panels: the raw text, the rendered rows and the buttons. It walks the Lantern Pass, the three layouts, the debug scenes and the console. The window is hidden unless you pass `-PuiShow`.

It takes screenshots into `lwjgl3/build/ui-shots/` and, with the window shown, fails if one is blank (with a hidden window it only warns, since a hidden window may not render on every system). On failure it prints the reason per scenario, saves a `FAILED-...png` and exits non-zero. On success it prints a summary and exits 0. It gives up after 180 seconds. It needs a display and a GPU driver, so it isn't part of `:core:test`, `build` or JitPack. The code is in `lwjgl3/src/uiTest`, a separate source set that isn't in the harness jar. So far it has only been run on Windows.

## Known limits

- Blade Ink 1.3.2 is patched from inside its own package, because the bugs can't be reached any other way. `HdgifeStory` fixes the inverted lookahead-safe guard. `InkNativePatch` fixes `FLOOR`, `CEILING` and `INT` on floats. Both are in `com.bladecoder.ink.runtime` (no reflection), so a Java module can't hold the split package and this is classpath only. `UpstreamCanaryTest` tells you when upstream fixes one, see "Testing".
- Android and web (TeaVM) are untested. Precompiled stories (`loadCompiled`) avoid compiler work at runtime, but I haven't tried them there.
- The UI test has only been run on Windows.
- Conditions ink evaluates itself (`+ {coin >= 3} [Buy]`) are decided before events reach the game, so a game that changes state in `onEvent` should keep that state in ink variables or use `# requires:`.
- Mods are the game's job. Ink can't override an existing knot, so replacing content means the game choosing which sources to compile.
- A tag speaker covers one line only.
- The `emit` functions are numbered by argument count because ink has no overloads.
- A bare requirement value can't contain a comma. A quoted one can.
- Validation of `requires` tags only sees tags written literally in the source.
- There's no reusable UI in the library. That's on purpose.
