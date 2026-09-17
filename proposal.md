# Structural typing for Kotlin — KSP proof of concept

## Goal

Kotlin only has nominal typing: a class satisfies an interface only if it declares it. This library lets a
function accept any class that has the right *shape*:

```kotlin
@Structural
interface Sized {
    val width: Int
    val height: Int
}

fun size(target: Sized) = target.width * target.height

class Rectangular(val width: Int, val height: Int, val color: String)

size(Rectangular(1, 2, "red")) // compiles and returns 2
```

## Approach

A KSP processor generates, **before the user's code is type-checked**, an overload of every function that takes a
`@Structural` parameter, for every class in the configured packages that matches the interface. The overload wraps
the argument in a generated proxy and calls the original function:

```kotlin
// generated: Size_Structural.kt (same package as `size`)
fun size(target: Rectangular): Int = size(Rectangular_AsSized(target))

// generated: Rectangular_AsSized.kt (same package as `Rectangular`)
internal class Rectangular_AsSized(val target: Rectangular) : Sized {
    override val width: Int get() = target.width
    override val height: Int get() = target.height
    // equals / hashCode / toString, see "Proxy"
}
```

The call `size(Rectangular(1, 2, "red"))` then resolves to the generated overload through normal Kotlin overload
resolution. No compiler plugin, no changes to existing code, and the generated code is plain Kotlin you can read.

We chose this over a K2 compiler plugin because KSP has a stable API, resolved type information, and works in the IDE
(generated sources are indexed after a build). A compiler plugin is harder to write, breaks between Kotlin versions,
and is not loaded by the K2 IDE plugin by default. See "Future work".

## Usage

```kotlin
// build.gradle.kts
plugins { id("com.google.devtools.ksp") }

dependencies {
    implementation("…:structural-annotations")
    ksp("…:structural-processor")
}

ksp {
    arg("structural.packages", "com.example.model,com.example.dto")
}
```

- `structural.packages` (required): comma-separated package names. Each includes its subpackages. Only classes
  declared in these packages **in the current module's sources** are candidates. If the option is missing, the
  processor reports an error.
- Functions that take `@Structural` parameters are looked for in **all** sources of the current module.
- `@Structural` interfaces may be declared in this module or come from a dependency (the annotation has
  `BINARY` retention so KSP can see it on library classes).

## Modules

| Module | Contents |
|---|---|
| `structural-annotations` | `@Structural` (`@Target(CLASS)`, `@Retention(BINARY)`) |
| `structural-processor` | `SymbolProcessorProvider` + processor; generates code with KotlinPoet (`kotlinpoet-ksp`) |
| `sample` | Consumer module applying the processor; its tests are the end-to-end check |

Processor components, each testable on its own:

1. **Config**: parses KSP options.
2. **InterfaceModel**: validates a `@Structural` interface and lists the properties a class must have.
3. **Matcher**: decides whether a candidate class satisfies an interface.
4. **FunctionCollector**: finds supported functions with a `@Structural` parameter and groups overloads.
5. **OverloadPlanner**: decides which overloads to generate (including the ambiguity rules below).
6. **Generator**: writes proxies and overloads with KotlinPoet.

## Rules

### `@Structural` interfaces

- Must be an interface without type parameters.
- Its **abstract** members, including those inherited from superinterfaces, must be `val`/`var` properties.
  Abstract functions → processor error on the interface. Members with a default implementation are allowed and
  are simply inherited by the proxy.
- Required members = all abstract properties, including inherited ones.

### Candidate classes

Any class, interface, object or enum declared in a scanned package (including nested ones), except:

- classes that are not `public` or `internal`, including ones nested in a private class
- annotation classes, companion objects, `inner` classes, and classes with type parameters

Classes that already implement the interface nominally still go through the ambiguity rules below. Usually they
need nothing, because the original function already accepts them.

### Matching (Kotlin override rules)

A candidate matches an interface if, for every required property `p: T`, the candidate has a member property
(declared or inherited, not an extension) named `p`, visible from generated code (`public` or `internal`), and:

- `val p: T` is required: the candidate has `val` or `var` of type `S`, where `S` is assignable to `T`
  (e.g. `Int` satisfies `Number`, `String` satisfies `String?`).
- `var p: T` is required: the candidate has `var` of exactly type `T` (including nullability) with a visible
  setter.

### Supported functions

