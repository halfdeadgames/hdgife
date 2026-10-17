# HDGEIFE

HDG Interactive Fiction Engine. It's an interactive fiction library for [libKTX](https://github.com/libktx/ktx)/[libGDX](https://libgdx.com) games in Kotlin, built on [Blade Ink](https://github.com/bladecoder/blade-ink-java). It doesn't care what your UI looks like or whether you use an ECS.

The idea is that ink handles all the narrative. That means the branching story, and also the procedurally generated ad-lib flavor text, which you get out of ink variables, functions and alternatives. The Kotlin side is just a bridge to your game plus a runtime compiler.

It's early. The API will change.

## What I'm going for

Drop it into any libKTX project from JitPack, write the whole narrative and all the flavor text in ink, and hook it up to your own game state through one small interface. After release, players should be able to mod in their own dialog.

## What works right now

`InkCompilerService` compiles raw `.ink` at runtime. Base files load from internal FileHandles. You can also give it a mod folder (local or external) and it scans it recursively and includes every `.ink` file it finds into the same Story. Includes inside your base files work too, and base and mod content can divert into each other.

Compiling is a suspend function. It runs on `Dispatchers.IO` unless you pass in a different context, for example one from ktx-async. If the ink is broken you get an `InkCompileException` with ink's own error messages and line numbers.

`NarrativeStateProvider` is the only interface your game has to implement:

```kotlin
fun getStat(entityId: String, statName: String): Float
fun hasStat(entityId: String, statName: String): Boolean = true // optional
```

With `INCLUDE hdgife.ink` in your story, ink can read stats live: `stat("sanity")` for the current speaker, `stat_of("guard", "mood")` for anyone, `has_stat("luck")`. No `VAR` per stat needed. See "Stat access and clean output" below.

`StoryManager.syncStatsToInk(entityId, statNames)` pulls those stats and pushes them into ink variables named `entity_stat`, so `player_hp` for example. The naming rule can be swapped out. If a variable isn't declared in the ink it gets skipped and logged.

## API

Everything lives under `com.github.halfdeadgames.hdgife`.

### Hdgife

```kotlin
Hdgife.VERSION // const val String, currently "0.1.0"
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

Package `compiler`.

```kotlin
class InkCompilerService(private val context: CoroutineContext = Dispatchers.IO)

suspend fun compile(baseMain: FileHandle, modDirectory: FileHandle? = null): Story
```

`compile` switches to `context` with `withContext`, so call it from a coroutine, for example `KtxAsync.launch`. Pass a ktx-async `newAsyncContext(...)` as `context` if you'd rather not use `Dispatchers.IO`. The returned `Story` isn't thread-safe, so use it from one thread.

It reads `baseMain` as UTF-8. If you pass `modDirectory`, it walks it recursively, picks up every file with the `ink` extension, sorts them by path relative to that folder and appends an `INCLUDE` for each one to the end of the base source. Those includes are named `__mod__/<relative path>`. `INCLUDE`s inside your base files resolve relative to the folder `baseMain` is in. Mod files can `INCLUDE` things relative to the mod folder.

Only ink errors count as failures. Warnings and other messages are logged at info level.

Throws `InkCompileException` if ink reports any error, or if the compiler returns no story. A missing `baseMain` isn't wrapped. You get whatever libGDX throws from `readString`.

### InkCompileException

```kotlin
class InkCompileException(val errors: List<String>) : RuntimeException
```

`errors` is ink's own error messages, line numbers included. The exception message is `"Ink compilation failed:"` followed by those messages, one per line.

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
```

`stateProvider` is optional. Without one, `stat`/`stat_of` return 0, `has_stat` returns false and `syncStatsToInk` does nothing. Assigning `story` binds the stat functions to the new story (after a reload, say). Not thread-safe. Keep it on one thread, like the render thread inside `KtxAsync.launch`. `variableNamer` is private.

```kotlin
fun beginScene(knotPath: String, speakerId: String? = null)
fun next(): DialogStep
fun choose(index: Int): DialogStep
fun seedRandom(seed: Int): Boolean
```

`beginScene` sets `speaker` and diverts to the knot (`knot` or `knot.stitch`). `next` continues until a choice or the end. `choose` picks a choice and then does the same. `seedRandom` makes ink's RANDOM and shuffles repeatable. It needs `INCLUDE hdgife.ink` (returns false without it) and `resetState()` reseeds randomly, so call it again after that.

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

