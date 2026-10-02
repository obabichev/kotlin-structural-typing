# Roadmap

Planned work for the structural typing compiler plugin, roughly in priority order. Current behavior is described in
[`design.md`](design.md), current limitations in [`known-issues.md`](known-issues.md).

## Language support

The generics work is done -- generic interfaces, generic superinterfaces, generic members and variance all match, see
[`known-issues.md`](known-issues.md) for what each means. What is left in the language is narrower.

### Controlling accidental matches

Every matching class in the module implements the interface. Consider:

- a package scope option
- an opt-out annotation
- a warning when a class starts matching an interface

## Tooling

- **JetBrains Marketplace:** publish the IntelliJ plugin so the prompt from `.idea/externalDependencies.xml` installs it
  directly.
- **More IDE versions:** today the IDE plugin bundles a copy of the compiler plugin built for one IDE's compiler, which
  is why it supports 2026.2 only. With the DevKit publishing a variant per compiler version, it could hand the IDE the
  matching published variant instead, covering many IDE versions with one build. Check Android Studio too.
- **Dropping the IDE plugin:** the Kotlin team is working on having the IDE load supported third-party compiler plugins
  automatically. Once that ships, `structural-intellij-plugin` can go, and the registry key
  `kotlin.k2.only.bundled.compiler.plugins.enabled` stops being the fallback.
- **CI:** build against new Kotlin versions early; the plugin uses internal compiler APIs.
- **A DevKit that knows released Kotlin versions:** the pinned EAP build predates Kotlin 2.4.20, which is why
  `includeBetaAndRc=LATEST` is needed; see [`known-issues.md`](known-issues.md). Drop it when a newer DevKit ships.
- **Testing that the IDE loads the plugin:** the editor's analysis is tested, but the substitution that gets our
  compiler plugin in front of it is still checked by hand.