A function is processed if it has **exactly one** parameter whose type is a `@Structural` interface (non-null) and it
is one of:

- a top-level function (optionally an extension function, e.g. `fun Canvas.draw(shape: Sized)`)
- a member function of a class, interface or object

and is `public` or `internal`, and `suspend` or regular.

### Unsupported functions

A function that uses a `@Structural` interface in its signature but doesn't meet the rules above gets **no
overloads**. The processor reports a **warning** (not an error, so the build still passes) of the form:

```
w: [structural] Skipping com.example.size(target: Sized, other: Sized): more than one @Structural parameter
```

Calls to such a function with a non-nominal argument fail with the normal Kotlin type-mismatch error.

| Case | Example | Why it is unsupported |
|---|---|---|
| Several structural parameters | `fun fit(a: Sized, b: Sized)` | Overload count grows as the product of candidates; left for later |
| Nullable structural parameter | `fun size(target: Sized?)` | `size(null)` would match both `Sized?` and `Rectangular?`, so it would become ambiguous |
| Structural type as extension receiver | `fun Sized.area()` | Needs receiver-based generation; left for later |
| Structural type nested in another type | `fun total(items: List<Sized>)`, `fun f(g: (Sized) -> Unit)` | A proxy can't be passed in place of a collection or lambda |
| Other parameters with default values | `fun size(target: Sized, scale: Int = 1)` | KSP can't read default-value expressions, so the overload couldn't copy them |
| `vararg` parameters | `fun sizes(vararg targets: Sized)` | Arrays can't be wrapped in place |
| Type parameters | `fun <T> size(target: Sized, tag: T)` | Copying type parameters and bounds is left for later |
| `inline` functions | `inline fun size(target: Sized)` | Also covers `reified`; left for later |
| `private` / `protected` functions | `private fun size(target: Sized)` | The generated overload, in another file or outside the class, can't call it |
| `expect` / `actual` functions | `expect fun size(target: Sized)` | The PoC is JVM only |
| Companion-object members | `companion object { fun size(target: Sized) }` | Extension overloads on `Owner.Companion` are left for later |
| Members of generic or `inner` classes | `class Box<T> { fun put(target: Sized) }` | The extension overload would need the owner's type parameters |

Only parameter and receiver types are inspected. A function where a `@Structural` interface appears only in its
return type is not considered at all and gets no warning. Local functions are not visible to KSP and are silently
ignored.

### What gets generated

For a supported function `f` and a class `C` that gets an overload:

- **Top-level `f`**: a top-level overload in the same package, same visibility, `suspend` modifier, extension
  receiver, other parameters (same names and order) and return type. The structural parameter's type is replaced by
  `C`.
- **Member `f` of `Owner`**: an **extension** overload `fun Owner.f(…, target: C, …)` in `Owner`'s package.
  Kotlin uses an extension when the member with the same name doesn't accept the arguments.
  Limitation: callers in other packages must import it (`import com.example.f`), as with any extension.
- Visibility: `internal` if the function (or its owner) or `C` is `internal`, otherwise `public`.
- Body: the argument is first stored in a local variable typed as the interface, so the call can't pick the
  generated overload again. Other parameters are passed by name:
  ```kotlin
  public fun size(target: Rectangular): Int {
      val structuralArgument: Sized = Rectangular_AsSized(target)   // `= target` if C implements Sized nominally
      return size(target = structuralArgument)                      // `this.f(…)` for extension overloads
  }
  ```
  If a parameter is already called `structuralArgument`, `_` is appended until the name is unique.
- One generated file per original source file for top-level overloads (`<File>_Structural.kt`), one per owner
  class for extension overloads (`<Owner>_Structural.kt`). Both go in the function's (or owner's) package; if the two
  names coincide, the overloads share one file.
- An overload is **not** generated if the user already declared `f` with the same signature.

### Proxy

One proxy per (candidate, interface) pair, generated once and reused by all overloads:

- `internal class <C>_As<I>(val target: C) : I` in `C`'s package. Nested class names are joined with `_`. If two
  pairs would get the same name, a short stable hash of the interface's qualified name is appended. If the interface
  itself has a property named `target`, `_` is appended to the proxy's property name until it is unique.
- `val` properties delegate their getter; `var` properties delegate getter and setter. Reads are live, not a
  snapshot.
