package dev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Inferred property and return types aren't known when supertypes are decided, so such members can't match. The plugin
 * warns when a class would match with its resolved types, naming the members to give explicit types.
 */
class InferredMemberTypesTest {
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
            "'Square' matches @Structural interface 'test.Sized' but doesn't implement it, because these members have " +
                "inferred types: width. Declare their types explicitly.",
        )
    }

    @Test
    fun `inferred return types get the warning`() {
        val compiled = compile(
            SHAPE,
            kotlin("Main.kt", "package test\nclass C(val name: String) { fun area() = 1\n fun describe(): String = \"\"\n fun scaled(factor: Int): Int = 0 }"),
        )
        assertEquals(1, compiled.warnings.size, compiled.messages)
        assertContains(compiled.warnings.single(), "these members have inferred types: area")
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
