package dev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Property requirements follow Kotlin override rules: a `val` accepts a public `val` or `var` of a subtype, a `var` needs a
 * `var` of exactly the same type with a public setter. Properties with a default getter in the interface aren't required.
 * Anything else doesn't match, and the class simply doesn't implement the interface.
 */
class PropertiesTest {
    private fun assertTypeMismatch(model: String, call: String) {
        val compiled = compile(SIZED, kotlin("Main.kt", "package test\n$model\nfun run() = $call"))
        assertFalse(compiled.succeeded, "expected a compile error")
        assertContains(compiled.errors.joinToString("\n"), "mismatch", ignoreCase = true)
    }

    @Test
    fun `val accepts a subtype and var requires the exact type`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Measurable { val length: Number }
                @Structural interface Counter { var count: Int }
                class Rope(val length: Int)
                class Clicks(var count: Int)
                fun describe(target: Measurable) = target.length.toString()
                fun increment(target: Counter) { target.count++ }
                fun run(): String {
                    val clicks = Clicks(1)
                    increment(clicks)
                    return describe(Rope(5)) + clicks.count
                }
                """,
            ),
        )
        assertEquals("52", compiled.run())
    }

    @Test
    fun `interface properties with a default getter are not required`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Shape {
                    val width: Int
                    val label: String get() = "shape"
                }
                class Line(val width: Int)
                fun describe(shape: Shape) = shape.label + shape.width
                fun run() = describe(Line(3))
                """,
            ),
        )
        assertEquals("shape3", compiled.run())
    }

    @Test
    fun `wrong property types`() = assertTypeMismatch("class Named(val width: String, val height: String)", "size(Named(\"a\", \"b\"))")

    @Test
    fun `missing property`() = assertTypeMismatch("class Flat(val width: Int)", "size(Flat(1))")

    @Test
    fun `private property`() = assertTypeMismatch("class Hidden(private val width: Int, val height: Int)", "size(Hidden(1, 2))")

    @Test
    fun `var with private setter`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Counter { var count: Int }
                class Guarded { var count: Int = 0
                    private set }
                fun increment(target: Counter) { target.count++ }
                fun run() = increment(Guarded())
                """,
            ),
        )
        assertFalse(compiled.succeeded)
        assertContains(compiled.errors.joinToString("\n"), "mismatch", ignoreCase = true)
    }

    @Test
    fun `const properties do not match`() {
        val compiled = compile(SIZED, kotlin("Main.kt", "package test\nobject Stamp { const val width: Int = 1; val height: Int = 1 }\nfun run() = (Stamp as Any is Sized).toString()"))
        assertEquals("false", compiled.run())
    }
}
