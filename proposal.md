# Structural typing for Kotlin — compiler plugin proof of concept

## Goal

Kotlin only has nominal typing: a class satisfies an interface only if it declares it. This library lets any class that
has the right *shape* be used as an interface. The user writes only the interface and ordinary code:

```kotlin
@Structural
interface Sized {
    val width: Int
    val height: Int
}

fun size(target: Sized) = target.width * target.height
fun totalArea(items: List<Sized>) = items.sumOf { size(it) }

class Rectangular(val width: Int, val height: Int, val color: String)

size(Rectangular(1, 2, "red"))                          // 2
totalArea(listOf(Rectangular(1, 2, "red")))             // collections, generics, varargs, nullable values all work
Rectangular(1, 2, "red") is Sized                       // true
```

No generated code, no extra annotations on functions or classes, no wrappers.

## Approach

A **K2 compiler plugin** makes every matching class in the module **actually implement** the interface, as if the user had
written `class Rectangular(...) : Sized`. It hooks into three phases of the compiler frontend (FIR):

1. **Supertypes** (`FirSupertypeGenerationExtension`): for each class, the plugin checks every `@Structural` interface
   of the module. If the class's properties and functions match, the interface is added as a supertype.
2. **Status** (`FirStatusTransformerExtension`): properties and functions that implement an added interface are marked
   `override`, matched by full signature.
3. **Checkers** (`FirAdditionalCheckersExtension`): explains classes that nearly match, at the class and at the call
   sites that reject them (see "Near misses").

After that the compiler does everything else itself: override checks, bridge methods (e.g. for `Int` implementing
`Number`), bytecode (`class Rectangular implements Sized`), incremental compilation.

Because the class really implements the interface, identity is kept, `is` checks work, `List<Rectangular>` is a
`List<Sized>`, and functions of any shape (default values, generics, `vararg`, nullable parameters, lambdas) accept
matching classes.

**History.** The first proof of concept used KSP to generate overloads and adapter functions (`rect.asSized()`) with
proxy classes. KSP can't see call sites and can't change existing classes, so it had to generate code for every
function × matching class and still couldn't support identity, `is` checks or collections. It was replaced by this
plugin; see commits `44daaab` and `5eaaf04` in git history.

## Usage

```kotlin
// build.gradle.kts of the module that declares the interfaces and classes
dependencies {
    implementation(project(":structural-annotations"))
    kotlinCompilerPluginClasspath(project(":structural-compiler-plugin"))
}
```

Requires Kotlin 2.4.x with the K2 compiler; one published artifact covers 2.4.0, 2.4.10 and 2.4.20. The published
Gradle plugin applies both for a module and names the interfaces its dependencies publish.

**IDE.** IntelliJ's K2 mode only runs compiler plugins bundled with the IDE, so without help the editor shows
`Argument type mismatch` errors that the build doesn't have. The `Structural Typing` IntelliJ plugin
(`structural-intellij-plugin`) fixes this without any IDE settings: it registers the Kotlin plugin's
`org.jetbrains.kotlin.bundledFirCompilerPluginProvider` extension point, recognizes the structural compiler plugin in a
project's build, and hands the IDE its own copy of it, compiled against the IDE's Kotlin compiler. The project declares
the IDE plugin in `.idea/externalDependencies.xml`, so IntelliJ suggests installing it when the project is opened.

## Modules

| Module | Contents |
|---|---|
| `structural-annotations` | `@Structural` (`@Target(CLASS)`, `@Retention(BINARY)`) |
| `structural-compiler-plugin` | The K2 compiler plugin, built against every supported Kotlin version (see [`docs/multiple-kotlin-versions.md`](docs/multiple-kotlin-versions.md)) |
| `structural-compiler-plugin-tests` | Its tests, which need the plugin and one compiler on a classpath |
| `structural-intellij-plugin` | IntelliJ plugin (ID `com.obabichev.structural.ide`, IntelliJ 2026.2) that makes the IDE run the compiler plugin |
| `sample` | A Gradle module using the plugin; its tests are the end-to-end check |
| `sample-library` | A module publishing a `@Structural` interface that `sample` matches from its own classes |

Plugin files:

1. **StructuralPluginRegistrar** / **StructuralCommandLineProcessor**: register the FIR extensions and diagnostics, and
   read the interfaces published by dependencies.
2. **StructuralInterfaces**: finds usable `@Structural` interfaces, collects their required members and a class's
   members, and decides whether a member satisfies a requirement.
