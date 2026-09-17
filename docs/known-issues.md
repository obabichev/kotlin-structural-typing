# Known issues and limitations

Issues found while building the KSP proof of concept (automatic overloads plus adapters). Items marked **verified** were reproduced with a compile test
(Kotlin 2.4.20, KSP 2.3.12). Design details are in [`proposal.md`](../proposal.md).

## Using the library

### 1. Generated extensions must be imported (verified)

Both kinds of generated extension functions are invisible from other packages until imported:

- **Adapters** live in the interface's package. One import covers every class:
  `import com.example.geometry.asSized` (verified).
- **Overloads of member functions** are extensions in the owner's package:

  ```kotlin
  package com.example.app

  import com.example.geometry.Canvas
  import com.example.model.Rectangular

  Canvas().fits(Rectangular(1, 2, "red"))
  // e: Argument type mismatch: actual type is 'Rectangular', but 'Sized' was expected.
  ```

  The error doesn't mention a missing import, so it is easy to misread as "the processor did nothing".
  **Workaround:** `import com.example.geometry.fits`. IDE auto-import finds it after a build.

Top-level overloads are **not** affected: `import com.example.geometry.size` imports all `size` overloads, including
generated ones (verified). **Possible fix for members:** generate a member overload, which KSP can't do (it only adds
new files); that would need a compiler plugin.

### 2. Generated code exists only after KSP runs

Overloads and proxies are generated during the build. Until KSP has run (first build, or after adding a class or
function), the IDE shows calls like `size(Rectangular(…))` as errors, and the build is the source of truth.

### 3. The argument's static type must be the matching class (verified)

Adapters and overloads are both picked at compile time from the declared type of the value:

```kotlin
val value: Any = Rectangular(1, 2, "red")
size(value)  // e: None of the following candidates is applicable
```

The same applies to type parameters and nullable variables: for `fun describe(target: Sized?)` the overload takes a
non-null `Rectangular`, so `describe(maybeRectangular)` with a `Rectangular?` value doesn't compile. Collections have to be converted element by element:
`listOf(rect.asSized())` works, but a `List<Rectangular>` can't be passed as `List<Sized>` (verified) and has to be
mapped (`rects.map { it.asSized() }`).

### 4. Only classes in the scanned packages of the current module are supported (verified)

A matching class outside `structural.packages` gets no overload and fails with a type mismatch. The same applies to
classes from dependencies and to classes in other modules. A library declaring `size(Sized)` can't generate overloads
for classes of the app that uses it.

### 5. Nominal subclass outside the scanned packages is ambiguous (verified)

```kotlin
// com.example.model (scanned): open class Base(val width: Int, val height: Int)
//   → Base.asSized() and size(Base) generated
// com.example.app (not scanned):
class Outside : Base(1, 2), Sized
Outside().asSized()  // e: Overload resolution ambiguity: Base.asSized() vs Sized.asSized()
size(Outside())      // e: Overload resolution ambiguity: size(Base) vs size(Sized)
```

Inside the scanned packages this is handled by generating `Child.asSized() = this` and `size(Child)`. Outside them,
the processor never sees the class. **Workaround:** `Outside()` already is a `Sized`, so pass it without the adapter
(`size(Outside() as Sized)` for overloads), or add the package to `structural.packages`.

### 6. Classes matching several interfaces of one overloaded function get nothing

For `describe(Sized)` and `describe(Named)`, a class matching both gets no overload and a warning
(`[structural] Not generating … it matches several @Structural interfaces …`). Calling `describe` with it fails to
compile. This is intentional: any choice would be arbitrary. Adapters still work: `describe(labeled.asSized())`
(verified).

### 7. Proxies are not the original object

For classes that match only structurally, `asSized()` (and every overload) passes a proxy:

- `proxy === original` is `false`, and `proxy is Rectangular` is `false`.
- A proxy equals another proxy of the same class and interface with an equal target, but never the raw object.
- Every conversion allocates a new proxy.
- Only the interface's properties are forwarded; storing the proxy keeps the original object alive but hides its type.

Classes that implement the interface nominally are returned as-is by their adapter, so identity is kept (verified).

### 8. Some function shapes get no overloads, and the reason is only logged at info level

