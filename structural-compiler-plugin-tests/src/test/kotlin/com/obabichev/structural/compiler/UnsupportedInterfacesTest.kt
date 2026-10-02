package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Interfaces the plugin can't use are never added to classes, so classes keep compiling exactly as without the plugin:
 * generic members, and a type parameter no member mentions, which a class could not say anything about. Generic
 * interfaces and generic superinterfaces are supported; see GenericInterfacesTest and GenericSuperinterfacesTest.
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