3. **StructuralTypes**: how types are resolved and compared in each compiler phase (see "Types during supertype
   resolution").
4. **StructuralSupertypeGenerator**: adds matched interfaces as supertypes.
5. **StructuralOverrideMarker**: marks implementing properties and functions `override`.
6. **StructuralCheckers** / **StructuralDiagnostics** / **StructuralMismatch**: the near-miss diagnostics and the
   reasons they report.
7. **StructuralIndexFile** / **StructuralIndexWriter**: the index of interfaces a module publishes for other modules
   (see "Interfaces from other modules").

## Rules

### `@Structural` interfaces

The plugin uses a `@Structural` interface declared in the module being compiled. Its **required members** are the
abstract properties and functions of the interface and of all its superinterfaces (Kotlin or Java, annotated or not),
except members that a more derived interface implements (e.g. `override fun greet() = "hi $name"`). Members with a
default implementation are never required. A class that gets the interface also implements its superinterfaces.

The interface is ignored, and never added to any class, if:

- it or any superinterface has type parameters
- a required member has type parameters or an extension receiver
- a superinterface can't be resolved

Adding such an interface could leave a class with members it doesn't implement, which would break its compilation.
Generic interfaces are planned; see [`docs/roadmap.md`](docs/roadmap.md).

### Candidate classes

Classes, objects and enum classes (including nested ones) declared in the module being compiled. Not interfaces,
annotation classes or classes from dependencies (their bytecode can't be changed).

A class that already lists the interface in its source is left alone; if it forgets `override`, that is the normal
compiler error.

Enum classes need special handling: the compiler only writes computed supertypes back to classes that start with an
unresolved or implicit supertype, and an enum class starts with an already resolved `Enum<E>`. The plugin therefore adds
the interface to an enum class directly.

### Matching (Kotlin override rules)

A class's members are its own properties and functions plus those inherited from its superclass chain. A member
satisfies a requirement if it has the same name, is public, has no type parameters or extension receiver, and:

- **`val p: T`**: a `val` or `var` of a subtype of `T` (`Int` satisfies `Number`), not `const`
- **`var p: T`**: a `var` of exactly `T` with a public setter
- **`fun f(a: A, …): R`**:
  - same `suspend`
  - same parameter names, `vararg` and exact parameter types
  - no default values (an override can't declare them)
  - not `inline`
  - a return type that is `R` or a subtype

Types from generic superclasses that use type parameters (e.g. `compareTo(other: E)` from `Enum<E>`) never match;
their other members do (the built-in `name: String` of every enum class can satisfy `val name: String`).

Types must be **declared explicitly**: supertypes are decided before inferred types are known.

### Types during supertype resolution

Supertypes are decided in the compiler's supertype phase, so the plugin has to resolve and compare types itself:

- Each type is resolved with the imports of the file that declares it, the same way the compiler builds file scopes.
  `Color` in an interface and `Color` in a class compare as the classes they refer to.
- The compiler's type checker is never used in this phase. It would compute and cache supertypes of classes the
  compiler hasn't processed yet, and unrelated code (`val b: Base = Derived()`) would then fail to compile. Instead:
  - types are compared structurally
  - subtyping walks declared supertypes, each resolved in its own file, so the result doesn't depend on file order
  - types with arguments must be equal

After that phase (override marking, the warning), the compiler's resolved types and type checker are used.

### Near misses

Matching is all-or-nothing, so a class that misses by one detail is simply not the interface, and the code that uses it
fails with the compiler's own message, which names the two types and nothing else:

```
e: Argument type mismatch: actual type is 'Panel', but 'Sized' was expected.
```

The matcher knows why each member failed, so the plugin reports it. `mismatch` returns a `Mismatch` (a wrong type, a
non-public member, a parameter name, a `val` where the interface declares a `var`, …) instead of a boolean, and
`satisfies` is a wrapper over it, so nothing is allocated while a member matches.

**At a rejected argument** every unmet requirement is listed:

```
e: 'Panel' does not implement @Structural interface 'com.example.Sized':
       height: is internal, must be public
```

This is an error rather than a warning because the compiler prints no warnings at all once a compilation has an error,
which is exactly this situation. It is reported only where resolution already failed, so it can't fail a build that
would otherwise succeed. No threshold applies: the interface was named at that position, so the comparison is the one
the code asked for.

**On a class declaration** a warning is reported when the class is close enough to be worth it: every required name is
present and at least one requirement is met. Without that threshold every class in the module would be reported against
every interface. An interface with a single requirement is therefore never reported here, only at call sites.

```
w: 'Panel' almost implements @Structural interface 'com.example.Sized':
       height: is 'Long', expected 'Int'
```

**Inferred member types** are the case where the class matches with resolved types although it didn't gain the
interface, because supertypes are decided before inferred types are known:

```
w: 'Square' matches @Structural interface 'com.example.Sized' but doesn't implement it, because these members have
   inferred types: width. Declare their types explicitly.
```

### Interfaces from other modules

A `@Structural` interface may be declared in a dependency, so a multi-module project doesn't have to copy it into every
module with matching classes. What the compiler allows decides the design:

- an interface from a dependency **can** be loaded by class id, with its annotation and its members' types readable;
- the classifiers of a **known** package can be listed;
- but the packages on the classpath **cannot** be enumerated (`getPackageNames()` returns null).

So the names have to be written down at compile time. A module compiled with the plugin publishes them:

```
META-INF/structural/interfaces.txt
    com/example/shapes/Sized
```

The file is written from IR, where a module is finished and its declarations are available in one place exactly once,
into the module's output directory, which Gradle packs into the jar.

A module compiling against it learns the names in one of two ways:

1. the Gradle plugin reads the indexes on the compile classpath and passes each interface as a compiler plugin option;
2. failing that, the compiler plugin reads them off the classpath itself (plain `kotlinc`, and the plugin's own tests).

Both work for a build. Only the first reaches IntelliJ, which passes a module's compiler plugin options from the Gradle
import but hands plugins no classpath to read; a newly published interface therefore needs a re-import before the editor
sees it. Interfaces declared in the module being compiled win over a dependency publishing the same one.

This does not change the other direction: a class already compiled can never gain a supertype, so matching classes still
have to be compiled together with each other.

## Testing

- **Plugin tests** (`structural-compiler-plugin-tests`) compile snippets with the plugin using kotlin-compile-testing
  (`dev.zacsweers.kctfork:core`) and run the result. One file per feature, each starting with a short description:
  - `BasicUsageTest`: what implementing by shape gives (identity, `is` checks, collections, any function shape)
  - `PropertiesTest`: property requirements
  - `FunctionsTest`: function requirements
  - `SuperinterfacesTest`: members of Kotlin and Java superinterfaces
  - `InheritanceTest`: superclasses, subclasses, classes declaring the interface themselves
  - `EnumClassesTest`: enum classes
  - `TypeResolutionTest`: types resolved in the right file, file order, compiler state, nested types
  - `UnsupportedInterfacesTest`: interfaces that are never added
  - `OtherModulesTest`: publishing the index, and what crosses a module boundary in each direction
  - `InferredMemberTypesTest`: the inferred-types warning
  - `NearMissTest`: what a class that nearly matches is told, at the call site and on the class
- **`sample`**: a real Gradle build using the plugin through `kotlinCompilerPluginClasspath`, with one test file per
  user-facing feature (`BasicUsageTest`, `CallSitesTest`, `PropertiesTest`, `InheritanceTest`, `EnumClassesTest`,
  `FunctionsAndSuperinterfacesTest`, `OtherModulesTest` against `:sample-library`).
- **`structural-intellij-plugin`**: unit tests for recognizing the compiler plugin jar, `verifyPluginStructure`, and
  `StructuralHighlightingTest` / `StructuralCrossModuleHighlightingTest`, which analyze code through the Kotlin plugin
  running in a test IDE and assert the errors the editor would show. The editor resolves declarations on demand rather
  than phase by phase, and every bug this plugin had in the editor was invisible to the tests above. Whether a running
  IDE loads the plugin at all is still checked by hand.
- **`structural-gradle-plugin`**: reading the index a dependency publishes, and the coordinates it hands the compiler.

## Success criteria for the PoC

1. The example from "Goal" compiles and runs with nothing but `@Structural` on the interface and the plugin applied.
2. Matching classes can be used everywhere their interface is expected, including collections, generics and `is` checks.
3. Classes that don't match, and unsupported interfaces, compile exactly as without the plugin.
4. The `sample` module builds with Gradle and its tests pass without compiler warnings.

## Future work

Planned work is tracked in [`docs/roadmap.md`](docs/roadmap.md); current limitations are in
[`docs/known-issues.md`](docs/known-issues.md).
