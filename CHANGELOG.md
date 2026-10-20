# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-10-18

First public release. From here the public API in `core/api/core.api`, save format versions 1 and 2 and `hdgife.ink` version 2 are stable. Validation stays `@ExperimentalHdgifeApi`.

### Added

- `StoryManager` over a compiled Blade Ink story: `next()` and `choose()` return a `DialogStep` with lines, spans, markers, choices, tags, events and open styles.
- `InkCompilerService` with ordered `InkSource` lists, lazy sources, `INCLUDE` resolution, precompiled stories (`loadCompiled`) and libGDX helpers (`InkSource.of`, `compile(FileHandle)`, `compileFolder`).
- Stats read live from a `NarrativeStateProvider` (`stat`, `stat_of`, `has_stat`, `has_stat_of`, `stat_text`, `stat_text_of`), `syncStats`, and `NumberFormat` with `fmt` and four rounding modes.
- Speakers: persistent `speak()` kept in ink state, `# speaker:` line tags, and speaker timing where the last `speak()` before a line ends wins and a trailing one applies to the next lines.
- Choice requirements in `# requires:` tags with AND (comma), OR (`or`, `\|`) and NOT (`!`, `not`), reported as `RequirementResult` (`Met`, `Unmet`, `Mismatch`, `MissingStat`, `Unreadable`, `AnyOf`, `Not`).
- Style and point markers with spans, hex color reading, carry across lines and choices, `maxOpenStyles`, `closeStylesAtChoice` and a log for styles left unclosed.
- Events from `emit`, `emit1`, `emit2`, `emit3`: delivered exactly once with line index and a saved id, typed `StoryEvent` getters and `size`, and `onEvent` before choice requirements are read.
- Bound game functions (`bindFunction`) with lookahead-safe support, typed `FunctionArgs`, variable access, `observe` and `seedRandom`.
- Save and load in a versioned envelope that includes open styles, with optional `storyId` and `saveFingerprint` (envelope v2) to tie a save to its story.
- `StoryManager.close()` and `isClosed`.
- `hdgife.ink` (version 2) and `hdgife_util.ink` bundled in the jar, with `HdgifeInk.version` and `libraryFunctions`.
- `validateStory` and `StoryValidator` (experimental).
- A typed exception hierarchy under `HdgifeException`.
- Test harness on desktop, Android and web (TeaVM). The story is a feature showcase with a typewriter renderer and per-glyph effects. The desktop one has a 24-scenario UI test.
- Apache-2.0 license, `NOTICE`, `THIRD_PARTY_NOTICES.md`, pom metadata, a binary compatibility dump and a CI workflow.

### Changed

- Blade Ink is pinned strictly to 1.3.2, in the Gradle metadata and as an exact range in the pom, because the engine patches package-private internals.
- If `onEvent` throws, the remaining events are kept and delivered on the next `next()`.
- `speak()` also reports when it ran, so a `speak()` after a line no longer changes that line's speaker.
- `Comparison` is stable API.
- Constants and enum entries follow Kotlin naming: `Hdgife.version`, `HdgifeInk.version`, `HdgifeInk.libraryFunctions`, `Tags.empty`, and PascalCase entries for `Rounding`, `Comparison`, `Severity` and `IssueKind`. `RequirementResult.Not.result` is `operand`.
- Core falls back to `Gdx.files.classpath` to read the bundled ink libraries where there is no class loader (web).

### Fixed

- Blade Ink's inverted lookahead-safe guard (`HdgifeStory`) and `FLOOR`, `CEILING` and `INT` on floats (`InkNativePatch`).
- Exactly-once events across glue lookahead.
- A failed `loadState` leaves the manager consistent: styles closed, pending events dropped, observers rebased.
- Observer changes are reported per Continue. Docs now say so.
- A tag after the closing `]` of a choice is dropped by Blade Ink, so `requires` must sit inside the brackets. Documented.
