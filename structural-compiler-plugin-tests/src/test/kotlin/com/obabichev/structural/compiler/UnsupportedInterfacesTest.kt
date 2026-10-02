package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Interfaces the plugin doesn't support yet are never added to classes, so classes keep compiling exactly as without the
 * plugin: generic interfaces and generic members. A generic *superinterface* is supported when it says what its
 * arguments are; see GenericSuperinterfacesTest. See docs/roadmap.md.
 */
class UnsupportedInterfacesTest {
    @Test
    fun `generic interfaces are never added`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Boxed<T> { val value: T }
                class IntBox(val value: Int)
                fun run() = (IntBox(1) as Any is Boxed<*>).toString()
                """,
            ),
        )
        assertEquals("false", compiled.run())
    }

    @Test
    fun `interfaces with generic functions are never added`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Converter { fun <T> convert(value: T): T }
                class Identity { fun <T> convert(value: T): T = value }
                fun run() = (Identity() as Any is Converter).toString()
                """,
            ),
        )
        assertEquals("false", compiled.run())
    }
}
