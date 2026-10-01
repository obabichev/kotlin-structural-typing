package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A class that nearly matches a @Structural interface is the common mistake, and the compiler's own
 * `Argument type mismatch` says nothing about why. The plugin explains the near miss at the call site, and on the class
 * itself when it is close enough to be worth a warning.
 */
class NearMissTest {
    @Test
    fun `explains a wrong return type at the call site`() {
        val compiled = compile(
            SHAPE,
            kotlin(
                "Main.kt",
                """
                package test
                class Slab(val name: String) {
                    fun area(): Long = 1L
                    fun scaled(factor: Int): Int = 0
                    fun describe(): String = ""
                }
                fun use() = measure(Slab("slab"))
                """,
            ),
        )
        assertContains(compiled.messages, "'Slab' does not implement @Structural interface 'test.Shape'")
        assertContains(compiled.messages, "area(): returns 'Long', expected 'Int'")
    }

    @Test
    fun `explains a member that is not public`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                class Panel(val width: Int) {
                    internal val height: Int = 4
                }
                fun use() = size(Panel(2))
                """,
            ),
        )
        assertContains(compiled.messages, "height: is internal, must be public")
    }

    @Test
    fun `explains a parameter name that differs`() {
        val compiled = compile(
            SHAPE,
            kotlin(
                "Main.kt",
                """
                package test
                class Square(val name: String) {
                    fun area(): Int = 1
                    fun scaled(times: Int): Int = 0
                    fun describe(): String = ""
                }
                fun use() = measure(Square("square"))
                """,
            ),
        )
        assertContains(compiled.messages, "scaled(): parameter 1 is named 'times', expected 'factor'")
    }

    @Test
    fun `explains a val where the interface declares a var`() {
        val compiled = compile(
            kotlin(
                "Counter.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Counter { var count: Int; val label: String }
                fun report(counter: Counter) = counter.label + counter.count
                """,
            ),
            kotlin(
                "Main.kt",
                """
                package test
                class Once { val count: Int = 1
                    val label: String = "once" }
                fun use() = report(Once())
                """,
            ),
        )
        assertContains(compiled.messages, "count: is a val, the interface declares a var")
    }

    @Test
    fun `warns on the class itself when only one requirement is unmet`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                class Slab(val width: Int) {
                    val height: Long = 2L
                }
                """,
            ),
        )
        assertEquals(1, compiled.warnings.size, compiled.messages)
        assertContains(compiled.messages, "'Slab' almost implements @Structural interface 'test.Sized'")
        assertContains(compiled.messages, "height: is 'Long', expected 'Int'")
    }

    @Test
    fun `stays quiet about classes that share no members`() {
        val compiled = compile(
            SIZED,
            kotlin("Main.kt", "package test\nclass Logger { fun log(message: String) {} }"),
        )
        assertEquals(emptyList(), compiled.warnings, compiled.messages)
    }

    @Test
    fun `stays quiet about a class that misses every requirement`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                class Label(val width: String) {
                    val height: String = ""
                }
                """,
            ),
        )
        assertEquals(emptyList(), compiled.warnings, compiled.messages)
    }

    @Test
    fun `says nothing when the class implements the interface`() {
        val compiled = compile(
            SIZED,
            kotlin("Main.kt", "package test\nclass Box(val width: Int, val height: Int)\nfun run() = size(Box(2, 3))"),
        )
        assertTrue(compiled.succeeded, compiled.messages)
        assertEquals(emptyList(), compiled.warnings, compiled.messages)
    }
}
