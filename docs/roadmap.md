# Roadmap

Planned work for the structural typing compiler plugin, roughly in priority order. Current behavior is described in
[`design.md`](design.md), current limitations in [`known-issues.md`](known-issues.md).

## Language support

### Generic interfaces and members

A `@Structural` interface may **extend** a generic interface, which is implemented: the arguments it gives are
substituted into the members inherited from it, including where a parameter sits inside another type.

```kotlin
@Structural interface Ranked : Comparable<String>        // works: asks for compareTo(String)
@Structural interface Texts : Holder<String>             // works: val items: List<T> asks for List<String>
@Structural interface Box<T> { val value: T }            // ignored: T would have to be guessed per class
@Structural interface Mapper { fun <T> map(value: T): T } // ignored: generic member
```

What is left:

- **A generic interface itself.** `IntBox(val value: Int)` could be `Box<Int>`, `Box<Number>` or `Box<out Number>`.
  Solving the arguments from the class's own members is the next step; the most specific choice (`Box<Int>`) is the only
  one that is derivable rather than arbitrary. Parameters a class doesn't pin down should keep the interface out.
- **Generic members:** matching needs type parameters with equivalent bounds, compared after renaming.
- **Variance:** types with arguments must currently be equal, so `Box<Int>` does not satisfy `Box<Number>`. Relaxing it
  needs variance-aware comparison written by hand, since the compiler's type checker can't be used while supertypes are
  being decided.

### Controlling accidental matches

Every matching class in the module implements the interface. Consider:

- a package scope option
- an opt-out annotation
- a warning when a class starts matching an interface

## Tooling

- **Gradle plugin:** `plugins { id("com.obabichev.structural") }` adds the annotations and the compiler plugin and checks the
  Kotlin version.
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
