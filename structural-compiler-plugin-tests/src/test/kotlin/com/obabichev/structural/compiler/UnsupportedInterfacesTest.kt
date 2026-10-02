package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Interfaces the plugin can't use are never added to classes, so classes keep compiling exactly as without the plugin:
 * extension members, which no class can implement, and a type parameter no member mentions, which a class could not
 * say anything about. Generic interfaces, generic superinterfaces and generic members are supported; see
 * GenericInterfacesTest, GenericSuperinterfacesTest and GenericMembersTest.
 */
class UnsupportedInterfacesTest {
    @Test
    fun `an interface whose parameter no member mentions is never added`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Tagged<T> { val name: String }
                class Product(val name: String)
                fun run() = (Product("p") as Any is Tagged<*>).toString()
                """,
            ),
        )
        assertEquals("false", compiled.run())
    }

    @Test
    fun `interfaces with extension members are never added`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Printer { val String.width: Int }
                class Ruler { val width: Int = 1 }
                fun run() = (Ruler() as Any is Printer).toString()
                """,
            ),
        )
        assertEquals("false", compiled.run())
    }
}
