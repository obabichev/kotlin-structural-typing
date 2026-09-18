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
    id("com.obabichev.structural") version "0.1.0-kotlin-2.4.20"
}
```

Kotlin **2.4.20** is required: the compiler plugin uses internal compiler APIs, which is why the version names the
Kotlin version it works with.

**2. Mark an interface and use any matching class:**

```kotlin
import dev.structural.Structural
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
explicitly, and the interface and its matching classes must be compiled in the same module.

**3. For IntelliJ,** install the IDE plugin, otherwise the editor reports `Argument type mismatch` errors that the build
doesn't have: download the zip from the
[latest release](https://github.com/obabichev/kotlin-structural-typing/releases/latest), install it through
*Settings → Plugins → ⚙ → Install Plugin from Disk…* and reload the Gradle project. IntelliJ 2026.2 (`262.*`) only.

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
signature. [`proposal.md`](proposal.md) has the exact rules.

## Repository layout

| Module | Contents |
|---|---|
| `structural-annotations` | The `@Structural` annotation |
| `structural-compiler-plugin` | The K2 compiler plugin |
| `structural-gradle-plugin` | Gradle plugin that applies both to a module |
| `structural-intellij-plugin` | IntelliJ plugin so the IDE analyzes code the same way as the build |
| `sample` | A Gradle module using the plugin; its tests show every supported case |

| Document | Contents |
|---|---|
| [`proposal.md`](proposal.md) | Design, matching rules, testing, project history |
| [`docs/known-issues.md`](docs/known-issues.md) | Current limitations, with what has been verified |
| [`docs/roadmap.md`](docs/roadmap.md) | Planned work, starting with generics |
| [`docs/publishing.md`](docs/publishing.md) | How the artifacts are published |

## Working on this repository

Gradle needs JDK 17:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-compiler-plugin:test :sample:test
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

A proof of concept: 64 tests cover the supported cases, and the whole build passes without compiler warnings. It is
published for trying out, not for production use. The main limitations:

- generic interfaces and generic members are ignored ([roadmap](docs/roadmap.md))
- interfaces and the classes matching them must be compiled in the same module; other modules can then use those
  classes through the interface
- member types must be explicit
- the plugin uses internal, experimental compiler APIs, so expect breakage on Kotlin updates

The first version of this proof of concept generated overloads and adapters with KSP. KSP can't see call sites or change
existing classes, so it produced a lot of code and still couldn't support `is` checks, identity or collections; the
compiler plugin replaced it. The git history keeps both.
