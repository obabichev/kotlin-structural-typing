package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Function requirements (using `test.Shape`, see [SHAPE]): a class function matches with the same name, the same
 * parameter names and exact parameter types, the same `suspend`, and a return type that is the same or a subtype. It must
 * be public, not `inline`, not an extension, and without default values, because Kotlin wouldn't accept it as an override.
 * Only the matching function is marked `override`; overloads are left alone.
 */
class FunctionsTest {
    private fun isShape(model: String, construct: String): String =
        compile(SHAPE, kotlin("Main.kt", "package test\n$model\nfun run() = ($construct as Any is Shape).toString()")).run() as String

    private val complete = """
        fun area(): Int = 1
        fun describe(): String = ""
    """

    @Test
    fun `class with matching functions implements the interface`() {
        val compiled = compile(
            SHAPE,
            kotlin(
                "Main.kt",
                """
                package test
                class Rect(val name: String, val w: Int, val h: Int) {
                    fun area(): Int = w * h
                    fun scaled(factor: Int): Int = area() * factor
                    fun describe(): String = "rect"
                }
                fun run() = measure(Rect("r", 2, 3))
                """,
            ),
        )
        assertEquals("r:6/12/rect", compiled.run())
    }

    @Test
    fun `unrelated overloads are left alone`() {
        val compiled = compile(
            SHAPE,
            kotlin(
                "Main.kt",
                """
                package test
                class C(val name: String) {
                    fun area(): Int = 1
                    fun describe(): String = "c"
                    fun scaled(factor: Int): Int = factor
                    fun scaled(factor: Long): Long = factor
                    fun area(times: Int): Int = times
                }
                fun run() = measure(C("c"))
                """,
            ),
        )
        assertEquals("c:1/2/c", compiled.run())
    }

    @Test
    fun `parameter names must match`() =
        assertEquals("false", isShape("class C(val name: String) { $complete\n fun scaled(by: Int): Int = by }", "C(\"c\")"))

    @Test
    fun `parameter types must match exactly`() =
        assertEquals("false", isShape("class C(val name: String) { $complete\n fun scaled(factor: Long): Int = 0 }", "C(\"c\")"))

    @Test
    fun `return type must be a subtype`() =
        assertEquals("false", isShape("class C(val name: String) { fun area(): Int = 1\n fun describe(): Any = \"\"\n fun scaled(factor: Int): Int = 0 }", "C(\"c\")"))

    @Test
    fun `suspend must match`() =
        assertEquals("false", isShape("class C(val name: String) { $complete\n suspend fun scaled(factor: Int): Int = 0 }", "C(\"c\")"))

    @Test
    fun `functions with default values do not match`() =
        assertEquals("false", isShape("class C(val name: String) { $complete\n fun scaled(factor: Int = 1): Int = 0 }", "C(\"c\")"))

    @Test
    fun `private functions do not match`() =
        assertEquals("false", isShape("class C(val name: String) { $complete\n private fun scaled(factor: Int): Int = 0 }", "C(\"c\")"))

    @Test
    fun `extension functions do not match`() =
        assertEquals("false", isShape("class C(val name: String) { $complete\n fun Int.scaled(factor: Int): Int = 0 }", "C(\"c\")"))

    @Test
    fun `inline functions do not match`() =
        assertEquals("false", isShape("class C(val name: String) { $complete\n inline fun scaled(factor: Int): Int = 0 }", "C(\"c\")"))
}
