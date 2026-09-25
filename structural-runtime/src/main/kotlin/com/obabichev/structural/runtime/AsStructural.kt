package com.obabichev.structural.runtime

/**
 * Views [this] as the `@Structural` interface [T], which it implements because it was loaded through a
 * [StructuralClassLoader] or a JVM running the structural agent.
 *
 * The cast has to start from `Any`. The compiler still reads the class file, which does not name the interface, so a
 * direct `rect as Sized` is a `CAST_NEVER_SUCCEEDS` warning and `rect is Sized` is an outright error on Kotlin 2.4 —
 * both classes are final and, as far as the frontend can see, unrelated. Going through `Any` says nothing false.
 */
inline fun <reified T : Any> Any.asStructural(): T = this as T

/** Whether [this] implements the `@Structural` interface [T] at runtime. See [asStructural] for why it takes `Any`. */
inline fun <reified T : Any> Any.isStructural(): Boolean = this is T
