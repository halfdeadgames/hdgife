# HDGEIFE

HDG Interactive Fiction Engine. It's an interactive fiction library for [libKTX](https://github.com/libktx/ktx)/[libGDX](https://libgdx.com) games in Kotlin, built on [Blade Ink](https://github.com/bladecoder/blade-ink-java). It doesn't care what your UI looks like or whether you use an ECS.

The idea is that ink handles all the narrative. That means the branching story, and also the procedurally generated ad-lib flavor text, which you get out of ink variables, functions and alternatives. The Kotlin side is just a bridge to your game plus a runtime compiler.

It's early. The API will change.

## What I'm going for

Drop it into any libKTX project from JitPack, write the whole narrative and all the flavor text in ink, and hook it up to your own game state through one small interface. After release, players should be able to mod in their own dialog.

## What works right now

`InkCompilerService` compiles the ink sources you hand it, in the order you hand them. The game owns mod discovery and load order. Compiling is plain blocking, so you pick the thread. You can also load a precompiled story from JSON. Broken ink gives an `InkCompileException` with ink's own messages, which name the source and line.

`NarrativeStateProvider` is the only interface your game has to implement:

```kotlin
fun getStat(entityId: String, statName: String): Float
fun hasStat(entityId: String, statName: String): Boolean = true // optional
```

With `INCLUDE hdgife.ink` in your story, ink can read stats live: `stat("sanity")` for the current speaker, `stat_of("guard", "mood")` for anyone, `has_stat("luck")`. No `VAR` per stat needed. See "Stat access and clean output" below.

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
interface NarrativeStateProvider {
    fun getStat(entityId: String, statName: String): Float
    fun hasStat(entityId: String, statName: String): Boolean = true
}
```

`hasStat` backs ink's `has_stat`. When it returns false, `stat`/`stat_of` give 0.

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

`compileFolder` takes the `.ink` files of a folder, compares their paths as plain text, uses `main.ink` as root if present (else the first file) and includes the rest in that order. Order it with file names, like `000_base.ink` and `010_modA.ink`, or pass your own list to `compile`.

Precompiled: `story.toJson()` is the compiled story. Store it and later call `loadCompiled(json)`, which skips the compiler. That's the way to go on Android and web. The JSON is of the story, not its state.

`log` gets ink warnings. Errors throw `InkCompileException(errors, source)`. Unreadable sources and missing includes are named in `errors`.

### StoryManager

Package `story`.

```kotlin
class StoryManager(
    story: Story,
    var stateProvider: NarrativeStateProvider? = null,
    variableNamer: (entityId: String, statName: String) -> String = ::defaultVariableName,
)
var story: Story
var speaker: String?
var numberFormat: NumberFormat // default NumberFormat(decimals = 0, rounding = Rounding.ROUND)
var formatOverride: ((entityId: String, statName: String) -> NumberFormat?)? // null by default
var textCleaner: (String) -> String // default cleanLine
var log: HdgifeLog? // null by default
var seed: Int?
```

`stateProvider` is optional. Without one, `stat`/`stat_of` return 0, `has_stat` returns false and `syncStatsToInk` does nothing. Assigning `story` binds the stat functions to the new story (after a reload, say). Not thread-safe. Keep it on one thread. `variableNamer` is private.

```kotlin
fun beginScene(knotPath: String, speakerId: String? = null)
fun next(): DialogStep
fun choose(index: Int): DialogStep
fun seedRandom(seed: Int): Boolean
fun saveState(): String
fun loadState(json: String)
fun syncStatsToInk(entityId: String, statNames: List<String>): List<String>
```

`beginScene` sets `speaker` and diverts to the knot (`knot` or `knot.stitch`). `next` continues until a choice or the end. `choose` picks a choice and then does the same. `seedRandom` makes ink's RANDOM and shuffles repeatable. It needs `INCLUDE hdgife.ink` (returns false without it) and `resetState()` reseeds randomly, so call it again after that.

`saveState()` and `loadState(json)` wrap ink's own state JSON. With `INCLUDE hdgife.ink` the speaker lives in the ink variable `hdgife_speaker` and the seed is in the ink state (ink's `SEED_RANDOM` plus the position in the random sequence), so one string is all the game stores. After `loadState`, ink continues with the same random results. Load it only into the same compiled story. Without the library, `speaker` is a plain field that you keep yourself. Your own stat values aren't included. blade-ink's seed setter is package-private, so `seed = n` (same as `seedRandom`) goes through ink and needs the library. The `seed` getter only returns the last value set through this manager.

`textCleaner` is applied to every output line and choice text. The default collapses spaces and removes spaces before `, . ; : ! ?`. Replace it for other languages or styles.

```kotlin
data class DialogStep(val text: String, val tags: List<String>, val choices: List<DialogChoice>, val ended: Boolean)
data class DialogChoice(val index: Int, val text: String, val tags: List<String>)
```

`text` has repeated spaces collapsed and spaces before `, . ; : ! ?` removed. Each ink line is one paragraph, joined with `\n`. Empty lines are dropped. `tags` collects every tag seen in the step. `ended` means no choices and nothing left to continue.

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

Everything about which stats exist is up to your game and your ink. Nothing is built into the engine. `hdgife.ink` is only included if you write the `INCLUDE`, so stories without it are untouched. The name is reserved and resolved from the classpath, so it works on desktop and Android. The EXTERNAL functions have ink fallbacks (0 / false / plain ink rounding), so a story using the library still runs without a `StoryManager`.

`stat` and `stat_of` always give ink a float, whole or not, so ink math behaves the same for every value. The engine does not decide how numbers are shown. That is up to the game (see below) and the story. Printing a float directly (`{stat("strength")}`) uses ink's own float printing, so prefer the functions below for display. Ink integer division truncates, so use `/ 2.0` or the helpers when averaging.

Library functions: `stat(name)`, `stat_of(entity, name)`, `has_stat(name)`, `stat_text(name)`, `stat_text_of(entity, name)`, `fmt(x, decimals, mode)`, `floor_text(x)`, `round_text(x, decimals)`, `chance(pct)`, `clamp(x, lo, hi)`, `avg2(a, b)`, `avg3(a, b, c)`, `blend(a, wa, b, wb)`, `invert(x)` (100 - x), `scale100(x, lo, hi)`. Don't declare your own knots with these names.

### Number formatting

Package `story`. Text output is locale-independent: always `.` as the decimal separator, no grouping.

```kotlin
enum class Rounding { FLOOR, CEIL, ROUND, TRUNCATE }
data class NumberFormat(val decimals: Int = 0, val rounding: Rounding = Rounding.ROUND, val trimTrailingZeros: Boolean = false)
fun NumberFormat.format(value: Float): String
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