Everything about which stats exist is up to your game and your ink. Nothing is built into the engine. `hdgife.ink` is only included if you write the `INCLUDE`, so stories without it are untouched. The name is reserved and resolved from the classpath, so it works on desktop and Android. The EXTERNAL functions have ink fallbacks (0 / false), so a story using the library still runs without a `StoryManager`.

Whole stat values reach ink as ints (`7`), fractions as floats. Ink integer division truncates, so use `/ 2.0` or the helpers below when averaging.

Library functions: `stat(name)`, `stat_of(entity, name)`, `has_stat(name)`, `chance(pct)`, `clamp(x, lo, hi)`, `avg2(a, b)`, `avg3(a, b, c)`, `blend(a, wa, b, wb)`, `invert(x)` (100 - x), `scale100(x, lo, hi)`. Don't declare your own knots with these names.

```kotlin
fun syncStatsToInk(entityId: String, statNames: List<String>)
```

For each stat name it builds the ink variable name with `variableNamer`, calls `stateProvider.getStat(entityId, stat)` and sets the variable to that Float. If the story has no variable with that name (not declared with `VAR`), it skips it, logs at debug level, and doesn't call `getStat` for it.

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

KtxAsync.launch {
    val story = try {
        InkCompilerService().compile(
            Gdx.files.internal("ink/main.ink"),
            Gdx.files.local("mods/"), // optional
        )
    } catch (e: InkCompileException) {
        e.errors.forEach { println(it) }
        return@launch
    }
    val manager = StoryManager(story, MyState())
    manager.syncStatsToInk("player", listOf("hp"))
    val text = manager.story.continueMaximally()
}
```

## Not done yet

- Mods can add content but can't override existing base knots. Ink rejects duplicate knot names.
- Nothing reads ink variables back into the game yet, and there are no variable observers. The only EXTERNAL functions are the stat ones.
- No save/load of story state.
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
  api "com.github.halfdeadgames:hdgife:0.1.0"
}
```

Dependencies are declared with `api`, so you get these transitively: KTX 1.14.2-rc1, libGDX 1.14.2, Blade Ink 1.3.2 (runtime and compiler), Fleks and sjInGameConsole. You don't need to add them yourself. If you do, keep the versions in line with these.

## Testing locally

To try changes before pushing, publish to your local Maven repo:

```
./gradlew :core:publishToMavenLocal
```

In the consuming project, add `mavenLocal()` to your repositories and use version `0.1.0`.

## Test harness

The `lwjgl3` module is a small desktop app I use for poking at things. It isn't published. Run it with:

```
./gradlew lwjgl3:run
```

It opens a dialog tester for `assets/ink/main.ink`. The backtick key toggles the console. Commands:

| Command | What it does |
| --- | --- |
| `vars` | Lists every ink variable with its value. |
| `get <name>` | Prints one variable, or says it isn't declared. |
| `set <name> <value>` | Sets an ink variable, keeping its current type. `set player_hp 5` stays an int, `set player_name 5` gives the string "5". |
| `set <name> <value> <type>` | Same, with an explicit type: `int`, `float`, `bool` or `string`. If the value doesn't parse, nothing changes. |
| `stat <entity> <stat> <value>` | Sets a stat on the test state provider and syncs it to ink. Value must be a number. Whole numbers go into ink as ints, so they print without `.0`. |
| `hp <value>` | Shortcut for `stat player hp <value>`. |
| `npc <entity> <stat> <value>` | Sets a stat on the test provider. Ink reads it live through `stat()`, no sync. The `guard` NPC starts with sanity 80, tiredness 20, mood 60, strength 12. |
| `talk <knot> [speaker]` | `beginScene`: sets the speaker and jumps to the knot, for example `talk guard_chat guard`. |
| `seed <n>` | Seeds ink's random. Use `restart` first if you want the same result twice. |
| `choose <n>` | Picks a choice by index, starting at 0. |
| `divert <path>` | Jumps to an ink path (`knot` or `knot.stitch`) and continues from there. Bad paths log an error. |
| `reload` | Recompiles `main.ink` plus any mod `.ink` files in `assets/mods/`, swaps in the new story and clears the log. Story state starts fresh. |
| `restart` | Resets the current story's state and clears the log. Doesn't recompile. |
| `help` | Lists the commands. Comes from the console library. |
