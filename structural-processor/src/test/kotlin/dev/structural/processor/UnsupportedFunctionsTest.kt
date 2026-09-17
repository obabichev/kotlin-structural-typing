package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertContains
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

    private fun assertSkipped(code: String, expected: String) {
        assertContains(messagesFor(code), "[structural] Skipping $expected")
    }

    @Test
    fun `several structural parameters`() =
        assertSkipped("fun fit(a: Sized, b: Sized) = 0", "test.fit(a: Sized, b: Sized): more than one @Structural parameter")

    @Test
    fun `nullable structural parameter`() =
        assertSkipped("fun size(target: Sized?) = 0", "test.size(target: Sized?): nullable @Structural parameter")

    @Test
    fun `structural extension receiver`() =
        assertSkipped("fun Sized.area() = 0", "test.area(): a @Structural interface as extension receiver")

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
    fun `vararg parameters`() =
        assertSkipped("fun sizes(vararg targets: Sized) = 0", "test.sizes(targets: Sized): vararg parameters")

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
    fun `companion object members`() =
        assertSkipped(
            "class Owner { companion object { fun size(target: Sized) = 0 } }",
            "test.Owner.Companion.size(target: Sized): companion object member",
        )

    @Test
    fun `members of generic classes`() =
        assertSkipped("class Box<T> { fun put(target: Sized) = 0 }", "test.Box.put(target: Sized): member of a generic or inner class")

    @Test
    fun `supported functions and return types produce no warnings`() {
        val messages = messagesFor(
            """
            fun size(target: Sized) = 0
            class Owner { fun area(target: Sized) = 0 }
            fun String.draw(target: Sized) = 0
            suspend fun later(target: Sized) = 0
            fun make(): Sized = TODO()
            """,
        )
        assertFalse("[structural]" in messages, messages)
    }
}