Without a `StoryManager`, ink fallbacks do the same rounding with ink math (`stat_text` is 0 decimals, round). Whole results are exact, but with decimals the last digit can be off because ink floats are binary, and decimals above 6 are not supported. The fallbacks print their text directly, so use them inline (`{fmt(...)}`) and not in `~ temp t = fmt(...)`.

```kotlin
fun syncStatsToInk(entityId: String, statNames: List<String>)
```

For each stat name it builds the ink variable name with `variableNamer`, calls `stateProvider.getStat(entityId, stat)` and sets the variable to that Float. If the story has no variable with that name (not declared with `VAR`), it skips it and doesn't call `getStat` for it. The skipped variable names are returned.

```kotlin
StoryManager.defaultVariableName(entityId: String, statName: String): String
```

Returns `entityId_statName` with anything outside `[A-Za-z0-9_]` replaced by `_`. So `("player", "hp")` gives `player_hp`.

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
    override fun getStat(entityId: String, statName: String): Float = 10f
}

val story = try {
    InkCompilerService().compile(
        InkSource.of(Gdx.files.internal("ink/main.ink")),
        modFiles.map { InkSource.of(it) }, // your own mod list and order
    )
} catch (e: InkCompileException) {
    e.errors.forEach { println(it) }
    return
}
val manager = StoryManager(story, MyState())
manager.syncStatsToInk("player", listOf("hp"))
val text = manager.next().text
val saved = manager.saveState() // later: manager.loadState(saved)
```

## Not done yet

- Mods can add content but can't override existing knots. Ink rejects duplicate knot names.
- Nothing reads ink variables back into the game yet, and there are no variable observers. The only EXTERNAL functions are the stat and number-format ones.
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

The jar contains only the engine and the generic `hdgife/hdgife.ink` helper library. Demo stories live in the test harness.

## Testing locally

To try changes before pushing, publish to your local Maven repo:

```
./gradlew :core:publishToMavenLocal
```

In the consuming project, add `mavenLocal()` to your repositories and use version `0.2.0`.

## Test harness

The `lwjgl3` module is a small desktop app I use for poking at things. It isn't published. It owns the KTX, scene2d and console dependencies, its own `lwjgl3/assets/` folder (UI skin and the demo ink `ink/main.ink` and `ink/guard.ink`) and its own load policy: `main.ink` plus any `.ink` files in `lwjgl3/assets/mods/`, sorted by path. Run it with:

```
./gradlew lwjgl3:run
```

It opens a dialog tester for `lwjgl3/assets/ink/main.ink`. The backtick key toggles the console. Commands:

| Command | What it does |
| --- | --- |
| `vars` | Lists every ink variable with its value. |
| `get <name>` | Prints one variable, or says it isn't declared. |
| `set <name> <value>` | Sets an ink variable, keeping its current type. `set player_hp 5` stays an int, `set player_name 5` gives the string "5". |
| `set <name> <value> <type>` | Same, with an explicit type: `int`, `float`, `bool` or `string`. If the value doesn't parse, nothing changes. |
| `stat <entity> <stat> <value>` | Sets a stat on the test state provider and syncs it to ink. Value must be a number. Whole numbers go into the ink variable as ints, so they print without `.0`. |
| `hp <value>` | Shortcut for `stat player hp <value>`. |
| `npc <entity> <stat> <value>` | Sets a stat on the test provider. Ink reads it live through `stat()`, no sync. The `guard` NPC starts with sanity 80, tiredness 20, mood 60, strength 12. |
| `talk <knot> [speaker]` | `beginScene`: sets the speaker and jumps to the knot, for example `talk guard_chat guard`. |
| `seed <n>` | Seeds ink's random. Use `restart` first if you want the same result twice. |
| `numfmt <decimals> <mode>` | Changes the default number format used by `stat_text`. Mode is `floor`, `ceil`, `round` or `trunc`, for example `numfmt 1 floor`. |
| `choose <n>` | Picks a choice by index, starting at 0. |
| `divert <path>` | Jumps to an ink path (`knot` or `knot.stitch`) and continues from there. Bad paths log an error. |
| `reload` | Recompiles `main.ink` plus any mod `.ink` files in `lwjgl3/assets/mods/`, swaps in the new story and clears the log. Story state starts fresh. |
| `restart` | Resets the current story's state and clears the log. Doesn't recompile. |
| `help` | Lists the commands. Comes from the console library. |
