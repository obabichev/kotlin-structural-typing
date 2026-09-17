# Structural typing for Kotlin — KSP proof of concept

## Goal

Kotlin only has nominal typing: a class satisfies an interface only if it declares it. This library lets code use
any class that has the right *shape* as an interface. The user writes only the interface and ordinary functions:

```kotlin
@Structural
interface Sized {
    val width: Int
    val height: Int
}

fun size(target: Sized) = target.width * target.height
fun totalArea(items: List<Sized>) = items.sumOf { size(it) }

class Rectangular(val width: Int, val height: Int, val color: String)

size(Rectangular(1, 2, "red"))                          // returns 2 through a generated overload
totalArea(listOf(Rectangular(1, 2, "red").asSized()))   // generated adapter, where no overload can exist
```

No extra annotations on functions or classes, and no required Gradle options.

## Approach

A KSP processor generates code **before the user's code is type-checked**. It has two layers:

1. **Overloads.** Every function that takes a `@Structural` interface gets an overload for every matching class,
   delegating through the adapter:

   ```kotlin
   // generated: <File>_Structural.kt (same file name and package as `size`)
   fun size(target: Rectangular): Int = size(target = target.asSized())
   ```

   The call `size(Rectangular(1, 2, "red"))` then resolves to the generated overload through normal Kotlin overload
   resolution.

2. **Adapters.** For every `@Structural` interface and every matching class, an extension function converts the class
   to the interface through a generated proxy:

   ```kotlin
   // generated: Sized_Adapters.kt (same package as `Sized`)
   fun Sized.asSized(): Sized = this                                  // identity adapter
   fun Rectangular.asSized(): Sized = Rectangular_AsSized(this)

   // generated: Rectangular_AsSized.kt (same package as `Rectangular`)
   internal class Rectangular_AsSized(val target: Rectangular) : Sized {
       override val width: Int get() = target.width
       override val height: Int get() = target.height
       // equals / hashCode / toString, see "Proxy"
   }
   ```

   Overload bodies use adapters, and callers use them directly where no overload can exist: `List<Sized>` parameters,
   lambdas, default values, generics.

No compiler plugin, no changes to existing code, and the generated code is plain Kotlin you can read. The cost is
generated code: overloads grow as functions × matching classes.

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

