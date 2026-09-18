package com.obabichev.structural

/**
 * Marks an interface that classes implement by shape rather than by declaration.
 *
 * With the structural compiler plugin applied, every class in the module whose properties match the interface's
 * abstract properties is compiled as if it declared the interface as a supertype.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Structural
