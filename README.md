# Structural typing for Kotlin

A proof of concept that brings **structural typing** to Kotlin with a K2 compiler plugin: any class whose properties and
functions match an interface can be used as that interface, without declaring it.

## Getting started

**1. Apply the Gradle plugin.** It adds the `@Structural` annotation and the compiler plugin to the module:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()       // the plugin is on Maven Central, not the Gradle Plugin Portal
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.20"
    id("com.obabichev.structural") version "0.3.0-kotlin-2.4"
}
```

Kotlin **2.4.x** is required: the compiler plugin uses internal compiler APIs, which is why the version names the
Kotlin line it works with. On other versions the build fails with a clear message. The plugin is compiled against every
version of that line it supports, so one artifact covers 2.4.0, 2.4.10 and 2.4.20. Release `0.2.0` works on 2.4.20
only, whatever its notes say; see [`CHANGELOG.md`](CHANGELOG.md).

**2. Mark an interface and use any matching class:**

```kotlin
import com.obabichev.structural.Structural
import kotlin.math.PI

@Structural
interface Sized {
    val width: Int
    val height: Int
    fun area(): Int
}

// Neither of these knows about Sized

class Circle(val radius: Int) {
    val width: Int get() = radius * 2
    val height: Int get() = radius * 2
    fun area(): Int = (PI * radius * radius).toInt()
}

enum class Paper(val width: Int, val height: Int) {
    A4(210, 297);

    fun area(): Int = width * height
}

fun describe(target: Sized) = "${target.width}x${target.height}=${target.area()}"

fun main() {
    println(describe(Circle(3)))                            // 6x6=28
    println(Circle(3) is Sized)                             // true

    val shapes: List<Sized> = listOf(Circle(3), Paper.A4)
    println(shapes.sumOf { it.area() })                     // 62398
}
```

Nothing is written on the classes: no annotations, no wrappers, no generated code. Member types have to be declared
explicitly, and the classes matching an interface must be compiled in the same module as each other. The interface
itself can live in another module, as long as that module is also built with the plugin: it publishes the interfaces it
declares, and modules depending on it match their own classes against them.

**3. For IntelliJ,** install the IDE plugin, otherwise the editor reports `Argument type mismatch` errors that the build
doesn't have: download the zip from the
[latest release](https://github.com/obabichev/kotlin-structural-typing/releases/latest), install it through
*Settings → Plugins → ⚙ → Install Plugin from Disk…* and reload the Gradle project. IntelliJ 2026.2 (`262.*`) only; on
other versions, turn off the registry key `kotlin.k2.only.bundled.compiler.plugins.enabled` instead
(*Help → Find Action → Registry…*).

## How it works

The plugin makes a matching class **really implement** the interface, as if you had written
`class Rectangular(…) : Sized`. During compilation it:

1. adds the interface as a supertype of every class in the module that matches it,
2. marks the matching properties and functions `override`,
3. explains classes that nearly match, so a near miss isn't silent:

```
e: 'Panel' does not implement @Structural interface 'com.example.Sized':
       height: is internal, must be public
```

The compiler does the rest: override checks, bridge methods, bytecode, incremental compilation. Because the class really
implements the interface, identity is preserved (`===`), `is` checks work, and `List<Rectangular>` is a `List<Sized>`.

A class matches when, for every abstract member of the interface and its superinterfaces, it has a public member that
Kotlin would accept as an override: a `val` of a subtype, a `var` of exactly the same type, or a function with the same
signature. [`proposal.md`](proposal.md) has the exact rules.

## Classes from libraries (unreleased)

A class in a dependency can match too, including one from a library that has never heard of this plugin. The compiler is
given the match as it reads the class file, and `structural-runtime` puts the interface on the class as it loads:

```kotlin
import com.example.shapes.Dated      // @Structural, declared in another module of this build
import kotlinx.datetime.LocalDate    // an ordinary library: year, monthNumber, dayOfMonth

