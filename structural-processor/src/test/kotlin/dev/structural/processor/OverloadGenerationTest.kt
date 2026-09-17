package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverloadGenerationTest {
    private val sized = """
        package test
        import dev.structural.Structural
        import test.model.*
        import kotlin.coroutines.*
        @Structural interface Sized { val width: Int; val height: Int }
    """

    private fun compileWith(model: String, code: String): Compiled =
        compile(kotlin("Model.kt", "package test.model\n$model"), kotlin("Geometry.kt", sized + code))

    private fun Compiled.runMain(): Any? {
        assertTrue(succeeded, messages)
        val generatedCodeWarnings = messages.lines().filter { it.startsWith("w:") && "/ksp/sources/" in it }
        assertEquals(emptyList(), generatedCodeWarnings, "warnings in generated code")
        return call("test.GeometryKt", "run")
    }

    @Test
    fun `original example`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int, val color: String)",
            """
            fun size(target: Sized) = target.width * target.height
            fun run() = size(Rectangular(1, 2, "red"))
            """,
        )
        assertEquals(2, compiled.runMain())
        assertContains(
            compiled.generatedFile("Geometry_Structural.kt"),
            "public fun size(target: Rectangular): Int = size(target = target.asSized())",
        )
    }

    @Test
    fun `works without the packages option`() {
        val compiled = compile(
            kotlin(
                "Geometry.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Sized { val width: Int; val height: Int }
                class Rectangular(val width: Int, val height: Int)
                fun size(target: Sized) = target.width * target.height
                fun run() = size(Rectangular(2, 3))
                """,
            ),
            options = emptyMap(),
        )
        assertEquals(6, compiled.runMain())
    }

    @Test
    fun `nullable structural parameter`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun describe(target: Sized?) = target?.width?.toString() ?: "none"
            fun run() = describe(Rectangular(4, 1)) + describe(null)
            """,
        )
        assertEquals("4none", compiled.runMain())
        assertContains(
            compiled.generatedFile("Geometry_Structural.kt"),
            "public fun describe(target: Rectangular): String = describe(target = target.asSized())",
        )
    }

    @Test
    fun `structural extension receiver`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun Sized.area() = width * height
            fun run() = Rectangular(2, 5).area()
            """,
        )
        assertEquals(10, compiled.runMain())
        assertContains(compiled.generatedFile("Geometry_Structural.kt"), "public fun Rectangular.area(): Int = this.asSized().area()")
    }

    @Test
    fun `companion object member`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            class Factory { companion object { fun measure(target: Sized) = target.width } }
            fun run() = Factory.measure(Rectangular(8, 1))
            """,
        )
        assertEquals(8, compiled.runMain())
        assertContains(
            compiled.generatedFile("Factory_Companion_Structural.kt"),
            "public fun Factory.Companion.measure(target: Rectangular): Int",
        )
    }

    @Test
    fun `vararg of one class`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun total(prefix: String, vararg targets: Sized) = prefix + targets.sumOf { it.width * it.height }
            fun run() = total("sum=", Rectangular(1, 2), Rectangular(3, 4))
            """,
        )
        assertEquals("sum=14", compiled.runMain())
        assertContains(compiled.generatedFile("Geometry_Structural.kt"), "vararg targets: Rectangular")
    }

    @Test
    fun `other vararg parameters are passed through`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun label(target: Sized, vararg parts: String) = parts.joinToString("-") + target.width
            fun run() = label(Rectangular(5, 1), "a", "b")
            """,
        )
        assertEquals("a-b5", compiled.runMain())
    }

    @Test
    fun `member function gets an extension overload`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            class Canvas { fun area(target: Sized) = target.width * target.height }
            fun run() = Canvas().area(Rectangular(2, 3))
            """,
        )
        assertEquals(6, compiled.runMain())
        assertContains(
            compiled.generatedFile("Canvas_Structural.kt"),
            "public fun Canvas.area(target: Rectangular): Int = this.area(target = target.asSized())",
        )
    }

    @Test
    fun `extension function with other parameters`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun String.describe(target: Sized, suffix: String) = this + (target.width * target.height) + suffix
            fun run() = "area=".describe(Rectangular(2, 2), "!")
            """,
        )
        assertEquals("area=4!", compiled.runMain())
    }

    @Test
    fun `suspend function`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            suspend fun measure(target: Sized) = target.width
            fun run(): Int {
                var result = 0
                val block: suspend () -> Int = { measure(Rectangular(7, 1)) }
                block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
                return result
            }
            """,
        )
        assertEquals(7, compiled.runMain())
    }

    @Test
    fun `base class overload covers subclasses and nominal subclass gets its own overload`() {
        val compiled = compileWith(
            """
            open class Base(val width: Int, val height: Int)
            class Mid : Base(1, 2)
            class Child : Base(3, 4), test.Sized
            """,
            """
            fun size(target: Sized) = target.width * target.height
            fun run() = size(Base(1, 1)) + size(Mid()) * 10 + size(Child()) * 100
            """,
        )
        assertEquals(1221, compiled.runMain())
        val overloads = compiled.generatedFile("Geometry_Structural.kt")
        assertContains(overloads, "fun size(target: Base)")
        assertContains(overloads, "fun size(target: Child)")
        assertFalse("target: Mid" in overloads, overloads)
    }

    @Test
    fun `class matching several interfaces is reported and skipped`() {
        val compiled = compileWith(
            """
            class Labeled(val width: Int, val height: Int, val name: String)
            class Person(val name: String)
            """,
            """
            @Structural interface Named { val name: String }
            fun describe(target: Sized) = "sized"
            fun describe(target: Named) = "named"
            fun run() = describe(Person("x")) + describe(Labeled(1, 1, "y").asSized())
            """,
        )
        assertEquals("namedsized", compiled.runMain())
        assertContains(
            compiled.messages,
            "[structural] Not generating test.describe for test.model.Labeled: it matches several @Structural interfaces (test.Named, test.Sized)",
        )
    }

    @Test
    fun `internal candidates get internal overloads`() {
        val compiled = compileWith(
            "internal class Secret(val width: Int, val height: Int)",
            """
            fun size(target: Sized) = target.width * target.height
            fun run() = size(Secret(2, 5))
            """,
        )
        assertEquals(10, compiled.runMain())
        assertContains(compiled.generatedFile("Geometry_Structural.kt"), "internal fun size(target: Secret): Int")
    }

    @Test
    fun `hand-written overload is kept`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun size(target: Sized) = target.width * target.height
            fun size(target: Rectangular) = -1
            fun run() = size(Rectangular(2, 2))
            """,
        )
        assertEquals(-1, compiled.runMain())
    }

    @Test
    fun `calling an unsupported function still fails to compile`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun fit(a: Sized, b: Sized) = 0
            fun run() = fit(Rectangular(1, 2), Rectangular(1, 2))
            """,
        )
        assertFalse(compiled.succeeded)
        assertContains(compiled.messages, "[structural] Skipping test.fit")
        assertContains(compiled.messages, "mismatch", ignoreCase = true)
    }
}