Functions with default values, several structural positions (`fit(a: Sized, b: Sized)`, `Sized.fit(other: Sized)`),
`List<Sized>` or lambda parameters, type parameters, `inline`, member extensions with a structural receiver and
members of generic classes get no overloads. See the table in [`proposal.md`](../proposal.md#unsupported-functions).

Callers use adapters there (e.g. `total(listOf(a.asSized()))` for `fun total(items: List<Sized>, scale: Int = 1)`,
verified). But a call with a class fails with a plain type mismatch, and the explanation
(`[structural] Skipping …: parameters with default values`) is only visible when building with `--info`. It is not a
warning because users don't opt in to anything and warnings on ordinary code would be noise.

### 9. Long compiler errors

When no overload applies, the Kotlin error lists **every** candidate, including all generated overloads (verified:
`None of the following candidates is applicable: fun size(target: Base) … fun size(target: Rect) …`). With many
matching classes, the real problem is hard to find in the error message.

### 10. Generated code is public API, and there is a lot of it

Overloads are generated for every supported function and matching class, so they grow as
*functions × matching classes*. They show up in autocomplete, in the module's ABI and in binary-compatibility checks.
The sample (11 functions taking `@Structural` interfaces, 6 model classes) generates:

- 23 one-line overloads for the 9 supported functions
- 8 adapters (one identity adapter per interface plus one per adapted class)
- 4 proxy classes

Setting `structural.packages` narrows which classes get overloads and adapters.

### 11. Every matching class in the module is a candidate

Without `structural.packages`, any class in the module whose properties happen to match (e.g. anything with
`width: Int` and `height: Int`) gets adapters and overloads, including classes never meant to be passed that way.

### 12. `vararg` overloads accept one class at a time

`fun totalSize(vararg targets: Sized)` gets `totalSize(vararg targets: Rectangular)` and so on, so
`totalSize(Rectangular(…), Rectangular(…))` works but `totalSize(Rectangular(…), Window())` doesn't compile. Use
adapters for mixed calls: `totalSize(a.asSized(), b.asSized())`.

## Processor gaps

### 13. Adapters for library interfaces are generated into the library's package

If `Sized` comes from a dependency, its adapters are written into that dependency's package in this module. This works
on the JVM, but a package split across two jars breaks with the Java module system (JPMS) and looks surprising.

### 14. Some interfaces get no adapters

Adapters are generated for `@Structural` interfaces declared in the module, plus library interfaces used in a function's
receiver or parameter types. A library interface used only in property types, return types or class supertypes gets no
adapters.

### 15. Adapter names can clash

- A hand-written `fun Rectangular.asSized()` is not detected and conflicts with the generated one.
- Two interfaces with the same simple name in different packages both produce `asSized()`. Importing both into one
  file makes calls ambiguous.


### 16. Overloads declared elsewhere are not considered

The ambiguity rules look only at functions declared **in the same package in this module** (top-level) or **directly in
the owner class** (members). Not considered:

- member overloads inherited from the owner's superclasses
- same-named top-level functions from other packages or dependencies that callers import together

In those cases the generated overload may be ambiguous or never picked.

### 17. KSP "upcoming features" notice (verified)

Every compilation prints:

```
i: [ksp] Processor 'dev.structural.processor.StructuralProcessor' has not opted in for upcoming features yet.
It might break in a future version of KSP.
```

Not investigated yet: find the KSP 2.3 opt-in mechanism and check the processor is compatible.

### 18. Every change regenerates everything

All generated files are aggregating outputs that depend on every source file in the module, so any change reruns the
whole generation. That is fine for a PoC but slow for large modules.

### 19. Matching ignores Java getters

Only Kotlin properties match. A Java class with `getWidth()` doesn't match `val width: Int`.

## Development notes

### 20. Gradle needs JDK 17

Gradle 9.7.1 doesn't run on JDK 11, the default `java` on the development machine. Run builds with
`JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew build`. Modules use `jvmToolchain(17)`.

### 21. `getAllSuperTypes` needs an import

In KSP 2.3, `getAllProperties()` and `getAllFunctions()` are members of `KSClassDeclaration`, but `getAllSuperTypes()` is
an extension function: `import com.google.devtools.ksp.getAllSuperTypes`.

### 22. Soft keywords in test sources

In a test snippet, `companion object` followed by `private class Hidden` on the next line parses as a companion
**named** `private`, followed by a *public* `class Hidden`. Put companion objects last or give them a body `{}`.