fun releaseIso(): String = iso(LocalDate(2026, 10, 2))   // compiles: LocalDate matches Dated
```

Both halves are needed: the call compiles to a cast, and the cast holds only when the class was loaded through
`StructuralClassLoader`. `sample/src/test/kotlin/com/example/ExternalLibraryTest.kt` shows both, including the
`ClassCastException` on an ordinary classloader.

Limits today: the interface has to come from a dependency as well, the dependency class must be written in Kotlin (a
Java class carries no Kotlin metadata for the matcher to read), and the editor does not know about any of it yet.

## Repository layout

| Module | Contents |
|---|---|
| `structural-annotations` | The `@Structural` annotation |
| `structural-compiler-plugin` | The K2 compiler plugin, built against every supported Kotlin version |
| `structural-compiler-plugin-tests` | Its tests: they compile snippets with the plugin and run the result |
| `structural-gradle-plugin` | Gradle plugin that applies both to a module |
| `structural-intellij-plugin` | IntelliJ plugin so the IDE analyzes code the same way as the build, and the tests that check it does |
| `sample` | A Gradle module using the plugin; its tests show every supported case |
| `sample-library` | A module publishing `@Structural` interfaces that `sample` matches from its own classes and from a library |
| `sample-dependency` | A module built without the plugin, standing in for a library whose class files can't be changed |
| `structural-runtime` | Adds interfaces to classes from dependencies as they load, by rewriting them |

| Document | Contents |
|---|---|
| [`CHANGELOG.md`](CHANGELOG.md) | What changed in each version |
| [`proposal.md`](proposal.md) | Design, matching rules, testing, project history |
| [`docs/known-issues.md`](docs/known-issues.md) | Current limitations, with what has been verified |
| [`docs/roadmap.md`](docs/roadmap.md) | Planned work, starting with generics |
| [`docs/publishing.md`](docs/publishing.md) | How the artifacts are published |
| [`docs/multiple-kotlin-versions.md`](docs/multiple-kotlin-versions.md) | How one artifact supports every Kotlin version in the line |

## Working on this repository

Gradle needs JDK 17:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew build
```

That compiles the plugin against each supported Kotlin version and runs every test, including the ones that analyze
code the way IntelliJ does:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-intellij-plugin:test \
    "-Pstructural.ideaPath=/path/to/IntelliJ IDEA.app"
```

The sample's tests are the best place to see what works: one file per feature under
`sample/src/test/kotlin/com/example/`. Modules inside this repository wire the plugin up directly instead of applying
the published one, as `sample/build.gradle.kts` shows:

```kotlin
dependencies {
    implementation(project(":structural-annotations"))
    kotlinCompilerPluginClasspath(project(":structural-compiler-plugin"))
}
```

To build the IntelliJ plugin zip yourself:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-intellij-plugin:buildPlugin \
    "-Pstructural.ideaPath=/Applications/IntelliJ IDEA.app"
```

Without `-Pstructural.ideaPath`, Gradle downloads IntelliJ IDEA 2026.2 to build against.

## License

[Apache License 2.0](LICENSE).

## Status

A proof of concept: 100 tests cover the supported cases, and the whole build passes without compiler warnings. It is
published for trying out, not for production use. The main limitations:

- generic interfaces and generic members are ignored ([roadmap](docs/roadmap.md))
- an interface can come from another module compiled with the plugin, but the classes matching it must be compiled
  together; other modules can then use those classes through the interface
- member types must be explicit
- the plugin uses internal, experimental compiler APIs, so expect breakage on Kotlin updates
- building it needs an EAP release of the Kotlin compiler plugin DevKit ([`docs/known-issues.md`](docs/known-issues.md))

The first version of this proof of concept generated overloads and adapters with KSP. KSP can't see call sites or change
existing classes, so it produced a lot of code and still couldn't support `is` checks, identity or collections; the
compiler plugin replaced it. The git history keeps both.
