package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An interface the plugin can't use is ignored, and a class that looks like it should match silently doesn't. The
 * warning says so on the interface itself, which is the only place the cause is visible.
 */
class UnusableInterfacesTest {
    private fun warningFor(code: String): String {
        val compiled = compile(kotlin("Main.kt", code))
        assertTrue(compiled.succeeded, compiled.messages)
        assertEquals(1, compiled.warnings.size, compiled.messages)
        return compiled.warnings.single()
    }

    @Test
    fun `a parameter no member mentions is named`() {
        val warning = warningFor(
            """
            package test
            import com.obabichev.structural.Structural
            @Structural interface Tagged<T> { val name: String }
            class Product(val name: String)
            """,
        )
        assertContains(warning, "@Structural interface 'Tagged' is ignored, so no class will implement it")
        assertContains(warning, "no member mentions 'T', so a class can't say what it is")
    }

    @Test
    fun `an extension member is named`() {
        val warning = warningFor(
            """
            package test
            import com.obabichev.structural.Structural
            @Structural interface Printer { val String.width: Int }
            """,
        )
        assertContains(warning, "'width' is an extension, which a class can't implement")
    }

    @Test
    fun `a reason inherited from a superinterface is named`() {
        val warning = warningFor(
            """
            package test
            import com.obabichev.structural.Structural
            interface Formats { val Int.padded: String }
            @Structural interface Pipeline : Formats { val name: String }
            """,
        )
        assertContains(warning, "@Structural interface 'Pipeline' is ignored")
        assertContains(warning, "'padded' is an extension, which a class can't implement")
    }

    @Test
    fun `extending a generic interface is usable, so it is not warned about`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Ranked : Comparable<String>
                class Word(private val word: String) {
                    operator fun compareTo(other: String): Int = word.compareTo(other)
                }
                fun run() = (Word("a") as Any is Ranked).toString()
                """,
            ),
        )
        assertEquals(emptyList(), compiled.warnings, compiled.messages)
        assertEquals("true", compiled.run())
    }

    @Test
    fun `a generic member is not warned about`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Mapper { fun <T> map(value: T): T }
                class Identity { fun <R> map(value: R): R = value }
                fun run() = (Identity() as Any is Mapper).toString()
                """,
            ),
        )
        assertEquals(emptyList(), compiled.warnings, compiled.messages)
        assertEquals("true", compiled.run())
    }

    @Test
    fun `a generic interface a class can pin down is not warned about`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Box<T> { val value: T }
                class IntBox(val value: Int)
                fun run() = (IntBox(1) as Any is Box<*>).toString()
                """,
            ),
        )
        assertEquals(emptyList(), compiled.warnings, compiled.messages)
        assertEquals("true", compiled.run())
    }

    @Test
    fun `an interface the plugin can use is not warned about`() {
        val compiled = compile(
            SIZED,
            kotlin("Main.kt", "package test\nclass Rectangular(val width: Int, val height: Int)\nfun run() = size(Rectangular(2, 3))"),
        )
        assertEquals(emptyList(), compiled.warnings, compiled.messages)
        assertEquals(6, compiled.run())
    }
}
