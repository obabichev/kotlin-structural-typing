# Known issues and limitations

Issues found while building the KSP proof of concept. Items marked **verified** were reproduced with a compile test
(Kotlin 2.4.20, KSP 2.3.12). Design details are in [`proposal.md`](../proposal.md).

## Using the library

### 1. Extension overloads for member functions must be imported (verified)

A member function `Canvas.fits(target: Sized)` gets a generated **extension** overload `fun Canvas.fits(target: Rectangular)`
in `Canvas`'s package. Callers in another package don't see extensions unless they import them:

```kotlin
package com.example.app

import com.example.geometry.Canvas
import com.example.model.Rectangular

Canvas().fits(Rectangular(1, 2, "red"))
// e: Argument type mismatch: actual type is 'Rectangular', but 'Sized' was expected.
```

The error doesn't mention a missing import, so it is easy to misread as "the processor did nothing".

- **Workaround:** `import com.example.geometry.fits` (or `import com.example.geometry.*`). IDE auto-import finds it after a build.
- Top-level functions are **not** affected: `import com.example.geometry.size` imports all `size` overloads,
  including generated ones (verified).
- **Possible fix:** generate a member overload instead, which KSP cannot do (it only adds new files). This would need a
  compiler plugin.

### 2. Generated code exists only after KSP runs

Overloads and proxies are generated during the build. Until KSP has run (first build, or after adding a class or
function), the IDE shows calls like `size(Rectangular(…))` as errors, and the build is the source of truth.

### 3. The argument's static type must be the matching class (verified)

Overloads are picked at compile time from the declared type of the argument:

```kotlin
val value: Any = Rectangular(1, 2, "red")
size(value)  // e: None of the following candidates is applicable
```

The same applies to `Rectangular?`, type parameters, and to containers:
`total(listOf(Rectangular(…)))` for `fun total(items: List<Sized>)` fails with
`Argument type mismatch: actual type is 'List<Rectangular>', but 'List<Sized>' was expected` (verified).
Functions like `total` are skipped with a warning.

### 4. Only classes in the scanned packages of the current module are supported (verified)

A matching class outside `structural.packages` gets no overload and fails with a type mismatch. The same applies to
classes from dependencies and to classes in other modules. A library declaring `size(Sized)` can't generate overloads
for classes of the app that uses it.

### 5. Nominal subclass outside the scanned packages is ambiguous (verified)

```kotlin
// com.example.model (scanned): open class Base(val width: Int, val height: Int) → size(Base) generated
// com.example.app (not scanned):
class Outside : Base(1, 2), Sized
size(Outside())  // e: Overload resolution ambiguity between candidates: size(Base), size(Sized)
```

Inside the scanned packages this is handled by generating `size(Child)`. Outside them, the processor never sees the class.
**Workaround:** cast (`size(Outside() as Sized)`) or add the package to `structural.packages`.

### 6. Classes matching several interfaces of one overloaded function get nothing

For `describe(Sized)` and `describe(Named)`, a class matching both gets no overload and a warning
(`[structural] Not generating … it matches several @Structural interfaces …`). Calling `describe` with it fails to
compile. This is intentional: any choice would be arbitrary.

### 7. The function receives a proxy, not the original object

- `target === original` is `false` inside the function, and `target is Rectangular` is `false`.
- A proxy equals another proxy of the same class and interface with an equal target, but never the raw object.
- Every call allocates a new proxy.
- Only the object's properties are forwarded, so storing the proxy keeps the original object alive but hides its type.

### 8. Unsupported function shapes

Functions with default values, several structural parameters, nullable structural parameters, `vararg`, type
parameters, `inline`, companion-object members, members of generic classes and others are skipped with a warning. See the
table in [`proposal.md`](../proposal.md#unsupported-functions). Default values are the most painful one: KSP can't read
default-value expressions, so they can't be copied to the overload.

### 9. Long compiler errors

When no overload applies, the Kotlin error lists **every** candidate, including all generated overloads (verified:
`None of the following candidates is applicable: fun size(target: Base) … fun size(target: Rect) …`). With many
matching classes, the real problem is hard to find in the error message.

### 10. Generated overloads are public API

A public function gets public overloads for every matching public class. They show up in autocomplete, in the module's
ABI, and in binary-compatibility checks. The number of overloads is *functions × matching classes*: in the sample, 6
functions and 6 model classes produce 13 overloads.

## Processor gaps

### 11. Overloads declared elsewhere are not considered

The ambiguity rules look only at functions declared **in the same package in this module** (top-level) or **directly in
the owner class** (members). Not considered:

- member overloads inherited from the owner's superclasses
- same-named top-level functions from other packages or dependencies that callers import together

In those cases the generated overload may be ambiguous or never picked.

### 12. KSP "upcoming features" notice (verified)

Every compilation prints:

```
i: [ksp] Processor 'dev.structural.processor.StructuralProcessor' has not opted in for upcoming features yet.
It might break in a future version of KSP.
```

Not investigated yet: find the KSP 2.3 opt-in mechanism and check the processor is compatible.

### 13. Every change regenerates everything

All generated files are aggregating outputs that depend on every source file in the module, so any change reruns the
whole generation. That is fine for a PoC but slow for large modules.

### 14. Matching ignores Java getters

Only Kotlin properties match. A Java class with `getWidth()` doesn't match `val width: Int`.

## Development notes

### 15. Gradle needs JDK 17

Gradle 9.7.1 doesn't run on JDK 11, the default `java` on the development machine. Run builds with
`JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew build`. Modules use `jvmToolchain(17)`.

### 16. `getAllSuperTypes` needs an import

In KSP 2.3, `getAllProperties()` and `getAllFunctions()` are members of `KSClassDeclaration`, but `getAllSuperTypes()` is
an extension function: `import com.google.devtools.ksp.getAllSuperTypes`.

### 17. Soft keywords in test sources

In a test snippet, `companion object` followed by `private class Hidden` on the next line parses as a companion
**named** `private`, followed by a *public* `class Hidden`. Put companion objects last or give them a body `{}`.
