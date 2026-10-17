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
```

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
}
```

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
    var story: Story,
    stateProvider: NarrativeStateProvider,
    variableNamer: (entityId: String, statName: String) -> String = ::defaultVariableName,
)
```

`story` is a plain `var`, so you can swap in a freshly compiled one (after a reload, say). Not thread-safe. Keep it on one thread, like the render thread inside `KtxAsync.launch`.

`stateProvider` and `variableNamer` are private.

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
- Nothing reads ink variables back into the game yet. No bindings for external functions or variable observers either.
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
| `set <name> <value>` | Sets an ink variable. The value is tried as an int, then a float, then `true`/`false` (any case), and falls back to a string. So `set x 5` gives an int, and there's no way to set the string "5". |
| `stat <entity> <stat> <value>` | Sets a stat on the test state provider and syncs it to ink. Value must be a number. Whole numbers go into ink as ints, so they print without `.0`. |
| `hp <value>` | Shortcut for `stat player hp <value>`. |
| `choose <n>` | Picks a choice by index, starting at 0. |
| `divert <path>` | Jumps to an ink path (`knot` or `knot.stitch`) and continues from there. Bad paths log an error. |
| `reload` | Recompiles `main.ink` plus any mod `.ink` files in `assets/mods/`, swaps in the new story and clears the log. Story state starts fresh. |
| `restart` | Resets the current story's state and clears the log. Doesn't recompile. |
| `help` | Lists the commands. Comes from the console library. |