// optional: limit which classes can be passed structurally
ksp {
    arg("structural.packages", "com.example.model,com.example.dto")
}
```

- `structural.packages` (optional): comma-separated package names, each including its subpackages. When set, only
  classes declared in these packages are candidates; when missing or blank, every class in the module is. Either way,
  only classes **in the current module's sources** are considered.
- Adapters are generated for every `@Structural` interface declared in this module, and for `@Structural` interfaces
  from dependencies that appear in a receiver or parameter type (including type arguments, e.g. `List<Sized>`) of any
  function in this module. The annotation has `BINARY` retention so KSP can see it on library classes.
- Functions taking `@Structural` interfaces are looked for in **all** sources of the current module.

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
4. **Candidates**: finds candidate classes in the scanned packages.
5. **FunctionCollector**: records `@Structural` interfaces used by functions, finds supported functions and groups
   overloads.
6. **OverloadPlanner**: decides which adapters and overloads to generate (the ambiguity rules below).
7. **Generator**: writes adapters, proxies and overloads with KotlinPoet.

## Rules

### `@Structural` interfaces

- Must be an interface without type parameters.
- Its **abstract** members, including those inherited from superinterfaces, must be `val`/`var` properties.
  Abstract functions → processor error on the interface. Members with a default implementation are allowed and
  are simply inherited by the proxy.
- Required members = all abstract properties, including inherited ones.

### Candidate classes

Any class, interface, object or enum declared in the module (or in `structural.packages`, if set), including nested
ones, except:

- classes that are not `public` or `internal`, including ones nested in a private class
- annotation classes, companion objects, `inner` classes, and classes with type parameters

Classes that already implement the interface nominally still go through the ambiguity rules below. Usually they
need nothing, because the identity adapter and the original function already accept them.

### Matching (Kotlin override rules)

A candidate matches an interface if, for every required property `p: T`, the candidate has a member property
(declared or inherited, not an extension) named `p`, visible from generated code (`public` or `internal`), and:

- `val p: T` is required: the candidate has `val` or `var` of type `S`, where `S` is assignable to `T`
  (e.g. `Int` satisfies `Number`, `String` satisfies `String?`).
- `var p: T` is required: the candidate has `var` of exactly type `T` (including nullability) with a visible
  setter.

### Adapters

For each `@Structural` interface `I` (see "Usage" for which interfaces):

- **Identity adapter:** `fun I.asI(): I = this`, so `x.asI()` works for any value already implementing `I`.
- **Class adapters:** `fun C.asI(): I` for classes chosen by the planning rules below, where the "group" is the
  identity adapter (declared type `I`) and there are no member functions. This gives:
  - a matching class gets `= C_AsI(this)`
  - a subclass of an adapted class gets nothing (extension functions are inherited)
  - a class that implements `I` nominally but also inherits an adapter gets `= this`, which avoids ambiguity and keeps
    identity
- Adapter name: `as` + the interface's simple name. All adapters of `I` go in one file, `<I>_Adapters.kt`, in `I`'s
  package, so a single `import com.example.geometry.asSized` imports them for every class.
- Visibility: `internal` if `I` or `C` is `internal`, otherwise `public`.
- User-written functions named `asI` are not taken into account.

### Supported functions (overloads)

A function gets overloads if exactly **one** position in its signature, either a parameter or the extension
receiver, has a `@Structural` interface type, and it is one of:

- a top-level function, including extension functions (`fun Canvas.draw(shape: Sized)`) and extension functions on
  the interface itself (`fun Sized.area()`)
- a member function of a class, interface, object or companion object

and it is `public` or `internal`. `suspend` is copied. A nullable structural parameter (`Sized?`) gets overloads with
a non-null class type, so `f(null)` still only matches the original. A `vararg` structural parameter gets overloads
for one class at a time (`sizes(a, b)` works when `a` and `b` are the same class). Other `vararg` parameters are
copied as they are.

### Unsupported functions

A function that uses a `@Structural` interface in its receiver or parameter types but doesn't meet the rules above
gets **no overloads**. The processor logs this at **info** level, visible with `--info`, of the form:

```
i: [structural] Skipping com.example.fit(a: Sized, b: Sized): more than one @Structural parameter
```

It is not a warning: users don't opt in to anything, and such functions are still usable through adapters
(`fit(a.asSized(), b.asSized())`). Calls with a class instead of an adapter fail with the normal Kotlin type-mismatch
error.

| Case | Example | Why it is unsupported |
|---|---|---|
| Several structural positions | `fun fit(a: Sized, b: Sized)`, `fun Sized.fit(other: Sized)` | Overload count grows as the product of candidates; left for later |
| Structural type nested in another type | `fun total(items: List<Sized>)`, `fun f(g: (Sized) -> Unit)` | A proxy can't be passed in place of a collection or lambda |
| Other parameters with default values | `fun size(target: Sized, scale: Int = 1)` | KSP can't read default-value expressions, so the overload couldn't copy them |
| Type parameters | `fun <T> size(target: Sized, tag: T)` | Copying type parameters and bounds is left for later |
| `inline` functions | `inline fun size(target: Sized)` | Also covers `reified`; left for later |
| `private` / `protected` functions | `private fun size(target: Sized)` | The generated overload, in another file or outside the class, can't call it |
| `expect` / `actual` functions | `expect fun size(target: Sized)` | The PoC is JVM only |
| Member extension with a structural receiver | `class Owner { fun Sized.area() }` | Would need two receivers in the generated function |
| Members of generic or `inner` classes | `class Box<T> { fun put(target: Sized) }` | The extension overload would need the owner's type parameters |

Only parameter and receiver types are inspected; functions where a `@Structural` interface appears only in the return
type are ignored. Local functions are not visible to KSP and are silently ignored.

### What gets generated for overloads

For a supported function `f` and a class `C` that gets an overload:

- **Top-level `f`**: a top-level overload in the same package, same visibility, `suspend` modifier, extension
  receiver, other parameters (same names and order) and return type. The structural parameter's type is replaced by
  `C`.
- **Member `f` of `Owner`**: an **extension** overload `fun Owner.f(…, target: C, …)` in `Owner`'s package.
  Kotlin uses an extension when the member with the same name doesn't accept the arguments. For companion-object
  members the receiver is `Owner.Companion`, so `Owner.f(C())` works.
  Limitation: callers in other packages must import it (`import com.example.f`), as with any extension.
- **Structural receiver** `fun I.f(…)`: an overload `fun C.f(…) = this.asI().f(…)` in `f`'s package.
- Visibility: `internal` if the function (or its owner) or `C` is `internal`, otherwise `public`.
- Body: the structural argument is converted with its adapter and other parameters are passed by name. The adapter
  returns the interface type, so the call can't resolve to the generated overload again:
  ```kotlin
  public fun size(target: Rectangular): Int = size(target = target.asSized())   // `this.f(…)` for extension overloads
  public fun sizes(vararg targets: Rectangular): Int = sizes(targets = targets.map { it.asSized() }.toTypedArray())
  ```
  Named `vararg` arguments take the array directly, without `*`.
- One generated file per original source file for top-level overloads (`<File>_Structural.kt`), one per owner
  class for extension overloads (`<Owner>_Structural.kt`, `<Owner>_Companion_Structural.kt`). Both go in the function's (or owner's) package; if the two
  names coincide, the overloads share one file.
- An overload is **not** generated if the user already declared `f` with the same signature.

### Proxy

One proxy per (candidate, interface) pair, used only by that pair's adapter:

- `internal class <C>_As<I>(val target: C) : I` in `C`'s package. Nested class names are joined with `_`. If two
  pairs would get the same name, a short stable hash of the interface's qualified name is appended. If the interface
  itself has a property named `target`, `_` is appended to the proxy's property name until it is unique.
- `val` properties delegate their getter; `var` properties delegate getter and setter. Reads are live, not a
  snapshot.
- `toString()` delegates to `target`. `equals` is true for another proxy of the same interface whose `target` is
  equal; `hashCode()` delegates to `target`. (A proxy is never equal to a raw target, which keeps `equals`
  symmetric.)

### Overload resolution safety (ambiguity rules)

Generated overloads and adapters must never make code that used to compile ambiguous, and must never conflict with
each other. The same planner is used for both; for adapters, the group is described in "Adapters".

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
   - exactly one, `I`: generate `f(C)` calling the `I` version through `C.asI()`
   - several: generate nothing and report a **warning**
     (`[structural] Not generating com.example.f for com.example.model.C: it matches several @Structural interfaces (…)`).
     Calling `f(C())` then fails with the normal compiler error.

Example the rules handle:

```kotlin
open class Base(val width: Int, val height: Int)  // matches → size(Base) generated
class Mid : Base(1, 2)                            // only size(Base) applies → skipped
class Child : Base(1, 2), Sized                   // size(Base) and size(Sized) apply, neither more specific
                                                  // → size(Child) generated; Child.asSized() = this
