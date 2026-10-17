# hdgife

A grab-bag of extensions for [libKTX](https://libktx.github.io/) that I use in my own libGDX projects. It's still early, so expect things to move around.

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

hdgife declares its dependencies with `api`, so you get these transitively: KTX 1.14.2-rc1, libGDX 1.14.2, Fleks, blade-ink and sjInGameConsole. You don't need to add them yourself, and if you do, keep the versions in line with these.

## Testing locally

To try changes before pushing, publish to your local Maven repo:

```
./gradlew :core:publishToMavenLocal
```

In the consuming project, add `mavenLocal()` to your repositories and use version `0.1.0`.

## Test harness

The `lwjgl3` module is a small desktop app for poking at things. It isn't published. Run it with:

```
./gradlew lwjgl3:run
```

It opens a dialog tester for `assets/ink/main.ink`. Press the backtick key to open the console. Commands are `vars`, `get`, `set`, `stat`, `choose`, `divert`, `reload`, `restart` and `help`. You can drop mod `.ink` files in `assets/mods/` and they get picked up on `reload`.
