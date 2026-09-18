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
3. **Checkers** (`FirAdditionalCheckersExtension`): a warning for classes that would match except that some member
   types are inferred (see "Inferred member types").

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

Requires Kotlin 2.4.20 with the K2 compiler. A published version would ship a Gradle plugin instead of the classpath
dependency.

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
| `structural-compiler-plugin` | The K2 compiler plugin, registered through `META-INF/services` |
| `structural-intellij-plugin` | IntelliJ plugin (ID `com.obabichev.structural.ide`, IntelliJ 2026.2) that makes the IDE run the compiler plugin |
| `sample` | A Gradle module using the plugin; its tests are the end-to-end check |

Plugin files:

1. **StructuralPluginRegistrar**: registers the FIR extensions and diagnostics.
2. **StructuralInterfaces**: finds usable `@Structural` interfaces, collects their required members and a class's
   members, and decides whether a member satisfies a requirement.
3. **StructuralTypes**: how types are resolved and compared in each compiler phase (see "Types during supertype
   resolution").
4. **StructuralSupertypeGenerator**: adds matched interfaces as supertypes.
5. **StructuralOverrideMarker**: marks implementing properties and functions `override`.
6. **StructuralCheckers** / **StructuralDiagnostics**: the inferred-member-types warning.

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

### Inferred member types

If a class would match with its resolved types but some matching members have inferred types, it doesn't implement the
interface and the plugin reports a warning:

```
w: 'Square' matches @Structural interface 'com.example.Sized' but doesn't implement it, because these members have
   inferred types: width. Declare their types explicitly.
```

No warning is reported if the class wouldn't match even with its resolved types.

## Testing

- **Plugin tests** (`structural-compiler-plugin`) compile snippets with the plugin using kotlin-compile-testing
  (`dev.zacsweers.kctfork:core`) and run the result. One file per feature, each starting with a short description:
  - `BasicUsageTest`: what implementing by shape gives (identity, `is` checks, collections, any function shape)
  - `PropertiesTest`: property requirements
  - `FunctionsTest`: function requirements
  - `SuperinterfacesTest`: members of Kotlin and Java superinterfaces
  - `InheritanceTest`: superclasses, subclasses, classes declaring the interface themselves
  - `EnumClassesTest`: enum classes
  - `TypeResolutionTest`: types resolved in the right file, file order, compiler state, nested types
  - `UnsupportedInterfacesTest`: interfaces that are never added
  - `InferredMemberTypesTest`: the warning
- **`sample`**: a real Gradle build using the plugin through `kotlinCompilerPluginClasspath`, with one test file per
  user-facing feature (`BasicUsageTest`, `CallSitesTest`, `PropertiesTest`, `InheritanceTest`, `EnumClassesTest`,
  `FunctionsAndSuperinterfacesTest`).
- **`structural-intellij-plugin`**: unit tests for recognizing the compiler plugin jar, and `verifyPluginStructure`.
  Whether the IDE actually loads the plugin is checked manually in IntelliJ.

## Success criteria for the PoC

1. The example from "Goal" compiles and runs with nothing but `@Structural` on the interface and the plugin applied.
2. Matching classes can be used everywhere their interface is expected, including collections, generics and `is` checks.
3. Classes that don't match, and unsupported interfaces, compile exactly as without the plugin.
4. The `sample` module builds with Gradle and its tests pass without compiler warnings.

## Future work

Planned work is tracked in [`docs/roadmap.md`](docs/roadmap.md); current limitations are in
[`docs/known-issues.md`](docs/known-issues.md).
