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

It opens a dialog tester for `assets/ink/main.ink`. Press the backtick key to open the console. Commands are `vars`, `get`, `set`, `stat`, `choose`, `divert`, `reload`, `restart` and `help`. Drop mod `.ink` files in `assets/mods/` and they get picked up on `reload`.
