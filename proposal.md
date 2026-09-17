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
   of the module. If the class's properties match, the interface is added as a supertype.
2. **Status** (`FirStatusTransformerExtension`): properties that implement an added interface are marked `override`.
3. **Checkers** (`FirAdditionalCheckersExtension`): a warning for classes that would match except that some property
   types are inferred (see "Inferred property types").

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

## Modules

| Module | Contents |
|---|---|
| `structural-annotations` | `@Structural` (`@Target(CLASS)`, `@Retention(BINARY)`) |
| `structural-compiler-plugin` | The K2 compiler plugin, registered through `META-INF/services` |
| `sample` | A Gradle module using the plugin; its tests are the end-to-end check |

Plugin files:

1. **StructuralPluginRegistrar**: registers the FIR extensions and diagnostics.
2. **StructuralInterfaces**: finds usable `@Structural` interfaces and decides whether properties match.
3. **StructuralSupertypeGenerator**: adds matched interfaces as supertypes.
4. **StructuralOverrideMarker**: marks implementing properties `override`.
5. **StructuralCheckers** / **StructuralDiagnostics**: the inferred-property-types warning.

## Rules

### `@Structural` interfaces

The plugin uses a `@Structural` interface only if it is declared in the module being compiled and has:

- no type parameters
- no abstract functions
- no superinterfaces

Other `@Structural` interfaces are ignored. Adding them could leave a class with members it doesn't implement, which
would break its compilation.

**Required properties** are the interface's abstract properties. Properties with a default getter
(`val label: String get() = "shape"`) are not required.

### Candidate classes

Classes and objects (including nested ones) declared in the module being compiled. Not:

- enum classes (the compiler ignores supertypes a plugin adds to them)
- interfaces, annotation classes
- classes from dependencies (their bytecode can't be changed)

A class that already lists the interface in its source is left alone; if it forgets `override`, that is the normal
compiler error.

### Matching (Kotlin override rules)

A class matches an interface if, for every required property `p: T`, the class declares or inherits (from its superclass
chain, excluding generic superclasses) a property `p` that is public, and:

- `val p: T` required: a `val` or `var` of type `S` where `S` is a subtype of `T` (`Int` satisfies `Number`)
- `var p: T` required: a `var` of exactly type `T` with a public setter

Property types must be **declared explicitly**. Supertypes are decided before inferred types are known.

### Inferred property types

If a class would match with its resolved types but some required properties have inferred types, it doesn't implement
the interface and the plugin reports a warning:

```
w: 'Square' matches @Structural interface 'com.example.Sized' but doesn't implement it, because these properties
   have inferred types: width. Declare their types explicitly.
```

No warning is reported if the class wouldn't match even with its resolved types.

## Testing

- **Plugin tests** (`structural-compiler-plugin`) compile snippets with the plugin using kotlin-compile-testing
  (`dev.zacsweers.kctfork:core`) and run the result:
  - `StructuralTypingTest`: matching cases (functions of every shape, identity, `is` checks, collections, packages and
    declaration order, `Int` for `Number`, `var` write-through, subclasses, inherited properties, objects, default getters)
  - `NonMatchingTest`: cases that must not match or must keep normal errors (wrong types, missing or private properties,
    private setters, interfaces with functions, superinterfaces or type parameters, enums, interfaces from other modules,
    missing `override` on a declared supertype)
  - `InferredPropertyTypesTest`: the warning
- **`sample`**: a real Gradle build using the plugin through `kotlinCompilerPluginClasspath`, covering the same
  user-facing cases, including a matching class that is never used.

## Success criteria for the PoC

1. The example from "Goal" compiles and runs with nothing but `@Structural` on the interface and the plugin applied.
2. Matching classes can be used everywhere their interface is expected, including collections, generics and `is` checks.
3. Classes that don't match, and unsupported interfaces, compile exactly as without the plugin.
4. The `sample` module builds with Gradle and its tests pass without compiler warnings.

## Future work

See [`docs/known-issues.md`](docs/known-issues.md) for the details behind these.

- Interfaces from other modules: libraries compiled with the plugin could emit an index of their `@Structural`
  interfaces, which consumers list through the compiler's symbol names provider. Listing a dependency package at the
  supertype phase was verified in the spike.
- Superinterfaces of `@Structural` interfaces, generic interfaces, and matching functions, not only properties.
- Enum classes.
- A Gradle plugin, and checking IDE support (the K2 IDE plugin doesn't load third-party compiler plugins by default).
- Classes from dependencies can never gain supertypes; they would need generated adapters as in the KSP version.
