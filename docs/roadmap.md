# Roadmap

Planned work for the structural typing compiler plugin, roughly in priority order. Current behavior is described in
[`proposal.md`](../proposal.md), current limitations in [`known-issues.md`](known-issues.md).

## Language support

### Generic interfaces and members

Not supported yet: a `@Structural` interface with type parameters, a generic superinterface, or a generic required
member makes the plugin ignore the interface.

Examples to support:

```kotlin
@Structural interface Box<T> { val value: T }            // class IntBox(val value: Int) : Box<Int>?
@Structural interface Named : Comparable<Named> { … }    // generic superinterface
@Structural interface Mapper { fun <T> map(value: T): T } // generic function
```

Open questions:

- **Choosing type arguments:** `IntBox` could implement `Box<Int>`, `Box<Number>` or `Box<out Number>`. The most specific
  choice (`Box<Int>`) looks natural but has to be decided per variance.
- **Substitution:** members of generic superclasses and superinterfaces need their type parameters substituted before
  matching, instead of being skipped as today.
- **Generic functions:** matching needs type parameters with equivalent bounds, compared after renaming.
- **Subtyping of types with arguments during supertype resolution:** it currently requires equal types, and would need
  variance-aware comparison without the compiler's type checker.

### Types nested in classes and interfaces

During supertype resolution, types are resolved with file-level imports only, so a requirement like
`val kind: Shape.Kind` written as `Kind` inside `Shape` doesn't resolve and the class doesn't match. Add the scopes of
the containing classes.

### Explaining ignored interfaces

Report a warning on a `@Structural` interface that can never be used (type parameters, generic members, unresolved
superinterfaces), saying why.

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