```

Limitation: only classes in the scanned packages are checked. A class outside them that extends `Base` and
implements `Sized` nominally still gets an ambiguity error at its call sites.

## Processing

- Runs in one round. Generated code contains no `@Structural` interfaces or new candidates, so later rounds do
  nothing.
- Generated files are **aggregating** outputs that depend on all source files of the module. This is the simplest
  correct choice for the PoC; narrower dependencies are an optimization for later.
- Invalid `@Structural` interfaces are processor errors and fail the build. Classes matching several interfaces of
  one overloaded function are warnings. Unsupported functions are logged at info level.

## Testing

- **Processor tests** with kotlin-compile-testing for KSP2 (`dev.zacsweers.kctfork:ksp`): each rule above gets a
  test that compiles a snippet and checks the generated code, warnings, or errors, including a negative case
  where calling an unsupported/ambiguous function must fail to compile.
- Generation tests also fail on any compiler warning in generated code.
- **`sample` module**: no annotations besides `@Structural` and no Gradle options. It covers overloads (the original
  example, member, companion-object and extension functions, an extension on the interface, nullable and `vararg`
  parameters, `suspend`, `var` write-through, the base-class optimization and the `Child : Base, Sized` case) and
  adapters (a default parameter, `Int` for `Number`, `List<Sized>`, identity for nominal implementors). Its tests run
  the calls and check the results.

## Success criteria for the PoC

1. The examples from "Goal" compile and run with nothing but `@Structural` on the interface and the processor applied.
2. Adapters work for every function shape, including the ones overloads don't support.
3. Member functions work via extension overloads.
4. Every case in the `sample` module compiles, runs and gives correct results, without compiler warnings.
5. Unsupported functions never break the build or produce broken generated code.

## Out of scope / future work

- Methods in `@Structural` interfaces (match by name, parameter and return types).
- Overloads for several structural parameters, default values, generics and `inline` functions (adapters already cover
  these call sites). Mixed-class `vararg` calls.
- Scanning classes from dependencies (`Resolver.getDeclarationsFromPackage`, currently `@KspExperimental`) and Java
  getters (`getWidth()`).
- Cross-module use: a library can't generate adapters or overloads for classes of the app using it. The app gets
  adapters for library interfaces it uses in function signatures, but not overloads of library functions.
- Unwrapping proxies (`asOriginal()`), converting whole collections (`List<Rectangular>.asSized()`).
- A K2 compiler plugin alternative that needs no scanning: generate a generic stub `fun <T : Any> size(target: T)` in
  the frontend, check `T`'s shape with a frontend checker, and replace calls with proxies in the IR backend. Works
  across modules and only for used call sites, but relies on the unstable plugin API and has no IDE support by default.
