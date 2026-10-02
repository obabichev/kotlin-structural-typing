# Roadmap

Planned work for the structural typing compiler plugin, roughly in priority order. Current behavior is described in
[`design.md`](design.md), current limitations in [`known-issues.md`](known-issues.md).

## Language support

### Generic interfaces and members

A `@Structural` interface may be generic, or extend a generic interface. A generic interface takes the arguments the
class in front of it gives its parameters; an extended one takes the arguments written down for it, substituted into the
members inherited from it, including where a parameter sits inside another type.

```kotlin
@Structural interface Ranked : Comparable<String>         // works: asks for compareTo(String)
@Structural interface Texts : Holder<String>              // works: val items: List<T> asks for List<String>
@Structural interface Box<T> { val value: T }             // works: IntBox(val value: Int) is a Box<Int>
@Structural interface Tagged<T> { val name: String }      // ignored: nothing says what T is
@Structural interface Mapper { fun <T> map(value: T): T } // ignored: generic member
```

What is left:

- **Variance:** types with arguments must be equal, so a `Box<Int>` does not satisfy `Box<Number>` and a class gets the
  most specific interface only. Relaxing it needs variance-aware comparison written by hand, since the compiler's type
  checker can't be used while supertypes are being decided.
- **Generic members:** `fun <T> map(value: T): T` is still refused; matching would need type parameters with equivalent
  bounds, compared after renaming.


