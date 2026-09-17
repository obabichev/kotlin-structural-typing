package dev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NonMatchingTest {
    private fun assertTypeMismatch(model: String, call: String) {
        val compiled = compile(SIZED, kotlin("Main.kt", "package test\n$model\nfun run() = $call"))
        assertFalse(compiled.succeeded, "expected a compile error")
        assertContains(compiled.errors.joinToString("\n"), "mismatch", ignoreCase = true)
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
    fun `interfaces with abstract functions are never added`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Shape { val width: Int; fun area(): Int }
                class Line(val width: Int)
                fun run() = (Line(2) as Any is Shape).toString()
                """,
            ),
        )
        assertEquals("false", compiled.run(), "the class must still compile and not implement the interface")
    }

    @Test
    fun `interfaces extending other interfaces are never added`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                interface HasWidth { val width: Int }
                @Structural interface Sized : HasWidth { val height: Int }
                class Tall(val height: Int)
                fun run() = (Tall(1) as Any is Sized).toString()
                """,
            ),
        )
        assertEquals("false", compiled.run(), "the class must still compile and not implement the interface")
    }

    @Test
    fun `generic interfaces are never added`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Boxed<T> { val value: T }
                class IntBox(val value: Int)
                fun run() = (IntBox(1) as Any is Boxed<*>).toString()
                """,
            ),
        )
        assertEquals("false", compiled.run())
    }

    @Test
    fun `missing override on a declared supertype is still a normal error`() {
        val compiled = compile(SIZED, kotlin("Main.kt", "package test\nclass Explicit(val width: Int, val height: Int) : Sized"))
        assertFalse(compiled.succeeded)
        assertTrue(compiled.errors.any { "override" in it }, compiled.messages)
    }

    @Test
    fun `enum classes are not supported yet`() =
        assertTypeMismatch("enum class Paper(val width: Int, val height: Int) { A4(210, 297) }", "size(Paper.A4)")

    @Test
    fun `interfaces from other modules are not supported yet`() {
        val library = compile(SIZED, withPlugin = false)
        assertTrue(library.succeeded, library.messages)
        val libraryClasses = java.io.File(library.loadClass("test.Sized").protectionDomain.codeSource.location.toURI())

        val compiled = compile(
            kotlin("Main.kt", "package test\nclass Rectangular(val width: Int, val height: Int)\nfun run() = size(Rectangular(2, 2))"),
            classpath = listOf(libraryClasses),
        )
        assertFalse(compiled.succeeded)
    }
}
