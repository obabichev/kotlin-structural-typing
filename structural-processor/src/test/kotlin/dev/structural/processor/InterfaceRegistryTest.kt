package dev.structural.processor

import com.google.devtools.ksp.getClassDeclarationByName
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InterfaceRegistryTest {
    private fun propertiesOf(code: String, name: String): Pair<Set<String>?, Compiled> =
        resolve(kotlin("Source.kt", code)) { resolver, logger ->
            InterfaceRegistry(logger).get(resolver.getClassDeclarationByName(name)!!)
                ?.properties
                ?.map { "${if (it.mutable) "var" else "val"} ${it.name}: ${it.type.key()}" }
                ?.toSet()
        }

    @Test
    fun `collects own and inherited abstract properties`() {
        val (properties, _) = propertiesOf(
            """
            package test
            import dev.structural.Structural
            interface HasWidth { val width: Int }
            @Structural interface Sized : HasWidth {
                var height: Int?
                val area: Int get() = 0
            }
            """,
            "test.Sized",
        )
        assertEquals(setOf("val width: kotlin.Int", "var height: kotlin.Int?"), properties)
    }

    @Test
    fun `resolves property types inherited from generic superinterfaces`() {
        val (properties, _) = propertiesOf(
            """
            package test
            import dev.structural.Structural
            interface HasValue<T> { val value: T }
            @Structural interface IntValue : HasValue<Int>
            """,
            "test.IntValue",
        )
        assertEquals(setOf("val value: kotlin.Int"), properties)
    }

    @Test
    fun `rejects abstract functions`() {
        val (properties, compiled) = propertiesOf(
            """
            package test
            import dev.structural.Structural
            @Structural interface Shape {
                val width: Int
                fun area(): Int
            }
            """,
            "test.Shape",
        )
        assertNull(properties)
        assertContains(compiled.messages, "[structural] @Structural interface test.Shape must not declare abstract functions: area")
    }

    @Test
    fun `rejects classes`() {
        val (properties, compiled) = propertiesOf(
            """
            package test
            import dev.structural.Structural
            @Structural class NotAnInterface(val width: Int)
            """,
            "test.NotAnInterface",
        )
        assertNull(properties)
        assertContains(compiled.messages, "[structural] @Structural can only be applied to interfaces: test.NotAnInterface")
    }

    @Test
    fun `rejects type parameters`() {
        val (properties, compiled) = propertiesOf(
            """
            package test
            import dev.structural.Structural
            @Structural interface Boxed<T> { val value: T }
            """,
            "test.Boxed",
        )
        assertNull(properties)
        assertContains(compiled.messages, "[structural] @Structural interface test.Boxed must not have type parameters")
    }
}
