package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UnsupportedFunctionsTest {
    private fun messagesFor(code: String): String = compile(
        kotlin(
            "Functions.kt",
            """
            package test
            import dev.structural.Structural
            @Structural interface Sized { val width: Int }
            """ + code,
        ),
    ).messages

    /** Skips are logged at info level: users write these functions without opting in to anything. */
    private fun assertSkipped(code: String, expected: String) {
        val messages = messagesFor(code)
        assertContains(messages, "[structural] Skipping $expected")
        assertEquals(emptyList(), messages.lines().filter { it.startsWith("w:") && "[structural]" in it })
    }

    @Test
    fun `several structural parameters`() =
        assertSkipped("fun fit(a: Sized, b: Sized) = 0", "test.fit(a: Sized, b: Sized): more than one @Structural parameter")

    @Test
    fun `structural receiver and parameter`() =
        assertSkipped("fun Sized.fit(other: Sized) = 0", "test.fit(other: Sized): more than one @Structural parameter")

    @Test
    fun `member extension with structural receiver`() =
        assertSkipped("class Owner { fun Sized.area() = 0 }", "test.Owner.area(): member extension function with a @Structural receiver")

    @Test
    fun `structural type nested in a collection`() =
        assertSkipped("fun total(items: List<Sized>) = 0", "test.total(items: List<Sized>): a @Structural interface nested in a parameter type")

    @Test
    fun `structural type nested in a lambda`() =
        assertContains(messagesFor("fun each(action: (Sized) -> Unit) = 0"), "): a @Structural interface nested in a parameter type")

    @Test
    fun `default values`() =
        assertSkipped("fun scaled(target: Sized, scale: Int = 1) = 0", "test.scaled(target: Sized, scale: Int): parameters with default values")

    @Test
    fun `type parameters`() =
        assertSkipped("fun <T> tagged(target: Sized, tag: T) = 0", "test.tagged(target: Sized, tag: T): type parameters")

    @Test
    fun `inline functions`() =
        assertSkipped("inline fun measured(target: Sized) = 0", "test.measured(target: Sized): inline function")

    @Test
    fun `private functions`() =
        assertSkipped("private fun hidden(target: Sized) = 0", "test.hidden(target: Sized): private or protected visibility")

    @Test
    fun `members of generic classes`() =
        assertSkipped("class Box<T> { fun put(target: Sized) = 0 }", "test.Box.put(target: Sized): member of a generic or inner class")

    @Test
    fun `supported functions and return types produce no messages`() {
        val messages = messagesFor(
            """
            fun size(target: Sized) = 0
            fun maybe(target: Sized?) = 0
            fun Sized.area() = 0
            fun sizes(vararg targets: Sized) = 0
            class Owner {
                fun area(target: Sized) = 0
                companion object { fun create(target: Sized) = 0 }
            }
            fun String.draw(target: Sized) = 0
            suspend fun later(target: Sized) = 0
            fun make(): Sized = TODO()
            """,
        )
        assertFalse("[structural]" in messages, messages)
    }
}
