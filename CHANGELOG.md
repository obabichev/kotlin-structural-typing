# Changelog

Versions name the Kotlin line they work with, because the compiler plugin uses internal compiler APIs.

## Unreleased

### Added

- A `@Structural` interface may extend a generic one: its arguments are substituted into the members inherited from it, so `Ranked : Comparable<String>` requires `compareTo(String)`, and `List<T>` becomes `List<String>`.

### Fixed

- Matching no longer depends on the order files are compiled in: a type written with arguments is resolved argument by argument, rather than only when the compiler happened to resolve that file first.
- An interface whose own supertypes were not yet resolved when first examined is examined again, instead of being refused for the rest of the compilation.

## 0.3.0-kotlin-2.4 — 2026-10-01

### Added

- A `@Structural` interface may be declared in another module: a module built with the plugin publishes its interfaces in `META-INF/structural/interfaces.txt`, and modules depending on it match their own classes against them.
- The Gradle plugin reads those indexes from the compile classpath and names what it finds to the compiler plugin, which is the only route that reaches IntelliJ.
- Near misses are explained instead of being silent: a rejected argument lists which requirements the class fails, such as `area(): returns 'Long', expected 'Int'`.
- A class that comes close to an interface is reported on its own declaration, when every required member is present and at least one requirement is met.
- One artifact now covers Kotlin 2.4.0, 2.4.10 and 2.4.20, built against each of them with the Kotlin compiler plugin DevKit.
- Tests that analyze code through the Kotlin plugin running in a test IDE, so bugs that only appear in the editor are caught by the build.
- Tests for the Gradle plugin, which had none: reading a dependency's index, and the coordinates it hands the compiler.

### Fixed

- A matching enum class no longer fails on Kotlin 2.4.0 and 2.4.10 with `NoClassDefFoundError: KtFakeSourceElementKind$PluginGenerated$Default`, which affected every project on those versions in 0.2.0.
- An interface from a sibling module is matched in IntelliJ too: the editor keeps each module's sources in its own session, so its member types had come out unknown and the editor reported an argument type mismatch while the build passed.
- The IntelliJ plugin recognizes the compiler plugin jar again after the DevKit changed how its entry point is named; without this the editor ran no plugin at all.

### Changed

- The compiler plugin's own tests moved to `structural-compiler-plugin-tests`, which compiles the plugin for the single compiler it tests against.
- Documentation describes how one artifact supports several Kotlin versions, and what the DevKit does not do: it builds against the compiler IntelliJ ships, which is not the same as testing IntelliJ.

## 0.2.0-kotlin-2.4 — 2026-09-19

### Changed

- The annotation moved from `dev.structural.Structural` to `com.obabichev.structural.Structural`, matching the artifact coordinates. **Breaking.**
- The version names the Kotlin line rather than one patch release, and the Gradle plugin refuses other lines with a clear message instead of failing inside the compiler.

### Fixed

- The Gradle plugin no longer ships stale coordinates: it generated them once and kept them, so a release could point at a SNAPSHOT.

### Known to be broken

- A matching enum class fails on Kotlin 2.4.0 and 2.4.10, although the release notes claim both work. Fixed in 0.3.0.

## 0.1.0-kotlin-2.4.20 — 2026-09-18

### Added

- Structural typing for Kotlin: a class whose properties and functions match a `@Structural` interface really implements it, so identity, `is` checks and collections all work, with nothing written on the class.
- Properties, functions, superinterfaces (Kotlin and Java), enum classes, objects, nested classes and inherited members take part in matching, under Kotlin's own override rules.
- A warning for classes that would match except that some member types are inferred, since supertypes are decided before inferred types are known.
- An IntelliJ plugin that hands the IDE a copy of the compiler plugin built against the IDE's own Kotlin compiler, so the editor agrees with the build without changing any IDE setting.
- A Gradle plugin, `com.obabichev.structural`, that adds the annotation and the compiler plugin to a module.
- Published to Maven Central under `com.obabichev.structural`, under the Apache 2.0 licence.
