package dev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class InferredPropertyTypesTest {
    @Test
    fun `warns when a class matches only with inferred property types`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                class Square(val side: Int) {
                    val width get() = side
                    val height: Int get() = side
                }
                """,
            ),
        )
        assertEquals(1, compiled.warnings.size, compiled.messages)
        assertContains(
            compiled.warnings.single(),
            "'Square' matches @Structural interface 'test.Sized' but doesn't implement it, because these properties have " +
                "inferred types: width. Declare their types explicitly.",
        )
    }

    @Test
    fun `no warning when the inferred types would not match anyway`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                class Label(val text: String) {
                    val width get() = text
                    val height get() = text
                }
                """,
            ),
        )
        assertEquals(emptyList(), compiled.warnings)
    }
}
