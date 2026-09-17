# Structural typing for Kotlin

A proof of concept that brings **structural typing** to Kotlin with a K2 compiler plugin: any class whose properties and
functions match an interface can be used as that interface, without declaring it.

```kotlin
@Structural
interface Sized {
    val width: Int
    val height: Int
}

fun size(target: Sized) = target.width * target.height

// Doesn't know about Sized
class Rectangular(val width: Int, val height: Int, val color: String)

size(Rectangular(1, 2, "red"))                      // 2
Rectangular(1, 2, "red") is Sized                   // true
val shapes: List<Sized> = listOf(Rectangular(1, 2, "red"))
```

The only thing a user writes is `@Structural` on the interface. No annotations on classes or functions, no generated
code, no wrappers, and no required Gradle options.

## How it works

The plugin makes a matching class **really implement** the interface, as if you had written
`class Rectangular(…) : Sized`. During compilation it:

1. adds the interface as a supertype of every class in the module that matches it,
2. marks the matching properties and functions `override`,
3. warns about classes that would match except that some member types are inferred.

The compiler does the rest: override checks, bridge methods, bytecode, incremental compilation. Because the class really
implements the interface, identity is preserved (`===`), `is` checks work, and `List<Rectangular>` is a `List<Sized>`.

A class matches when, for every abstract member of the interface and its superinterfaces, it has a public member that
Kotlin would accept as an override: a `val` of a subtype, a `var` of exactly the same type, or a function with the same
signature. Member types have to be written explicitly, because supertypes are decided before inferred types are known.
[`proposal.md`](proposal.md) has the exact rules.

## Repository layout

| Module | Contents |
|---|---|
| `structural-annotations` | The `@Structural` annotation |
| `structural-compiler-plugin` | The K2 compiler plugin |
| `structural-intellij-plugin` | IntelliJ plugin so the IDE analyzes code the same way as the build |
| `sample` | A Gradle module using the plugin; its tests show every supported case |

| Document | Contents |
|---|---|
| [`proposal.md`](proposal.md) | Design, matching rules, testing, project history |
| [`docs/known-issues.md`](docs/known-issues.md) | Current limitations, with what has been verified |
| [`docs/roadmap.md`](docs/roadmap.md) | Planned work, starting with generics |

## Trying it

Requires JDK 17 for Gradle (Kotlin 2.4.20, K2).

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-compiler-plugin:test :sample:test
```

The sample's tests are the best place to see what works: one file per feature under
`sample/src/test/kotlin/com/example/`.

To use the plugin in a module of this project:

```kotlin
dependencies {
    implementation(project(":structural-annotations"))
    kotlinCompilerPluginClasspath(project(":structural-compiler-plugin"))
}
```

### IntelliJ

IntelliJ's K2 mode only runs compiler plugins bundled with the IDE, so without help the editor reports
`Argument type mismatch` errors that the build doesn't have. The IntelliJ plugin in this repository fixes that, with no
IDE settings to change:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-intellij-plugin:buildPlugin \
    "-Pstructural.ideaPath=/Applications/IntelliJ IDEA.app"
```

Then install `structural-intellij-plugin/build/distributions/structural-intellij-plugin.zip` through
*Settings → Plugins → ⚙ → Install Plugin from Disk…* and reload the Gradle project. Without `-Pstructural.ideaPath`,
Gradle downloads IntelliJ IDEA 2026.2 to build against. The plugin supports IntelliJ 2026.2 (`262.*`) only, because each
IDE version bundles a different Kotlin compiler.

## Status

A proof of concept: 62 tests cover the supported cases, and the whole build passes without compiler warnings. It is not
published anywhere and not ready for production. The main limitations:

- generic interfaces and generic members are ignored ([roadmap](docs/roadmap.md))
- interfaces and classes must be declared in the module being compiled
- member types must be explicit
- the plugin uses internal, experimental compiler APIs, so expect breakage on Kotlin updates

The first version of this proof of concept generated overloads and adapters with KSP. KSP can't see call sites or change
existing classes, so it produced a lot of code and still couldn't support `is` checks, identity or collections; the
compiler plugin replaced it. The git history keeps both.