- `toString()` delegates to `target`. `equals` is true for another proxy of the same interface whose `target` is
  equal; `hashCode()` delegates to `target`. (A proxy is never equal to a raw target, which keeps `equals`
  symmetric.)

### Overload resolution safety (ambiguity rules)

Generated overloads must never make code that used to compile ambiguous, and must never conflict with each other.

**Overload group**: all user-declared functions named `f` in the same scope (same package for top-level functions,
same owner for members) with the same extension receiver and the same parameter types, except at the position of
the structural parameter. Their types at that position are the group's **declared types** (for example `Sized`,
`Named`, or a hand-written `Base`). Declared types that are not classes or interfaces (e.g. type parameters) are
ignored.

**Planning**: classes in the scanned packages are processed supertypes first. For each class `C`:

1. If `C` is itself a declared type, skip it (the user already wrote `f(C)`).
2. **Member functions only**: if any declared type is a supertype of `C`, skip it. Kotlin tries members before
   extensions, so a matching member is used (or already reports an error) and a generated extension would never be
   picked.
3. Collect the overloads that accept `C`: declared types that are supertypes of `C`, plus classes that already got a
   generated overload in this group and are supertypes of `C`. If one of them is a subtype of all the others, that
   overload is picked unambiguously, so skip `C`. This is the **base-class optimization**.
4. Otherwise, collect the group's `@Structural` interfaces that `C` implements nominally or matches structurally:
   - none: skip `C`
   - exactly one, `I`: generate `f(C)` calling the `I` version, directly if `C` implements `I` nominally,
     otherwise through the proxy
   - several: generate nothing and report a **warning**
     (`[structural] Not generating com.example.f for com.example.model.C: it matches several @Structural interfaces (…)`).
     Calling `f(C())` then fails with the normal compiler error.

Example the rules handle:

```kotlin
open class Base(val width: Int, val height: Int)  // matches → size(Base) generated
class Mid : Base(1, 2)                            // only size(Base) applies → skipped
class Child : Base(1, 2), Sized                   // size(Base) and size(Sized) apply, neither more specific
                                                  // → size(Child) generated, calls size(Sized) directly
```

Limitation: only classes in the scanned packages are checked. A class outside them that extends `Base` and
implements `Sized` nominally still gets an ambiguity error at its call sites.

## Processing

- Runs in one round. Generated code contains no `@Structural` interfaces or new candidates, so later rounds do
  nothing.
- Generated files are **aggregating** outputs that depend on all source files of the module. This is the simplest
  correct choice for the PoC; narrower dependencies are an optimization for later.
- Processor errors (invalid `@Structural` interface, missing option) fail the build. Unsupported functions and
  ambiguities are warnings.

## Testing

- **Processor tests** with kotlin-compile-testing for KSP2 (`dev.zacsweers.kctfork:ksp`): each rule above gets a
  test that compiles a snippet and checks the generated code, warnings, or errors, including a negative case
  where calling an unsupported/ambiguous function must fail to compile.
- **`sample` module**: real code covering the original example, a member function, an extension function, a
  `suspend` function, `var` write-through, `Int` for `Number`, the base-class optimization, and the
  `Child : Base, Sized` case. Its tests run the calls and check the results.

## Success criteria for the PoC

1. The example from "Goal" compiles and runs unchanged apart from the Gradle setup.
2. Member functions work via extension overloads.
3. Every case in the `sample` module compiles, runs and gives correct results.
4. Unsupported and ambiguous cases produce clear warnings rather than broken generated code.

## Out of scope / future work

- Methods in `@Structural` interfaces (match by name, parameter and return types).
- Several structural parameters in one function, nullable structural parameters, default values, generics.
- Scanning classes from dependencies (`Resolver.getDeclarationsFromPackage`, currently `@KspExperimental`) and Java
  getters (`getWidth()`).
- Cross-module use: a library declaring `size(Sized)` cannot generate overloads for classes of the app using it; the
  app would have to run the processor over the library's functions.
- Unwrapping proxies (`asOriginal()`), structural collections (`List<Rectangular>` as `List<Sized>`), lambdas.
- A K2 compiler plugin alternative that needs no scanning: generate a generic stub `fun <T : Any> size(target: T)` in
  the frontend, check `T`'s shape with a frontend checker, and replace calls with proxies in the IR backend. Works
  across modules and only for used call sites, but relies on the unstable plugin API and has no IDE support by default.
