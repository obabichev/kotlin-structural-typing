package dev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Interfaces the plugin doesn't support yet are never added to classes, so classes keep compiling exactly as without the
 * plugin: generic interfaces and members, generic superinterfaces, and interfaces from other modules. See docs/roadmap.md.
 */
class UnsupportedInterfacesTest {
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
    fun `interfaces with generic functions or generic superinterfaces are never added`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Converter { fun <T> convert(value: T): T }
                interface Box<T> { val value: T }
                @Structural interface IntBox : Box<Int>
                class Identity { fun <T> convert(value: T): T = value }
                class Holder(val value: Int)
                fun run() = listOf(Identity() as Any is Converter, Holder(1) as Any is IntBox).toString()
                """,
            ),
        )
        assertEquals("[false, false]", compiled.run())
    }

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
