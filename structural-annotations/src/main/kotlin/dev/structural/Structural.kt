package dev.structural

/**
 * Marks an interface whose shape, not its name, decides which classes a function accepts.
 *
 * The KSP processor generates overloads of functions taking this interface for every class in the
 * configured packages whose properties match it.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Structural
